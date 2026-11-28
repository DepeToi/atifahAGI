package com.atifah.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关管理端鉴权配置。管理端使用独立密钥与独立白名单，不复用 C 端 JWT。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "atifah.gateway.admin-auth")
public class GatewayAdminAuthProperties {

    /** 本地开发占位密钥；生产环境必须替换，禁止沿用该值。 */
    public static final String DEFAULT_SECRET = "atifah-admin-dev-secret-change-me-32bytes";

    /** 管理端签名密钥，以 ENC() 密文入库，生产由 Nacos 网关私有配置下发真实密钥密文覆盖。 */
    private String secret = DEFAULT_SECRET;

    /** 管理端令牌签发者。 */
    private String issuer = "atifah-admin";

    /** 管理端免鉴权规则，仅放行登录等必要接口；为空表示拒绝所有。 */
    private List<AuthRule> rules = new ArrayList<>();
}
