package com.atifah.common.security.secret;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置密钥加解密测试：ENC() 往返、密文随机性、错误主密钥拒绝与非法结构识别。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class SecretCipherTest {

    @Test
    void roundTripRecoversPlaintext() {
        byte[] masterKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        String wrapped = SecretCipher.encrypt(masterKey, "p@ssw0rd-中文");

        assertTrue(SecretCipher.isEncryptedValue(wrapped));
        assertEquals("p@ssw0rd-中文", SecretCipher.decrypt(masterKey, wrapped));
    }

    @Test
    void samePlaintextProducesDifferentCiphertext() {
        byte[] masterKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());

        assertNotEquals(SecretCipher.encrypt(masterKey, "root"), SecretCipher.encrypt(masterKey, "root"));
    }

    @Test
    void wrongMasterKeyIsRejected() {
        byte[] masterKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        byte[] otherKey = SecretCipher.parseMasterKey(SecretCipher.generateMasterKey());
        String wrapped = SecretCipher.encrypt(masterKey, "root");

        assertThrows(IllegalStateException.class, () -> SecretCipher.decrypt(otherKey, wrapped));
    }

    @Test
    void invalidMasterKeyLengthIsRejected() {
        String shortKey = java.util.Base64.getEncoder().encodeToString(new byte[16]);

        assertThrows(IllegalArgumentException.class, () -> SecretCipher.parseMasterKey(shortKey));
    }

    @Test
    void malformedValuesAreNotTreatedAsEncrypted() {
        assertFalse(SecretCipher.isEncryptedValue(null));
        assertFalse(SecretCipher.isEncryptedValue(""));
        assertFalse(SecretCipher.isEncryptedValue("root"));
        assertFalse(SecretCipher.isEncryptedValue("ENC()"));
        assertFalse(SecretCipher.isEncryptedValue("ENC(not-base64!)"));
        // version 字节非 1 的载荷视为未知格式（明文），由明文守卫兜底
        String futureVersion = "ENC(" + java.util.Base64.getEncoder().encodeToString(new byte[64]) + ")";
        assertFalse(SecretCipher.isEncryptedValue(futureVersion));
    }
}
