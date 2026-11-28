package com.atifah.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 网关 C 端鉴权配置。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "atifah.gateway.auth")
public class GatewayAuthProperties {

    /**
     * 是否启用网关鉴权。
     * 默认开启并保持 fail-closed；仅允许在隔离的本地排障环境中显式关闭，
     * 防止新增环境或漏配配置时把业务路由直接暴露给外部。
     */
    private boolean enabled = true;

    /** C 端免鉴权规则；为空表示拒绝所有（fail-closed）。 */
    private List<AuthRule> rules = new ArrayList<>();

    /** JWT jti 黑名单查询配置。 */
    private Blacklist blacklist = new Blacklist();

    /**
     * 黑名单配置。
     */
    @Getter
    @Setter
    public static class Blacklist {

        /** 是否查询 Redis 黑名单；关闭后登出只在令牌过期时生效。 */
        private boolean enabled = true;

        /** 查询超时，避免 Redis 抖动拖慢鉴权链。 */
        private Duration timeout = Duration.ofMillis(300);

        /** 查询异常时是否放行；false 表示返回 503/50300。 */
        private boolean failOpen = true;
    }
}
