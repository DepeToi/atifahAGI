package com.atifah.common.security.secret;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 配置密钥解密入口测试：test profile 跳过、profile 缺省按本地语义、生产哑主密钥防呆。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class SecretEnvironmentPostProcessorTest {

    private final SecretEnvironmentPostProcessor processor = new SecretEnvironmentPostProcessor();

    @Test
    void testProfileSkipsDecryptionAndGuard() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        environment.setProperty("spring.datasource.password", "plain-root");

        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, null));
    }

    @Test
    void missingProfileDefaultsToLocalSemantics() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        SecretPropertySourceScanner.DEV_MASTER_KEY);

        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, null));
    }

    @Test
    void explicitProfileDecryptsAndEnforcesGuard() {
        byte[] masterKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        java.util.Base64.getEncoder().encodeToString(masterKey))
                .withProperty("spring.datasource.password", SecretCipher.encrypt(masterKey, "root"));
        environment.setActiveProfiles("local");

        processor.postProcessEnvironment(environment, null);

        assertEquals("root", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void plaintextSecretUnderExplicitProfileIsRejected() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.password", "root");
        environment.setActiveProfiles("local");

        assertThrows(IllegalStateException.class,
                () -> processor.postProcessEnvironment(environment, null));
    }

    @Test
    void productionRejectsRepositoryDevMasterKey() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        SecretPropertySourceScanner.DEV_MASTER_KEY);
        environment.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class,
                () -> processor.postProcessEnvironment(environment, null));
    }

    @Test
    void containerWithLocalProfileAcceptsDevMasterKey() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        SecretPropertySourceScanner.DEV_MASTER_KEY);
        environment.setActiveProfiles("container", "local");

        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, null));
    }

    @Test
    void bareContainerProfileIsRejected() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        SecretPropertySourceScanner.DEV_MASTER_KEY);
        environment.setActiveProfiles("container");

        assertThrows(IllegalStateException.class,
                () -> processor.postProcessEnvironment(environment, null));
    }

    @Test
    void productionAcceptsRealMasterKey() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        SecretCipher.generateMasterKey());
        environment.setActiveProfiles("prod");

        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, null));
    }
}
