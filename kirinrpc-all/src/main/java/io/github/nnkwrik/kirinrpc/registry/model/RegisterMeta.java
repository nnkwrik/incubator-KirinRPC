package io.github.nnkwrik.kirinrpc.registry.model;

import io.github.nnkwrik.kirinrpc.rpc.model.ServiceMeta;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * @author nnkwrik
 * @date 19/04/29 12:16
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterMeta {
    private String appName;
    //权重，用于负载均衡
    private int wight;
    // 地址
    private Address address = new Address();
    // metadata
    private ServiceMeta serviceMeta = new ServiceMeta();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Address {
        // 地址
        private String host;
        // 端口
        private int port;


    }

}
