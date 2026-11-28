package com.atifah.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CORS 启动校验测试：生产必须收敛来源，本地允许通配。
 *
 * <p>校验对象是实际生效的网关 CORS 配置项
 * {@code spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-origin-patterns}。</p>
 *
 * @author DepeToi
 * @since 1.0.0
 */
class GatewayCorsStartupValidatorTest {

    private static final String ALLOWED_ORIGINS_PROPERTY =
            "spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-origin-patterns";

    @Test
    void rejectsBlankOriginsInProduction() {
        GatewayCorsStartupValidator validator = new GatewayCorsStartupValidator(env("prod", ""));

        assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
    }

    @Test
    void rejectsWildcardOriginsInProduction() {
        GatewayCorsStartupValidator validator = new GatewayCorsStartupValidator(env("prod", "*"));

        assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
    }

    @Test
    void acceptsConcreteOriginsInProduction() {
        GatewayCorsStartupValidator validator =
                new GatewayCorsStartupValidator(env("prod", "https://admin.example.com"));

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    void allowsWildcardInLocalEnvironment() {
        GatewayCorsStartupValidator validator = new GatewayCorsStartupValidator(env("local", "*"));

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    @Test
    void missingProfileTreatedAsLocalAllowsWildcard() {
        GatewayCorsStartupValidator validator =
                new GatewayCorsStartupValidator(env(null, "*"));

        assertDoesNotThrow(validator::afterPropertiesSet);
    }

    /**
     * 构造带激活 profile 与 CORS 来源配置的测试环境。
     *
     * @param profile 激活 profile，null 表示不激活任何 profile
     * @param origins CORS 来源串
     * @return 测试环境
     */
    private MockEnvironment env(String profile, String origins) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(ALLOWED_ORIGINS_PROPERTY, origins);
        if (profile != null) {
            environment.setActiveProfiles(profile);
        }
        return environment;
    }
}
