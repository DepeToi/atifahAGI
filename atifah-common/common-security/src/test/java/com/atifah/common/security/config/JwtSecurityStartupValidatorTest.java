package com.atifah.common.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 生产 JWT 算法基线校验测试。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class JwtSecurityStartupValidatorTest {

    @Test
    void productionRejectsHs256Automatically() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm("HS256");

        assertThrows(IllegalStateException.class,
                () -> new JwtSecurityStartupValidator(environment, properties).afterPropertiesSet());
    }

    @Test
    void productionAcceptsRs256() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm("RS256");

        assertDoesNotThrow(
                () -> new JwtSecurityStartupValidator(environment, properties).afterPropertiesSet());
    }

    @Test
    void missingProfileFallsBackToLocalSemantics() {
        MockEnvironment environment = new MockEnvironment();
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm("HS256");

        assertDoesNotThrow(
                () -> new JwtSecurityStartupValidator(environment, properties).afterPropertiesSet());
    }

    @Test
    void localKeepsHs256() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm("HS256");

        assertDoesNotThrow(
                () -> new JwtSecurityStartupValidator(environment, properties).afterPropertiesSet());
    }

    @Test
    void containerWithLocalProfileKeepsHs256() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("container", "local");
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm("HS256");

        assertDoesNotThrow(
                () -> new JwtSecurityStartupValidator(environment, properties).afterPropertiesSet());
    }

    @Test
    void bareContainerProfileIsRejected() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("container");
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm("HS256");

        assertThrows(IllegalStateException.class,
                () -> new JwtSecurityStartupValidator(environment, properties).afterPropertiesSet());
    }

    @Test
    void unknownProfileCountsAsProduction() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("uat");
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm("HS256");

        assertThrows(IllegalStateException.class,
                () -> new JwtSecurityStartupValidator(environment, properties).afterPropertiesSet());
    }

    @Test
    void testEnvironmentAllowsHs256() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        JwtProperties properties = new JwtProperties();
        properties.setAlgorithm("HS256");

        assertDoesNotThrow(
                () -> new JwtSecurityStartupValidator(environment, properties).afterPropertiesSet());
    }
}
