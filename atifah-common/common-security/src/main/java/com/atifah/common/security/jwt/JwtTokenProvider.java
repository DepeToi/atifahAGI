package com.atifah.common.security.jwt;

import com.atifah.common.core.constant.JwtConstants;
import com.atifah.common.security.config.JwtProperties;
import com.atifah.common.security.config.JwtUsage;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * JWT 签发与校验。生产使用 RS256（签发方持私钥、网关只持公钥），本地可回退 HS256。
 *
 * @author DepeToi
 * @since 1.0.0
 */
public class JwtTokenProvider {

    private static final String ALG_HS256 = "HS256";

    private static final String ALG_RS256 = "RS256";

    private final JwtProperties properties;

    private final String algorithm;

    private final JwtUsage usage;

    private final SecretKey secretKey;

    private final PublicKey publicKey;

    private final PrivateKey privateKey;

    /**
     * 构造 JWT 处理器并按配置初始化签名/验签密钥。
     *
     * @param properties JWT 配置
     */
    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        this.algorithm = normalize(properties.getAlgorithm());
        this.usage = properties.getUsage() == null ? JwtUsage.BOTH : properties.getUsage();
        if (ALG_HS256.equals(algorithm)) {
            if (!StringUtils.hasText(properties.getSecret())) {
                throw new IllegalStateException(
                        "HS256 需要配置 atifah.security.jwt.secret，非 Provider 服务不得下发该密钥");
            }
            this.secretKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
            this.publicKey = null;
            this.privateKey = null;
        } else if (ALG_RS256.equals(algorithm)) {
            this.secretKey = null;
            boolean hasPublicKey = StringUtils.hasText(properties.getPublicKeyLocation());
            boolean hasPrivateKey = StringUtils.hasText(properties.getPrivateKeyLocation());
            validateRs256Usage(hasPublicKey, hasPrivateKey);
            this.publicKey = hasPublicKey ? readPublicKey(properties.getPublicKeyLocation()) : null;
            this.privateKey = hasPrivateKey ? readPrivateKey(properties.getPrivateKeyLocation()) : null;
        } else {
            throw new IllegalArgumentException("不支持的 JWT 算法: " + properties.getAlgorithm());
        }
    }

    /**
     * 校验 RS256 密钥与 Provider 用途一致，防止验签方误加载签发私钥。
     *
     * @param hasPublicKey 是否配置公钥
     * @param hasPrivateKey 是否配置私钥
     */
    private void validateRs256Usage(boolean hasPublicKey, boolean hasPrivateKey) {
        switch (usage) {
            case SIGNER -> {
                if (!hasPrivateKey) {
                    throw new IllegalStateException("JWT SIGNER 必须配置 atifah.security.jwt.private-key-location");
                }
            }
            case VERIFIER -> {
                if (hasPrivateKey) {
                    throw new IllegalStateException("JWT VERIFIER 禁止配置签名私钥");
                }
                if (!hasPublicKey) {
                    throw new IllegalStateException("JWT VERIFIER 必须配置 atifah.security.jwt.public-key-location");
                }
            }
            case BOTH -> {
                if (!hasPublicKey || !hasPrivateKey) {
                    throw new IllegalStateException("JWT BOTH 必须同时配置签发私钥与验签公钥");
                }
            }
            default -> throw new IllegalStateException("不支持的 JWT usage: " + usage);
        }
    }

    /**
     * 签发访问令牌，携带唯一 jti 与 typ=access。
     *
     * @param userId 用户 ID
     * @param username 用户名，可为空
     * @return JWT 令牌
     */
    public String generateToken(Long userId, String username) {
        if (usage == JwtUsage.VERIFIER) {
            throw new IllegalStateException("JWT VERIFIER 不允许签发令牌");
        }
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .claim(JwtConstants.CLAIM_TYPE, JwtConstants.TOKEN_TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getAccessTokenTtl())));
        if (StringUtils.hasText(username)) {
            builder.claim("username", username);
        }
        if (ALG_RS256.equals(algorithm)) {
            if (privateKey == null) {
                throw new IllegalStateException("RS256 签发需要配置 atifah.security.jwt.private-key-location");
            }
            return builder.signWith(privateKey, Jwts.SIG.RS256).compact();
        }
        return builder.signWith(secretKey, Jwts.SIG.HS256).compact();
    }

    /**
     * 解析并校验令牌：签名、签发者与 typ=access 必须同时满足。
     *
     * @param token JWT 令牌
     * @return 令牌声明
     */
    public Claims parseToken(String token) {
        if (usage == JwtUsage.SIGNER) {
            throw new IllegalStateException("JWT SIGNER 不允许验签令牌");
        }
        JwtParserBuilder parser = Jwts.parser()
                .requireIssuer(properties.getIssuer())
                .require(JwtConstants.CLAIM_TYPE, JwtConstants.TOKEN_TYPE_ACCESS);
        if (ALG_RS256.equals(algorithm)) {
            if (publicKey == null) {
                throw new IllegalStateException(
                        "RS256 验签需要配置 atifah.security.jwt.public-key-location");
            }
            parser.verifyWith(publicKey);
        } else {
            parser.verifyWith(secretKey);
        }
        return parser.build().parseSignedClaims(token).getPayload();
    }

    /**
     * 从令牌中提取用户 ID。
     *
     * @param token JWT 令牌
     * @return 用户 ID
     */
    public Long getUserId(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    /**
     * 读取 RSA 公钥。
     *
     * @param location 公钥位置
     * @return 公钥
     */
    private static PublicKey readPublicKey(String location) {
        if (!StringUtils.hasText(location)) {
            throw new IllegalStateException(
                    "RS256 验签需要配置 atifah.security.jwt.public-key-location");
        }
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decodePem(location)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("解析 JWT 公钥失败: " + location, ex);
        }
    }

    /**
     * 读取 RSA 私钥。
     *
     * @param location 私钥位置
     * @return 私钥
     */
    private static PrivateKey readPrivateKey(String location) {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(decodePem(location)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("解析 JWT 私钥失败: " + location, ex);
        }
    }

    /**
     * 读取 PEM 文件并做 Base64 解码。
     *
     * @param location 密钥位置
     * @return 解码后的密钥字节
     */
    private static byte[] decodePem(String location) {
        Resource resource = new DefaultResourceLoader().getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("JWT 密钥文件不存在: " + location);
        }
        try (InputStream input = resource.getInputStream()) {
            String pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
            String base64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                    .replaceAll("-----END [A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            return Base64.getDecoder().decode(base64);
        } catch (IOException ex) {
            throw new IllegalStateException("读取 JWT 密钥失败: " + location, ex);
        }
    }

    /**
     * 归一化算法名。
     *
     * @param value 原始值
     * @return 大写去空白的算法名
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
