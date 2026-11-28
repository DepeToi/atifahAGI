package com.atifah.gateway.error;

import com.atifah.common.core.enums.ErrorCode;
import com.atifah.gateway.support.GatewayErrorWriter;
import com.atifah.gateway.support.RequestSizeExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关统一错误出口测试：413、503、500 与统一 Result。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class GatewayErrorWebExceptionHandlerTest {

    private final GatewayErrorWebExceptionHandler handler =
            new GatewayErrorWebExceptionHandler(new GatewayErrorWriter(new ObjectMapper()));

    @Test
    void mapsRequestSizeExceededToPayloadTooLarge() {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new RequestSizeExceededException(10)).block();

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
        assertTrue(body(exchange).contains(String.valueOf(ErrorCode.PAYLOAD_TOO_LARGE.getCode())));
    }

    @Test
    void mapsUpstreamFailureToServiceUnavailable() {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new ConnectException("down")).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertTrue(body(exchange).contains(String.valueOf(ErrorCode.SERVICE_UNAVAILABLE.getCode())));
    }

    @Test
    void mapsUnknownFailureToInternalError() {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new IllegalStateException("boom")).block();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        assertTrue(body(exchange).contains(String.valueOf(ErrorCode.INTERNAL_ERROR.getCode())));
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/moods").build());
    }

    private String body(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block();
    }
}
