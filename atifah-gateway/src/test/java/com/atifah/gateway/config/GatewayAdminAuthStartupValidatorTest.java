package com.atifah.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 管理端密钥启动校验测试：生产禁止占位/空/过短密钥。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class GatewayAdminAuthStartupValidatorTest {

    private static final String STRONG_SECRET = "01234567890123456789012345678901";

    @Test
    void rejectsDefaultSecretInProductionWhenAuthEnabled() {
        GatewayAuthProperties auth = auth(true);
        GatewayAdminAuthProperties admin = admin(GatewayAdminAuthProperties.DEFAULT_SECRET);

        assertThrows(IllegalStateException.class,
                () -> new GatewayAdminAuthStartupValidator(auth, admin, env("prod")).afterPropertiesSet());
    }

    @Test
    void rejectsBlankSecretInProductionWhenAuthEnabled() {
        GatewayAuthProperties auth = auth(true);
        GatewayAdminAuthProperties admin = admin(" ");

        assertThrows(IllegalStateException.class,
                () -> new GatewayAdminAuthStartupValidator(auth, admin, env("prod")).afterPropertiesSet());
    }

    @Test
    void rejectsShortSecretInProductionWhenAuthEnabled() {
        GatewayAuthProperties auth = auth(true);
        GatewayAdminAuthProperties admin = admin("short-secret");

        assertThrows(IllegalStateException.class,
                () -> new GatewayAdminAuthStartupValidator(auth, admin, env("prod")).afterPropertiesSet());
    }

    @Test
    void acceptsStrongSecretInProductionWhenAuthEnabled() {
        GatewayAuthProperties auth = auth(true);
        GatewayAdminAuthProperties admin = admin(STRONG_SECRET);

        assertDoesNotThrow(
                () -> new GatewayAdminAuthStartupValidator(auth, admin, env("prod")).afterPropertiesSet());
    }

    @Test
    void allowsDefaultSecretInLocalEnvironment() {
        GatewayAuthProperties auth = auth(true);
        GatewayAdminAuthProperties admin = admin(GatewayAdminAuthProperties.DEFAULT_SECRET);

        assertDoesNotThrow(
                () -> new GatewayAdminAuthStartupValidator(auth, admin, env("local")).afterPropertiesSet());
    }

    @Test
    void skipsValidationWhenAuthDisabled() {
        GatewayAuthProperties auth = auth(false);
        GatewayAdminAuthProperties admin = admin(GatewayAdminAuthProperties.DEFAULT_SECRET);

        assertDoesNotThrow(
                () -> new GatewayAdminAuthStartupValidator(auth, admin, env("prod")).afterPropertiesSet());
    }

    @Test
    void missingProfileTreatedAsLocalAllowsDefaultSecret() {
        GatewayAuthProperties auth = auth(true);
        GatewayAdminAuthProperties admin = admin(GatewayAdminAuthProperties.DEFAULT_SECRET);

        assertDoesNotThrow(
                () -> new GatewayAdminAuthStartupValidator(auth, admin, new MockEnvironment()).afterPropertiesSet());
    }

    private MockEnvironment env(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }

    private GatewayAuthProperties auth(boolean enabled) {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    private GatewayAdminAuthProperties admin(String secret) {
        GatewayAdminAuthProperties properties = new GatewayAdminAuthProperties();
        properties.setSecret(secret);
        return properties;
    }
}
