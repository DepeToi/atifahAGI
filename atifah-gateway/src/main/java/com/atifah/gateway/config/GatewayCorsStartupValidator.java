package com.atifah.gateway.config;

import com.atifah.common.security.environment.SecurityEnvironmentSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;

/**
 * CORS 来源启动校验：生产环境必须显式收敛来源，漏配或裸通配符直接拒绝启动。
 *
 * <p>运行环境由 Nacos namespace 区分；激活 profile 出现 {@code local} / {@code dev} /
 * {@code test} / {@code container} 之外的值（含生产部署资产内置的 prod）时视为生产，
 * 此时 CORS 来源必须配置为具体域名且不得包含裸 {@code *}。
 * 校验对象是实际生效的网关 CORS 配置项（本地 application.yml 或 Nacos 网关私有配置覆盖后的值）。</p>
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Slf4j
@Component
public class GatewayCorsStartupValidator implements InitializingBean {

    /** 网关全局 CORS 来源配置键（SCG 4.3 webflux 变体 key）。 */
    private static final String ALLOWED_ORIGINS_PROPERTY =
            "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-origin-patterns";

    private final Environment environment;

    /**
     * 构造校验器。
     *
     * @param environment 运行环境
     */
    public GatewayCorsStartupValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        String profiles = SecurityEnvironmentSupport.require(environment);
        String allowedOrigins = resolveAllowedOrigins();
        if (SecurityEnvironmentSupport.isNonProduction(profiles)) {
            if (hasWildcard(allowedOrigins)) {
                log.warn("event=gateway_cors_wildcard profiles={} hint=生产环境必须收敛 CORS 来源", profiles);
            }
            return;
        }
        if (!StringUtils.hasText(allowedOrigins)) {
            throw new IllegalStateException(
                    "生产环境必须配置网关 CORS 来源（" + ALLOWED_ORIGINS_PROPERTY + "），当前为空（profiles=" + profiles + "）");
        }
        if (hasWildcard(allowedOrigins)) {
            throw new IllegalStateException(
                    "生产环境网关 CORS 来源不得包含通配符 *（profiles=" + profiles + "）");
        }
        log.info("event=gateway_cors_validated profiles={} origins={}", profiles, countOrigins(allowedOrigins));
    }

    /**
     * 读取实际生效的 CORS 来源配置（本地 yaml 或 Nacos 网关私有配置覆盖后的值）。
     *
     * @return 逗号分隔的来源串，未配置时为 {@code *}
     */
    private String resolveAllowedOrigins() {
        return Binder.get(environment)
                .bind(ALLOWED_ORIGINS_PROPERTY, String.class)
                .orElse("*");
    }

    /**
     * 判断来源列表中是否存在裸通配符。
     *
     * @param allowedOrigins 逗号分隔的来源串
     * @return 是否存在 {@code *}
     */
    private boolean hasWildcard(String allowedOrigins) {
        if (allowedOrigins == null) {
            return false;
        }
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .anyMatch("*"::equals);
    }

    /**
     * 统计配置的来源数量，避免日志直接输出内部域名。
     *
     * @param allowedOrigins 逗号分隔的来源串
     * @return 来源数量
     */
    private long countOrigins(String allowedOrigins) {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .count();
    }

}
