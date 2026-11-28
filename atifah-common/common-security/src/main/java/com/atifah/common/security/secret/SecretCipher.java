package com.atifah.common.security.secret;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 配置密钥加解密实现：AES-256-GCM。
 *
 * <p>密文统一为 {@code ENC(Base64(version ‖ nonce ‖ ciphertext+tag))} 形态，直接写入
 * application*.yml 或 Nacos 配置；运行时由 {@link SecretPropertySourceScanner} 解密回明文，
 * 所有下游组件拿到的仍是明文值。密文格式约定：</p>
 * <ul>
 *     <li>version：1 字节版本号，当前为 1，预留给未来更换算法时做平滑迁移；</li>
 *     <li>nonce：12 字节随机数，同一段明文每次加密产生不同密文，防止通过密文比对配置值；</li>
 *     <li>ciphertext+tag：AES-GCM 密文与 128 位认证标签。</li>
 * </ul>
 *
 * <p>主密钥为 32 字节 Base64 字符串，随配置单一来源管理：Nacos 公共配置优先、各服务本地
 * application.yml 兜底；仓库与开发命名空间只出现开发哑密钥（只保护仓库内哑密文），
 * 生产主密钥仅在 Nacos 生产命名空间下发，不经启动参数注入。</p>
 *
 * @author DepeToi
 * @since 1.0.0
 */
public final class SecretCipher {

    /** 密文包裹前缀。 */
    public static final String ENC_PREFIX = "ENC(";

    /** 密文包裹后缀。 */
    public static final String ENC_SUFFIX = ")";

    /** 当前密文格式版本号。 */
    private static final byte VERSION = 1;

    /** GCM nonce 长度（字节）。 */
    private static final int NONCE_BYTES = 12;

    /** GCM 认证标签长度（位）。 */
    private static final int GCM_TAG_BITS = 128;

    /** 主密钥长度（字节），即 AES-256。 */
    private static final int KEY_BYTES = 32;

    /** 密文最小载荷长度：version(1) + nonce(12) + 认证标签(16)。 */
    private static final int MIN_PAYLOAD_BYTES = 1 + NONCE_BYTES + 16;

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    private SecretCipher() {
    }

    /**
     * 生成随机主密钥。
     *
     * @return Base64 编码的 32 字节主密钥
     */
    public static String generateMasterKey() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * 解析 Base64 主密钥。
     *
     * @param masterKeyBase64 Base64 主密钥
     * @return 32 字节密钥
     * @throws IllegalArgumentException Base64 非法或长度不是 32 字节
     */
    public static byte[] parseMasterKey(String masterKeyBase64) {
        byte[] key = Base64.getDecoder().decode(masterKeyBase64 == null ? "" : masterKeyBase64.trim());
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("主密钥必须是 32 字节的 Base64 字符串"
                    + "（openssl rand -base64 32 生成），当前解码后 " + key.length + " 字节");
        }
        return key;
    }

    /**
     * 加密并包裹为 ENC() 形态。
     *
     * @param masterKey 32 字节主密钥
     * @param plaintext 明文
     * @return ENC(Base64(...)) 密文
     */
    public static String encrypt(byte[] masterKey, String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[1 + nonce.length + ciphertext.length];
            payload[0] = VERSION;
            System.arraycopy(nonce, 0, payload, 1, nonce.length);
            System.arraycopy(ciphertext, 0, payload, 1 + nonce.length, ciphertext.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(payload) + ENC_SUFFIX;
        } catch (Exception e) {
            throw new IllegalStateException("配置密钥加密失败", e);
        }
    }

    /**
     * 解密 ENC() 形态密文。
     *
     * @param masterKey 32 字节主密钥
     * @param wrapped ENC(...) 密文
     * @return 明文
     * @throws IllegalStateException 密文结构非法、主密钥不匹配或密文损坏
     */
    public static String decrypt(byte[] masterKey, String wrapped) {
        byte[] payload = unwrap(wrapped);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, payload, 1, NONCE_BYTES));
            byte[] plaintext = cipher.doFinal(payload, 1 + NONCE_BYTES, payload.length - 1 - NONCE_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("配置密钥解密失败：主密钥不匹配或密文损坏"
                    + "（轮换主密钥后请用 SecretTool 重新生成全部 ENC() 值）", e);
        }
    }

    /**
     * 判断值是否为合法的 ENC() 密文形态。
     *
     * <p>只校验结构（包裹、Base64、版本号、长度），不校验密钥正确性；结构不合法的值一律
     * 视为普通明文，保证"解密后再扫描"的幂等性。</p>
     *
     * @param value 待检查的配置值
     * @return 是否为当前版本可解密的密文
     */
    public static boolean isEncryptedValue(String value) {
        return tryUnwrap(value) != null;
    }

    private static byte[] tryUnwrap(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (!text.startsWith(ENC_PREFIX) || !text.endsWith(ENC_SUFFIX)) {
            return null;
        }
        String base64 = text.substring(ENC_PREFIX.length(), text.length() - ENC_SUFFIX.length()).trim();
        if (base64.isEmpty()) {
            return null;
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (payload.length < MIN_PAYLOAD_BYTES || payload[0] != VERSION) {
            return null;
        }
        return payload;
    }

    private static byte[] unwrap(String wrapped) {
        byte[] payload = tryUnwrap(wrapped);
        if (payload == null) {
            throw new IllegalStateException("配置值不是合法的 ENC() 密文，无法解密");
        }
        return payload;
    }
}
