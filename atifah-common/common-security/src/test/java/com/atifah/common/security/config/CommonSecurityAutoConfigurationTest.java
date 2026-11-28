package com.atifah.common.security.config;

import com.atifah.common.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公共安全自动装配测试：JWT Provider 必须由服务显式启用。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class CommonSecurityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonSecurityAutoConfiguration.class));

    @Test
    void businessServiceDoesNotLoadJwtProviderByDefault() {
        contextRunner.run(context -> {
            assertFalse(context.containsBean("jwtTokenProvider"));
            assertFalse(context.containsBean("jwtSecurityStartupValidator"));
            assertNotNull(context.getBean(PasswordEncoder.class));
        });
    }

    @Test
    void signingServiceLoadsJwtProviderWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "atifah.security.jwt.provider-enabled=true",
                        "atifah.security.jwt.secret=01234567890123456789012345678901")
                .run(context -> {
                    assertTrue(context.containsBean("jwtTokenProvider"));
                    assertTrue(context.containsBean("jwtSecurityStartupValidator"));
                    assertNotNull(context.getBean(JwtTokenProvider.class));
                });
    }
}
