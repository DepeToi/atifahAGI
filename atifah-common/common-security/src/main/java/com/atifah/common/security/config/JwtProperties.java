package com.atifah.common.security.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 配置。本地默认 HS256；生产必须切换 RS256，签发方加载私钥，网关只加载公钥。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "atifah.security.jwt")
public class JwtProperties {

    /** 签名算法：HS256（本地/测试）或 RS256（生产）。 */
    private String algorithm = "HS256";

    /** Provider 密钥用途；网关使用 VERIFIER，签发服务使用 SIGNER。 */
    private JwtUsage usage = JwtUsage.BOTH;

    /**
     * 是否装配 JWT Provider。
     * 仅网关、认证等实际签发或验签的服务启用，避免业务服务加载 JWT 密钥。
     */
    private boolean providerEnabled;

    /** HS256 对称密钥；仅在 local/dev/test 且显式启用 Provider 的服务中配置。 */
    private String secret;

    /** RSA 私钥位置（仅签发方），支持 file:/ 或 classpath: 前缀；网关不配置。 */
    private String privateKeyLocation;

    /** RSA 公钥位置（验签方必填），支持 file:/ 或 classpath: 前缀。 */
    private String publicKeyLocation;

    /** 令牌签发者。 */
    private String issuer = "atifah";

    /** 访问令牌有效期；短 TTL 降低令牌泄露后的影响面。 */
    private Duration accessTokenTtl = Duration.ofMinutes(15);
}
