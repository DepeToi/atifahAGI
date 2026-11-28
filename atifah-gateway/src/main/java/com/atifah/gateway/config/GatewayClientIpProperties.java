package com.atifah.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实客户端 IP 解析配置。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "atifah.gateway.client-ip")
public class GatewayClientIpProperties {

    /**
     * 受信代理网段（CIDR 或单 IP 字面量）。
     * 默认只信任回环与 RFC1918 / ULA 内网段；公网代理必须经配置显式追加（本地 yaml 或 Nacos 网关私有配置）。
     */
    private List<String> trustedProxies = new ArrayList<>(List.of(
            "127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
            "192.168.0.0/16", "fc00::/7"));
}
