package com.atifah.gateway.filter;

import com.atifah.common.core.constant.CommonConstants;
import com.atifah.common.security.jwt.JwtTokenProvider;
import com.atifah.gateway.auth.AuthRuleMatcher;
import com.atifah.gateway.auth.TokenBlacklistChecker;
import com.atifah.gateway.config.GatewayAuthProperties;
import com.atifah.gateway.support.GatewayErrorWriter;
import io.jsonwebtoken.Claims;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * C 端鉴权链：只处理 {@code /api/v1/**}，白名单命中或 JWT 校验通过后注入用户身份。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Component
@ConditionalOnProperty(
        prefix = "atifah.gateway.auth",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CUserAuthFilter extends AbstractAuthFilter {

    /** C 端路径前缀。 */
    private static final String PREFIX = "/api/v1";

    private static final String CHAIN = "c-end";

    private final JwtTokenProvider jwtTokenProvider;
    private final AuthRuleMatcher ruleMatcher;

    /**
     * 构造 C 端鉴权过滤器。
     *
     * @param jwtTokenProvider C 端 JWT 处理器
     * @param authProperties C 端鉴权配置
     * @param errorWriter 统一错误出口
     * @param blacklistChecker jti 黑名单查询
     */
    public CUserAuthFilter(JwtTokenProvider jwtTokenProvider, GatewayAuthProperties authProperties,
                           GatewayErrorWriter errorWriter, TokenBlacklistChecker blacklistChecker) {
        super(errorWriter, blacklistChecker, authProperties.getBlacklist().isEnabled());
        this.jwtTokenProvider = jwtTokenProvider;
        this.ruleMatcher = new AuthRuleMatcher(authProperties.getRules());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!pathUnder(path, PREFIX)) {
            return chain.filter(exchange);
        }
        if (ruleMatcher.isWhitelisted(path, exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange, CHAIN, "missing_token");
        }

        try {
            Claims claims = jwtTokenProvider.parseToken(authorization.substring(BEARER_PREFIX.length()));
            String userId = claims.getSubject();
            if (!isValidId(userId)) {
                return unauthorized(exchange, CHAIN, "invalid_token");
            }
            String username = claimText(claims.get("username"));
            ServerWebExchange mutated = injectIdentity(exchange, CommonConstants.HEADER_USER_ID, userId, username);
            return chainAfterRevocationCheck(mutated, chain, CHAIN, claims.getId());
        } catch (Exception ex) {
            return unauthorized(exchange, CHAIN, "invalid_token");
        }
    }
}
