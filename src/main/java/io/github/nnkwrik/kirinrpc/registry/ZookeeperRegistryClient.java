package io.github.nnkwrik.kirinrpc.registry;

import io.github.nnkwrik.kirinrpc.common.util.JsonUtil;
import io.github.nnkwrik.kirinrpc.common.util.StackTraceUtil;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
    private final ConcurrentMap<ServiceMeta, CopyOnWriteArrayList<NotifyListener>> subscribeListeners = new ConcurrentHashMap<>();
    //服务对应的zookeeper 路径信息
    private final ConcurrentMap<ServiceMeta, PathChildrenCache> pathChildrenCaches = new ConcurrentHashMap<>();
    //指定节点都提供了哪些服务
    private final ConcurrentMap<RegisterMeta.Address, Set<ServiceMeta>> serviceMetaMap = new ConcurrentHashMap<>();

    public ZookeeperRegistryClient(String registryAddress) {
        this.registryAddress = registryAddress;
    }

    @Override
    public void connect(String registryAddr) {
        configClient = CuratorFrameworkFactory.newClient(
                registryAddress, sessionTimeoutMs, connectionTimeoutMs, new ExponentialBackoffRetry(500, 20));

        configClient.getConnectionStateListenable().addListener((client, newState) -> {
            log.info("Zookeeper connection state changed {}.", newState);
            if (newState == ConnectionState.CONNECTED) {
                isConnected.countDown();
            } else if (newState == ConnectionState.RECONNECTED) {

                log.info("Zookeeper connection has been re-established, will re-subscribe and re-addServiceBean.");
//                // 重新订阅
//                for (RegisterMeta.ServiceMeta serviceMeta : getSubscribeSet()) {
//                    doSubscribe(serviceMeta);
//                }
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
                    log.warn("[addServiceBean.executor] interrupted.");
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
            configClient.create().withMode(CreateMode.EPHEMERAL).inBackground((client, event) -> {
                if (event.getResultCode() == KeeperException.Code.OK.intValue()) {
                    registerMetaMap.put(meta, RegisterState.DONE);
                }
                log.info("Register on zookeeper: {} - {}.", meta, event);
            }).forPath(String.format("%s/%s",
                    directory,
                    JsonUtil.toJson(meta)));
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Create addServiceBean meta: {} path failed, {}.", meta, StackTraceUtil.stackTrace(e));
            }
        }

    }

    @Override
    public void subscribe(ServiceMeta serviceMeta, NotifyListener listener) {
        CopyOnWriteArrayList<NotifyListener> listeners = subscribeListeners.get(serviceMeta);
        if (listeners == null) {
            CopyOnWriteArrayList<NotifyListener> newListeners = new CopyOnWriteArrayList<>();
            listeners = subscribeListeners.putIfAbsent(serviceMeta, newListeners);
            if (listeners == null) {
                listeners = newListeners;
            }
        }
        listeners.add(listener);
        watchNode(serviceMeta);
    }

    private void watchNode(final ServiceMeta meta) {

        PathChildrenCache newPathChildren = createNewPathChildren(meta);
        if (newPathChildren == null) return;

        //创建了新的newPathChildren
        newPathChildren.getListenable().addListener((client, event) -> {

            log.info("Child event: {}", event);

            switch (event.getType()) {
                case CHILD_ADDED: {
                    //获取新添加的服务信息
                    RegisterMeta registerMeta = parseRegisterMeta(event.getData().getPath());
                    ServiceMeta serviceMeta = registerMeta.getServiceMeta();

                    //作为address对应的服务添加到添加到map里
                    RegisterMeta.Address address = registerMeta.getAddress();
                    Set<ServiceMeta> serviceMetaSet = getServiceMeta(address);
                    serviceMetaSet.add(serviceMeta);

                    //通知该服务绑定的listener，告诉他们address开始提供服务
                    notify(serviceMeta, NotifyListener.NotifyEvent.CHILD_ADDED, registerMeta);

                    break;
                }
                case CHILD_REMOVED: {
                    //获取要移除的服务信息
                    RegisterMeta registerMeta = parseRegisterMeta(event.getData().getPath());
                    ServiceMeta serviceMeta = registerMeta.getServiceMeta();

                    //从address对应的服务里面移除
                    RegisterMeta.Address address = registerMeta.getAddress();
                    Set<ServiceMeta> serviceMetaSet = getServiceMeta(address);
                    serviceMetaSet.remove(serviceMeta);

                    //通知该服务绑定的listener，告诉他们address停止提供服务
                    notify(serviceMeta, NotifyListener.NotifyEvent.CHILD_REMOVED, registerMeta);

                    //address下已经不提供任何服务
                    if (serviceMetaSet.isEmpty()) {
                        log.info("Offline notify: {}.", address);
                    }

                    break;
                }
            }
        });
    }

    private PathChildrenCache createNewPathChildren(ServiceMeta serviceMeta) {
        if (!pathChildrenCaches.containsKey(serviceMeta)) return null;

        String directory = String.format("/kirinrpc/%s/%s",
                serviceMeta.getServiceGroup(),
                serviceMeta.getServiceName());
        PathChildrenCache newChildrenCache = new PathChildrenCache(configClient, directory, false);

        if (pathChildrenCaches.putIfAbsent(serviceMeta, newChildrenCache) != null) {
            //新创建的PathChildrenCache没有用上 （putIfAbsent失败了）
            try {
                newChildrenCache.close();
                return null;
            } catch (IOException e) {
                if (log.isWarnEnabled()) {
                    log.warn("Close [PathChildrenCache] {} failed, {}.", directory, StackTraceUtil.stackTrace(e));
                }
            }
        }

        return newChildrenCache;
    }

    private RegisterMeta parseRegisterMeta(String data) {
        String[] array_0 = data.split("/");
        return JsonUtil.fromJson(array_0[3], RegisterMeta.class);
    }

    private Set<ServiceMeta> getServiceMeta(RegisterMeta.Address address) {
        Set<ServiceMeta> serviceMetaSet = serviceMetaMap.get(address);
        if (serviceMetaSet == null) {
            Set<ServiceMeta> newServiceMetaSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
            serviceMetaSet = serviceMetaMap.putIfAbsent(address, newServiceMetaSet);
            if (serviceMetaSet == null) {
                serviceMetaSet = newServiceMetaSet;
            }
        }
        return serviceMetaSet;
    }

    // 通知新增或删除服务
    private void notify(ServiceMeta serviceMeta, NotifyListener.NotifyEvent event, RegisterMeta... array) {

        if (array == null || array.length == 0) {
            return;
        }

        CopyOnWriteArrayList<NotifyListener> listeners = subscribeListeners.get(serviceMeta);
        if (listeners != null) {
            for (NotifyListener l : listeners) {
                for (RegisterMeta m : array) {
                    l.notify(m, event);
                }
            }
        }
    }


}
