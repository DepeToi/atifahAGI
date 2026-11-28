package com.atifah.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关异步日志配置。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "atifah.logging.async")
public class GatewayAsyncLogProperties {

    /** 是否把 root appender 包装为 AsyncAppender。 */
    private boolean enabled = true;

    /** 异步队列容量。 */
    private int queueSize = 4096;
}
