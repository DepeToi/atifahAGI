package com.atifah.gateway.filter;

import com.atifah.common.core.constant.CommonConstants;
import com.atifah.common.security.config.JwtProperties;
import com.atifah.common.security.jwt.JwtTokenProvider;
import com.atifah.gateway.auth.TokenBlacklistChecker;
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

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WebSocket 鉴权链测试：子协议令牌、禁止 query 令牌与缺失 jti。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class WebSocketAuthFilterTest {

    private static final String SECRET = "01234567890123456789012345678901";

    private final GatewayErrorWriter errorWriter = new GatewayErrorWriter(new ObjectMapper());

    @Test
    void acceptsTokenFromSubProtocol() {
        WebSocketAuthFilter filter = filter(provider());
        String token = provider().generateToken(123L, "138****5678");
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/ws/notify")
                .header("Sec-WebSocket-Protocol", "atifah.token." + token));

        filter.filter(exchange, ex -> {
            captured.set(ex);
            return Mono.empty();
        }).block();

        assertNotNull(captured.get());
        assertEquals("123", captured.get().getRequest().getHeaders()
                .getFirst(CommonConstants.HEADER_USER_ID));
    }

    @Test
    void rejectsQueryToken() {
        WebSocketAuthFilter filter = filter(provider());
        String token = provider().generateToken(123L, null);

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/ws/notify?token=" + token));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsMissingToken() {
        WebSocketAuthFilter filter = filter(provider());

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/ws/notify"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsTokenWithoutJtiWhenBlacklistEnabled() {
        JwtTokenProvider provider = mock(JwtTokenProvider.class);
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("123");
        when(claims.get("username")).thenReturn(null);
        when(claims.getId()).thenReturn(null);
        when(provider.parseToken("no-jti-token")).thenReturn(claims);

        WebSocketAuthFilter filter = filter(provider);

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/ws/notify")
                .header("Sec-WebSocket-Protocol", "atifah.token.no-jti-token"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    private WebSocketAuthFilter filter(JwtTokenProvider provider) {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        TokenBlacklistChecker blacklist = jti -> Mono.just(false);
        return new WebSocketAuthFilter(provider, properties, errorWriter, blacklist);
    }

    private JwtTokenProvider provider() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        return new JwtTokenProvider(properties);
    }

    private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> requestBuilder) {
        return MockServerWebExchange.from(requestBuilder.build());
    }
}
