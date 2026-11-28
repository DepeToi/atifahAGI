package com.atifah.common.core.constant;

/**
 * JWT 声明与撤销 Key 的统一定义，避免公共模块各写字面量。
 *
 * @author DepeToi
 * @since 1.0.0
 */
public final class JwtConstants {

    /** 令牌用途声明名；与 JOSE Header 的 typ 无关。 */
    public static final String CLAIM_TYPE = "typ";

    /** 访问令牌用途。 */
    public static final String TOKEN_TYPE_ACCESS = "access";

    /** 刷新令牌用途。 */
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    /** 令牌黑名单 Key，参数为 jti，TTL 等于令牌剩余有效期。 */
    public static final String BLACKLIST_KEY = "auth:token:%s";

    private JwtConstants() {
    }
}
