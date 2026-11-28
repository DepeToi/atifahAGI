package com.atifah.gateway.filter;

import com.atifah.common.core.constant.CommonConstants;
import com.atifah.common.security.config.JwtProperties;
import com.atifah.common.security.jwt.JwtTokenProvider;
import com.atifah.gateway.auth.AuthRuleMatcher;
import com.atifah.gateway.config.GatewayAdminAuthProperties;
import com.atifah.gateway.support.GatewayErrorWriter;
import io.jsonwebtoken.Claims;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 管理端鉴权链：只处理 {@code /admin/api/**}，使用独立密钥且不共用 C 端白名单。
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
public class AdminAuthFilter extends AbstractAuthFilter {

    /** 管理端路径前缀。 */
    private static final String PREFIX = "/admin/api";

    private static final String CHAIN = "admin";

    private final JwtTokenProvider jwtTokenProvider;
    private final AuthRuleMatcher ruleMatcher;

    /**
     * 构造管理端鉴权过滤器。
     *
     * @param adminProperties 管理端鉴权配置
     * @param errorWriter 统一错误出口
     */
    public AdminAuthFilter(GatewayAdminAuthProperties adminProperties, GatewayErrorWriter errorWriter) {
        super(errorWriter);
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret(adminProperties.getSecret());
        jwtProperties.setIssuer(adminProperties.getIssuer());
        this.jwtTokenProvider = new JwtTokenProvider(jwtProperties);
        this.ruleMatcher = new AuthRuleMatcher(adminProperties.getRules());
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
            String adminId = claims.getSubject();
            if (!isValidId(adminId)) {
                return unauthorized(exchange, CHAIN, "invalid_token");
            }
            ServerWebExchange mutated = injectIdentity(exchange, CommonConstants.HEADER_ADMIN_ID, adminId, null);
            return chain.filter(mutated);
        } catch (Exception ex) {
            return unauthorized(exchange, CHAIN, "invalid_token");
        }
    }
}
