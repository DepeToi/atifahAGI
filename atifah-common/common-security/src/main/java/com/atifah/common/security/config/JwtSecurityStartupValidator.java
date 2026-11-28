package com.atifah.common.security.config;

import com.atifah.common.security.environment.SecurityEnvironmentSupport;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * JWT 安全基线校验：生产环境必须使用 RS256，避免网关继续持有签发密钥。
 *
 * <p>仅对显式启用 JWT Provider 的服务装配。运行环境由 Nacos namespace 区分、生产部署资产
 * 内置 prod profile 触发；未激活 profile 或 {@code local} / {@code dev} / {@code test} /
 * {@code container} 可使用 HS256，其他环境一律要求 RS256。</p>
 *
 * @author DepeToi
 * @since 1.0.0
 */
public class JwtSecurityStartupValidator implements InitializingBean {

    private final Environment environment;

    private final JwtProperties properties;

    public JwtSecurityStartupValidator(Environment environment, JwtProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        String profiles = SecurityEnvironmentSupport.require(environment);
        if (SecurityEnvironmentSupport.isNonProduction(profiles)) {
            return;
        }
        String algorithm = properties.getAlgorithm() == null
                ? "" : properties.getAlgorithm().trim().toUpperCase(Locale.ROOT);
        if (!"RS256".equals(algorithm)) {
            throw new IllegalStateException(
                    "生产环境必须使用 RS256 签发/校验 JWT（profiles=" + profiles + "，当前 "
                            + properties.getAlgorithm() + "）");
        }
    }
}
