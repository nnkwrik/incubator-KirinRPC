package io.github.nnkwrik.kirinrpc.rpc.provider;

import io.github.nnkwrik.kirinrpc.rpc.model.ServiceMeta;
import io.github.nnkwrik.kirinrpc.rpc.model.ServiceWrapper;
import io.github.nnkwrik.kirinrpc.springboot.annotation.KirinProvideService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * @author nnkwrik
 * @date 19/05/18 18:18
 */
@Slf4j
public class ServiceBeanContainer implements ProviderLookup {

    private final ConcurrentMap<String, Object> serviceBeans = new ConcurrentHashMap<>();

    public List<ServiceMeta> addServiceBeans(Collection<Object> serviceBeans) {
        List<ServiceMeta> serviceMetaList = new ArrayList<>();
        for (Object serviceBean : serviceBeans) {
            serviceMetaList.addAll(addServiceBean(serviceBean));
        }
        return serviceMetaList;
    }

    public List<ServiceMeta> addServiceBean(Object serviceBean) {
        List<ServiceMeta> serviceMetaList = new ArrayList<>();

        List<String> interfaceName = Arrays.stream(serviceBean.getClass().getInterfaces())
                .map(Class::getName).collect(Collectors.toList());

        String serviceGroup = serviceBean.getClass().getAnnotation(KirinProvideService.class).group();
        interfaceName.stream().forEach(serviceName -> {
            log.info("Loading service: {} ,addressChannel : {}", serviceName, serviceGroup);
            ServiceMeta serviceMeta = new ServiceMeta(serviceName, serviceGroup);
            String serviceKey = serviceGroup + "/" + serviceName;
            if (serviceBeans.putIfAbsent(serviceKey, serviceBean) != null) {
                log.warn("Already have instance for service(serviceName={} ,group={}).The instance is {},can't overwrite by {}.",
                        serviceName, serviceGroup, serviceBeans.get(serviceKey), serviceBean);
            } else {
                serviceMetaList.add(serviceMeta);
            }
        });
        return serviceMetaList;
    }

    @Override
    public ServiceWrapper lookupService(ServiceMeta serviceMeta) {
        Object serviceBean = serviceBeans.get(serviceMeta.getServiceGroup() + "/" + serviceMeta.getServiceName());
        if (serviceBean == null) return null;
        return new ServiceWrapper(serviceBean);
    }
}
