package io.github.nnkwrik.kirinrpc.registry;

import io.github.nnkwrik.kirinrpc.common.util.JsonUtil;
import io.github.nnkwrik.kirinrpc.common.util.StackTraceUtil;
import io.github.nnkwrik.kirinrpc.registry.listener.NotifyListener;
import io.github.nnkwrik.kirinrpc.registry.model.RegisterMeta;
import io.github.nnkwrik.kirinrpc.rpc.model.ServiceMeta;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author nnkwrik
 * @date 19/04/29 10:24
 */
@Slf4j
public class ZookeeperRegistryClient implements RegistryClient {
    private String registryAddress;

    //zk客户端
    private CuratorFramework configClient;
    private final int sessionTimeoutMs = 60 * 1000;
    private final int connectionTimeoutMs = 15 * 1000;

    //准备注册的RegisterMeta
    private final LinkedBlockingDeque<RegisterMeta> metaQueue = new LinkedBlockingDeque<>();
    //进行注册的线程池
    private final ExecutorService registryExecutor = Executors.newSingleThreadExecutor();
    //注册失败时进行重试的线程池
    private final ScheduledExecutorService registerScheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    //与注册中心连接成功
    private CountDownLatch isConnected = new CountDownLatch(1);

    // Provider已发布的注册信息
    private final ConcurrentMap<RegisterMeta, RegisterState> registerMetaMap = new ConcurrentHashMap<>();

    //服务都绑定了哪些listener
    private final ConcurrentMap<ServiceMeta, NotifyListener> subscribeListener = new ConcurrentHashMap<>();

    public ZookeeperRegistryClient(String registryAddress) {
        this.registryAddress = registryAddress;
    }

    @Override
    public void connect() {
        configClient = CuratorFrameworkFactory.newClient(
                registryAddress, sessionTimeoutMs, connectionTimeoutMs, new ExponentialBackoffRetry(500, 20));

        configClient.getConnectionStateListenable().addListener((client, newState) -> {
            log.info("Zookeeper connection state changed {}.", newState);
            if (newState == ConnectionState.CONNECTED) {
                isConnected.countDown();
            } else if (newState == ConnectionState.RECONNECTED) {

                log.info("Zookeeper connection has been re-established, will re-subscribe and re-addServiceBeans.");
//                // 重新订阅
                for (ServiceMeta serviceMeta : subscribeListener.keySet()) {
                    watchNode(serviceMeta);
                }
                // 重新发布服务
                register(new ArrayList<>(registerMetaMap.keySet()));
            }
        });
        configClient.start();
    }

    @Override
    public void register(List<RegisterMeta> registerMetas) {
        metaQueue.addAll(registerMetas);
        registryExecutor.execute(() -> {
            try {
                isConnected.await();
            } catch (InterruptedException e) {
                log.warn("Interrupted when wait connect to registry server.");
            }
            while (!shutdown.get()) {
                RegisterMeta meta = null;
                try {
                    meta = metaQueue.poll(5, TimeUnit.SECONDS);
                    if (meta == null) break; //5秒内没有获取到需要注册的meta，说明所有meta都已经注册成功。
                    registerMetaMap.put(meta, RegisterState.PREPARE);
                    createNode(meta);
                } catch (InterruptedException e) {
                    log.warn("[addServiceBeans.executor] interrupted.");
                } catch (Throwable t) {
                    if (meta != null) {
                        log.error("Register [{}] fail: {}, will try again...", meta.getServiceMeta(), t.getStackTrace());

                        // 间隔一段时间再重新入队, 让出cpu
                        final RegisterMeta finalMeta = meta;
                        registerScheduledExecutor.schedule(() -> {
                            createNode(finalMeta);
                        }, 1, TimeUnit.SECONDS);
                    }
                }
            }
        });

    }

    private void createNode(final RegisterMeta meta) {
        String directory = String.format("/kirinrpc/%s/%s",
                meta.getServiceMeta().getServiceGroup(),
                meta.getServiceMeta().getServiceName());

        try {
            if (configClient.checkExists().forPath(directory) == null) {
                configClient.create().creatingParentsIfNeeded().forPath(directory);
            }
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Create parent path failed, directory: {}, {}.", directory, e.getStackTrace());
            }
        }

        // The znode will be deleted upon the client's disconnect.
        try {
            configClient.create().withMode(CreateMode.EPHEMERAL_SEQUENTIAL).inBackground((client, event) -> {
                if (event.getResultCode() == KeeperException.Code.OK.intValue()) {
                    registerMetaMap.put(meta, RegisterState.DONE);
                }
                log.info("Register on zookeeper: {} - {}.", meta, event);
            }).forPath(String.format("%s/%s",
                    directory,
                    JsonUtil.toJson(meta)));
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Create addServiceBeans meta: {} path failed, {}.", meta, StackTraceUtil.stackTrace(e));
            }
        }

    }

    @Override
    public void subscribe(ServiceMeta serviceMeta, NotifyListener listener) {
        if (subscribeListener.get(serviceMeta) == null) {
            if (subscribeListener.putIfAbsent(serviceMeta, listener) == null) {
                //added new listener
                watchNode(serviceMeta);
            }
        }
    }

    private void watchNode(final ServiceMeta meta) {

        String directory = String.format("/kirinrpc/%s/%s",
                meta.getServiceGroup(),
                meta.getServiceName());
        PathChildrenCache newPathChildren = new PathChildrenCache(configClient, directory, false);

        newPathChildren.getListenable().addListener((client, event) -> {


            log.info("Child event: {}.", event);

            switch (event.getType()) {
                case CHILD_ADDED: {
                    long sequenceNum = parseSequenceNum(event.getData().getPath());
                    RegisterMeta registerMeta = parseRegisterMeta(event.getData().getPath());
                    if (registerMeta == null) {
                        //json解析失败
                        return;
                    }

                    //通知该服务绑定的listener，告诉他们address开始提供服务
                    NotifyListener listener = subscribeListener.get(registerMeta.getServiceMeta());
                    if (listener != null) {
                        listener.notify(registerMeta, sequenceNum, NotifyListener.NotifyEvent.CHILD_ADDED);
                    }

                    break;
                }
                case CHILD_REMOVED: {
                    long sequenceNum = parseSequenceNum(event.getData().getPath());
                    RegisterMeta registerMeta = parseRegisterMeta(event.getData().getPath());
                    if (registerMeta == null) {
                        //json解析失败
                        return;
                    }

                    //获取要移除的服务信息
                    ServiceMeta serviceMeta = registerMeta.getServiceMeta();

                    //通知该服务绑定的listener，告诉他们address停止提供服务
                    NotifyListener listener = subscribeListener.get(serviceMeta);
                    if (listener != null) {
                        listener.notify(registerMeta, sequenceNum, NotifyListener.NotifyEvent.CHILD_REMOVED);
                    }

                    break;
                }
            }
        });

        try {
            newPathChildren.start();
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Subscribe failed, {}.", StackTraceUtil.stackTrace(e));
            }
        }
    }

    private long parseSequenceNum(String data) {
        String[] array_0 = data.split("/");
        String meta = array_0[4];
        return Long.parseLong(meta.substring(meta.length() - 10));
    }

    private RegisterMeta parseRegisterMeta(String data) {
        String[] array_0 = data.split("/");
        String meta = array_0[4];
        return JsonUtil.fromJson(meta, RegisterMeta.class);
    }
}
