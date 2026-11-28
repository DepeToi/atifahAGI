package com.atifah.gateway.filter;

import com.atifah.common.core.constant.CommonConstants;
import com.atifah.common.security.jwt.JwtTokenProvider;
import com.atifah.gateway.auth.TokenBlacklistChecker;
import com.atifah.gateway.config.GatewayAuthProperties;
import com.atifah.gateway.support.GatewayErrorWriter;
import io.jsonwebtoken.Claims;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * WebSocket 握手鉴权链：只处理 {@code /ws/**}，从子协议中提取并校验 JWT。
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
public class WebSocketAuthFilter extends AbstractAuthFilter {

    /** 网关路径前缀。 */
    private static final String PREFIX = "/ws";

    /** 子协议请求头。 */
    private static final String HEADER_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";

    /** 子协议中的令牌前缀。 */
    private static final String TOKEN_PROTOCOL_PREFIX = "atifah.token.";

    private static final String CHAIN = "websocket";

    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 构造 WebSocket 鉴权过滤器。
     *
     * @param jwtTokenProvider C 端 JWT 处理器
     * @param authProperties C 端鉴权配置
     * @param errorWriter 统一错误出口
     * @param blacklistChecker jti 黑名单查询
     */
    public WebSocketAuthFilter(JwtTokenProvider jwtTokenProvider, GatewayAuthProperties authProperties,
                               GatewayErrorWriter errorWriter, TokenBlacklistChecker blacklistChecker) {
        super(errorWriter, blacklistChecker, authProperties.getBlacklist().isEnabled());
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!pathUnder(path, PREFIX)) {
            return chain.filter(exchange);
        }

        String token = extractToken(
                exchange.getRequest().getHeaders().getFirst(HEADER_WEBSOCKET_PROTOCOL));
        if (token == null) {
            return unauthorized(exchange, CHAIN, "missing_token");
        }

        try {
            Claims claims = jwtTokenProvider.parseToken(token);
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

    /**
     * 从逗号分隔的子协议列表中提取网关令牌，禁止使用 URL query。
     *
     * @param header 子协议请求头
     * @return 令牌或 null
     */
    private String extractToken(String header) {
        if (header == null) {
            return null;
        }
        for (String protocol : header.split(",")) {
            String trimmed = protocol.trim();
            if (trimmed.startsWith(TOKEN_PROTOCOL_PREFIX) && trimmed.length() > TOKEN_PROTOCOL_PREFIX.length()) {
                return trimmed.substring(TOKEN_PROTOCOL_PREFIX.length());
            }
        }
        return null;
    }
}
