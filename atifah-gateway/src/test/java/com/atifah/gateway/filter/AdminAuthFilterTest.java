package com.atifah.gateway.filter;

import com.atifah.common.core.constant.CommonConstants;
import com.atifah.common.security.config.JwtProperties;
import com.atifah.common.security.jwt.JwtTokenProvider;
import com.atifah.gateway.config.AuthRule;
import com.atifah.gateway.config.GatewayAdminAuthProperties;
import com.atifah.gateway.support.GatewayErrorWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 管理端鉴权链测试：独立密钥、身份头与 C 端令牌隔离。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class AdminAuthFilterTest {

    private static final String ADMIN_SECRET = "admin-secret-012345678901234567890";
    private static final String ADMIN_ISSUER = "atifah-admin";

    private final GatewayErrorWriter errorWriter = new GatewayErrorWriter(new ObjectMapper());

    @Test
    void rejectsMissingToken() {
        AdminAuthFilter filter = filter(rules());
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/admin/api/users"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void acceptsAdminTokenAndInjectsAdminId() {
        AdminAuthFilter filter = filter(rules());
        String token = adminProvider().generateToken(9L, null);
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/admin/api/users")
                .header("Authorization", "Bearer " + token));

        filter.filter(exchange, ex -> {
            captured.set(ex);
            return Mono.empty();
        }).block();

        assertNotNull(captured.get());
        assertEquals("9", captured.get().getRequest().getHeaders()
                .getFirst(CommonConstants.HEADER_ADMIN_ID));
    }

    @Test
    void rejectsCUserToken() {
        AdminAuthFilter filter = filter(rules());
        JwtProperties cEnd = new JwtProperties();
        cEnd.setSecret("01234567890123456789012345678901");
        String token = new JwtTokenProvider(cEnd).generateToken(123L, null);

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/admin/api/users")
                .header("Authorization", "Bearer " + token));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void passesAdminWhitelistWithoutToken() {
        AdminAuthFilter filter = filter(rules());
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/admin/api/auth/login"));

        filter.filter(exchange, ex -> {
            captured.set(ex);
            return Mono.empty();
        }).block();

        assertNotNull(captured.get());
    }

    private AdminAuthFilter filter(List<AuthRule> rules) {
        GatewayAdminAuthProperties properties = new GatewayAdminAuthProperties();
        properties.setSecret(ADMIN_SECRET);
        properties.setIssuer(ADMIN_ISSUER);
        properties.setRules(rules);
        return new AdminAuthFilter(properties, errorWriter);
    }

    private JwtTokenProvider adminProvider() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(ADMIN_SECRET);
        properties.setIssuer(ADMIN_ISSUER);
        return new JwtTokenProvider(properties);
    }

    private List<AuthRule> rules() {
        AuthRule rule = new AuthRule();
        rule.setPath("/admin/api/auth/**");
        rule.setMethods(List.of("POST"));
        return List.of(rule);
    }

    private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> requestBuilder) {
        return MockServerWebExchange.from(requestBuilder.build());
    }
}
