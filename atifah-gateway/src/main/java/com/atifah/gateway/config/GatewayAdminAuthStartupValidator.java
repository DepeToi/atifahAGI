package com.atifah.gateway.config;

import com.atifah.common.security.environment.SecurityEnvironmentSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * 管理端鉴权启动校验：开启鉴权且非本地环境时，禁止使用占位密钥或过短密钥。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Slf4j
@Component
public class GatewayAdminAuthStartupValidator implements InitializingBean {

    /** HS256 最低密钥长度（字节）。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final GatewayAuthProperties authProperties;

    private final GatewayAdminAuthProperties adminProperties;

    private final Environment environment;

    /**
     * 构造校验器。
     *
     * @param authProperties 网关鉴权开关
     * @param adminProperties 管理端鉴权配置
     * @param environment 运行环境
     */
    public GatewayAdminAuthStartupValidator(GatewayAuthProperties authProperties,
                                            GatewayAdminAuthProperties adminProperties,
                                            Environment environment) {
        this.authProperties = authProperties;
        this.adminProperties = adminProperties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!authProperties.isEnabled()) {
            return;
        }
        String profiles = SecurityEnvironmentSupport.require(environment);
        if (SecurityEnvironmentSupport.isNonProduction(profiles)) {
            return;
        }
        String secret = adminProperties.getSecret();
        if (!StringUtils.hasText(secret)
                || GatewayAdminAuthProperties.DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "生产环境必须配置独立强密钥 atifah.gateway.admin-auth.secret，禁止使用默认占位值（profiles="
                            + profiles + "）");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "生产环境 atifah.gateway.admin-auth.secret 至少需要 " + MIN_SECRET_BYTES
                            + " 字节（profiles=" + profiles + "）");
        }
        log.info("event=gateway_admin_auth_validated profiles={}", profiles);
    }

}
