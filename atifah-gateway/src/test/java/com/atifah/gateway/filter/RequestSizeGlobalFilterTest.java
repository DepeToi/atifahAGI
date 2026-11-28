package com.atifah.gateway.filter;

import com.atifah.common.core.enums.ErrorCode;
import com.atifah.gateway.config.GatewayRequestSizeProperties;
import com.atifah.gateway.support.GatewayErrorWriter;
import com.atifah.gateway.support.RequestSizeExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求体大小门禁测试：已知长度超限与未超限放行。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class RequestSizeGlobalFilterTest {

    private final GatewayErrorWriter errorWriter = new GatewayErrorWriter(new ObjectMapper());

    @Test
    void rejectsDeclaredBodyOverLimit() {
        RequestSizeGlobalFilter filter = filter(10);

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/v1/moods")
                .header("Content-Length", "11"));

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
        assertTrue(body(exchange).contains(String.valueOf(ErrorCode.PAYLOAD_TOO_LARGE.getCode())));
    }

    @Test
    void passesDeclaredBodyUnderLimit() {
        RequestSizeGlobalFilter filter = filter(10);
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/v1/moods")
                .header("Content-Length", "5"));

        filter.filter(exchange, ex -> {
            captured.set(ex);
            return Mono.empty();
        }).block();

        assertNotNull(captured.get());
    }

    @Test
    void rejectsChunkedBodyOverLimit() {
        RequestSizeGlobalFilter filter = filter(10);
        DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/moods")
                        .body(Flux.just(buffer(bufferFactory, "123456"), buffer(bufferFactory, "789012"))));
        AtomicLong received = new AtomicLong();

        Mono<Void> result = filter.filter(exchange, ex -> ex.getRequest().getBody()
                .doOnNext(dataBuffer -> {
                    received.addAndGet(dataBuffer.readableByteCount());
                    DataBufferUtils.release(dataBuffer);
                })
                .then());

        RequestSizeExceededException exception =
                assertThrows(RequestSizeExceededException.class, result::block);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exception.getStatusCode());
        assertEquals(6, received.get());
    }

    @Test
    void passesChunkedBodyUnderLimit() {
        RequestSizeGlobalFilter filter = filter(10);
        DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/moods")
                        .body(Flux.just(buffer(bufferFactory, "1234"), buffer(bufferFactory, "5678"))));
        AtomicLong received = new AtomicLong();

        filter.filter(exchange, ex -> ex.getRequest().getBody()
                .doOnNext(dataBuffer -> {
                    received.addAndGet(dataBuffer.readableByteCount());
                    DataBufferUtils.release(dataBuffer);
                })
                .then()).block();

        assertEquals(8, received.get());
    }

    private DataBuffer buffer(DefaultDataBufferFactory factory, String body) {
        return factory.wrap(body.getBytes(StandardCharsets.UTF_8));
    }

    private RequestSizeGlobalFilter filter(long maxBytes) {
        GatewayRequestSizeProperties properties = new GatewayRequestSizeProperties();
        properties.setMaxBodySize(DataSize.ofBytes(maxBytes));
        return new RequestSizeGlobalFilter(properties, errorWriter);
    }

    private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> requestBuilder) {
        return MockServerWebExchange.from(requestBuilder.build());
    }

    private String body(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block();
    }
}
