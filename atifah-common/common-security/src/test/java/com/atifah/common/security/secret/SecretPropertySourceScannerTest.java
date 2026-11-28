package com.atifah.common.security.secret;

import org.junit.jupiter.api.Test;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置密钥解析与解密测试：生效值解密、被覆盖本地密文跳过、明文守卫、
 * 主密钥缺失拒绝、幂等性、刷新原子性与 OriginTrackedValue 兼容。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class SecretPropertySourceScannerTest {

    @Test
    void decryptsEncryptedValuesAcrossPropertyKeys() {
        byte[] masterKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        Base64.getEncoder().encodeToString(masterKey))
                .withProperty("spring.datasource.password", SecretCipher.encrypt(masterKey, "root"))
                .withProperty("atifah.security.jwt.secret", SecretCipher.encrypt(masterKey, "dev-secret"));

        SecretPropertySourceScanner.decrypt(environment, true);

        assertEquals("root", environment.getProperty("spring.datasource.password"));
        assertEquals("dev-secret", environment.getProperty("atifah.security.jwt.secret"));
    }

    @Test
    void decryptionIsIdempotent() {
        byte[] masterKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        Base64.getEncoder().encodeToString(masterKey))
                .withProperty("spring.datasource.password", SecretCipher.encrypt(masterKey, "root"));

        SecretPropertySourceScanner.decrypt(environment, true);
        SecretPropertySourceScanner.decrypt(environment, false);

        assertEquals("root", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void guardRejectsPlaintextSecret() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.password", "root");

        assertThrows(IllegalStateException.class,
                () -> SecretPropertySourceScanner.decrypt(environment, true));
    }

    @Test
    void guardAllowsBlankSecret() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.data.redis.password", "");

        SecretPropertySourceScanner.decrypt(environment, true);

        assertEquals("", environment.getProperty("spring.data.redis.password"));
    }

    @Test
    void missingMasterKeyFailsWhenCipherPresent() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.password",
                        SecretCipher.encrypt(SecretCipher.parseMasterKey(SecretCipher.generateMasterKey()), "root"));

        assertThrows(IllegalStateException.class,
                () -> SecretPropertySourceScanner.decrypt(environment, true));
    }

    @Test
    void encryptedMasterKeyIsRejected() {
        byte[] masterKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        SecretCipher.encrypt(masterKey, "not-a-key"))
                .withProperty("spring.datasource.password", SecretCipher.encrypt(masterKey, "root"));

        assertThrows(IllegalStateException.class,
                () -> SecretPropertySourceScanner.decrypt(environment, true));
    }

    @Test
    void onlyDecryptsEffectiveValueAndIgnoresShadowedLocalCipher() {
        // 生产场景回归：本地 application.yml 携带开发哑主密钥加密的哑密文，Nacos 生产命名空间
        // 同名键覆盖下发真实密文；只解密生效值，被覆盖的本地哑密文不参与解密（否则生产必败）。
        byte[] prodKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        byte[] devKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        Base64.getEncoder().encodeToString(prodKey));
        environment.getPropertySources().addFirst(new MapPropertySource("nacos-prod", Map.of(
                "spring.datasource.password", SecretCipher.encrypt(prodKey, "prod-root"),
                "atifah.security.jwt.secret", SecretCipher.encrypt(prodKey, "prod-secret"))));
        environment.getPropertySources().addLast(new MapPropertySource("localYml", Map.of(
                "spring.datasource.password", SecretCipher.encrypt(devKey, "dev-root"),
                "atifah.security.jwt.secret", SecretCipher.encrypt(devKey, "dev-secret"))));

        SecretPropertySourceScanner.decrypt(environment, true);

        assertEquals("prod-root", environment.getProperty("spring.datasource.password"));
        assertEquals("prod-secret", environment.getProperty("atifah.security.jwt.secret"));
    }

    @Test
    void unoverriddenLocalCipherStillFailsClosed() {
        // 生产场景：本地哑密文未被 Nacos 覆盖时仍是生效值，用生产主密钥解不开必须拒绝启动。
        byte[] prodKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        byte[] devKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        Base64.getEncoder().encodeToString(prodKey));
        environment.getPropertySources().addFirst(new MapPropertySource("nacos-prod", Map.of(
                "atifah.security.jwt.secret", SecretCipher.encrypt(prodKey, "prod-secret"))));
        environment.getPropertySources().addLast(new MapPropertySource("localYml", Map.of(
                "spring.datasource.password", SecretCipher.encrypt(devKey, "dev-root"))));

        assertThrows(IllegalStateException.class,
                () -> SecretPropertySourceScanner.decrypt(environment, true));
    }

    @Test
    void refreshRebuildsDecryptedValuesAtomically() {
        byte[] masterKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        Base64.getEncoder().encodeToString(masterKey))
                .withProperty("spring.datasource.password", SecretCipher.encrypt(masterKey, "root"));

        SecretPropertySourceScanner.decrypt(environment, true);
        assertEquals("root", environment.getProperty("spring.datasource.password"));

        // 刷新：上游密文更新后重放解密拿到新明文
        environment.setProperty("spring.datasource.password", SecretCipher.encrypt(masterKey, "root2"));
        SecretPropertySourceScanner.decrypt(environment, false);
        assertEquals("root2", environment.getProperty("spring.datasource.password"));

        // 失败原子性：上游换成其他主密钥的坏密文时重放失败，且派生源保留上一个一致明文
        byte[] otherKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        environment.setProperty("spring.datasource.password", SecretCipher.encrypt(otherKey, "broken"));
        assertThrows(IllegalStateException.class,
                () -> SecretPropertySourceScanner.decrypt(environment, false));
        assertEquals("root2", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void removedUpstreamCipherDropsDecryptedSource() {
        byte[] masterKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        Map<String, Object> upstream = new HashMap<>();
        upstream.put("spring.datasource.password", SecretCipher.encrypt(masterKey, "root"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        Base64.getEncoder().encodeToString(masterKey));
        environment.getPropertySources().addFirst(new MapPropertySource("localYml", upstream));

        SecretPropertySourceScanner.decrypt(environment, true);
        assertEquals("root", environment.getProperty("spring.datasource.password"));
        assertTrue(environment.getPropertySources()
                .contains(SecretPropertySourceScanner.DECRYPTED_SOURCE_NAME));

        // 上游密文下线后重放解密，派生源同步移除、不残留旧明文
        upstream.remove("spring.datasource.password");
        SecretPropertySourceScanner.decrypt(environment, false);
        assertFalse(environment.getPropertySources()
                .contains(SecretPropertySourceScanner.DECRYPTED_SOURCE_NAME));
        assertNull(environment.getProperty("spring.datasource.password"));
    }

    @Test
    void decryptsValuesFromOriginTrackedYamlSource() {
        // 本地 application.yml 经 yaml 装载后值是 OriginTrackedValue 包装，解析链路须兼容
        byte[] masterKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY,
                        Base64.getEncoder().encodeToString(masterKey));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "applicationConfig: [classpath:/application.yml]",
                Map.of("spring.datasource.password",
                        OriginTrackedValue.of(SecretCipher.encrypt(masterKey, "root")))));

        SecretPropertySourceScanner.decrypt(environment, true);

        assertEquals("root", environment.getProperty("spring.datasource.password"));
    }
}
