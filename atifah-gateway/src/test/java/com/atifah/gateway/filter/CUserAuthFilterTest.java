package com.atifah.gateway.filter;

import com.atifah.common.core.constant.CommonConstants;
import com.atifah.common.core.enums.ErrorCode;
import com.atifah.common.security.config.JwtProperties;
import com.atifah.common.security.jwt.JwtTokenProvider;
import com.atifah.gateway.auth.TokenBlacklistChecker;
import com.atifah.gateway.auth.TokenBlacklistUnavailableException;
import com.atifah.gateway.config.AuthRule;
import com.atifah.gateway.config.GatewayAuthProperties;
import com.atifah.gateway.support.GatewayErrorWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C 端鉴权链测试：放行、拒绝、白名单、黑名单与缺失 jti。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class CUserAuthFilterTest {

    private static final String SECRET = "01234567890123456789012345678901";

    private final GatewayErrorWriter errorWriter = new GatewayErrorWriter(new ObjectMapper());

    @Test
    void rejectsMissingToken() {
        CUserAuthFilter filter = filter(blacklist(false));

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/v1/moods"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertTrue(body(exchange).contains(String.valueOf(ErrorCode.UNAUTHORIZED.getCode())));
    }

    @Test
    void passesWhitelistedPost() {
        CUserAuthFilter filter = filter(blacklist(false));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/v1/auth/login"));

        filter.filter(exchange, ex -> {
            captured.set(ex);
            return Mono.empty();
        }).block();

        assertNotNull(captured.get());
    }

    @Test
    void rejectsWhitelistedPathWithWrongMethod() {
        CUserAuthFilter filter = filter(blacklist(false));

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/v1/auth/login"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void injectsIdentityForValidToken() {
        CUserAuthFilter filter = filter(blacklist(false));
        String token = provider().generateToken(123L, "138****5678");
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/v1/moods")
                .header("Authorization", "Bearer " + token)
                .header(CommonConstants.HEADER_USER_ID, "spoofed"));

        filter.filter(exchange, ex -> {
            captured.set(ex);
            return Mono.empty();
        }).block();

        assertNotNull(captured.get());
        assertEquals("123", captured.get().getRequest().getHeaders()
                .getFirst(CommonConstants.HEADER_USER_ID));
        assertEquals("138****5678", captured.get().getRequest().getHeaders()
                .getFirst(CommonConstants.HEADER_USERNAME));
    }

    @Test
    void rejectsRevokedToken() {
        CUserAuthFilter filter = filter(blacklist(true));
        String token = provider().generateToken(123L, "138****5678");

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/v1/moods")
                .header("Authorization", "Bearer " + token));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void returnsServiceUnavailableWhenBlacklistFailsClosed() {
        TokenBlacklistChecker failing = jti -> Mono.error(
                new TokenBlacklistUnavailableException("redis down", new IllegalStateException("down")));
        CUserAuthFilter filter = filter(failing);
        String token = provider().generateToken(123L, "138****5678");

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/v1/moods")
                .header("Authorization", "Bearer " + token));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertTrue(body(exchange).contains(String.valueOf(ErrorCode.SERVICE_UNAVAILABLE.getCode())));
    }

    @Test
    void rejectsTokenWithoutJtiWhenBlacklistEnabled() {
        JwtTokenProvider provider = mock(JwtTokenProvider.class);
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("123");
        when(claims.get("username")).thenReturn(null);
        when(claims.getId()).thenReturn(null);
        when(provider.parseToken("no-jti-token")).thenReturn(claims);

        CUserAuthFilter filter = new CUserAuthFilter(provider, authProperties(), errorWriter,
                blacklist(false));

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/api/v1/moods")
                .header("Authorization", "Bearer no-jti-token"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    private CUserAuthFilter filter(TokenBlacklistChecker blacklistChecker) {
        return new CUserAuthFilter(provider(), authProperties(), errorWriter, blacklistChecker);
    }

    private JwtTokenProvider provider() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        return new JwtTokenProvider(properties);
    }

    private GatewayAuthProperties authProperties() {
        AuthRule rule = new AuthRule();
        rule.setPath("/api/v1/auth/**");
        rule.setMethods(List.of("POST"));
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setRules(List.of(rule));
        return properties;
    }

    private TokenBlacklistChecker blacklist(boolean revoked) {
        return jti -> Mono.just(revoked);
    }

    private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> requestBuilder) {
        return MockServerWebExchange.from(requestBuilder.build());
    }

    private String body(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block();
    }
}
