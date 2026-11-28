package com.atifah.gateway.support;

import com.atifah.common.core.enums.ErrorCode;
import com.atifah.common.core.result.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关统一错误响应出口：按 {@link Result} 结构写回，禁止过滤器内手写 JSON。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Component
@RequiredArgsConstructor
public class GatewayErrorWriter {

    /** 统一 JSON 响应类型，显式声明 UTF-8。 */
    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    private final ObjectMapper objectMapper;

    /**
     * 写出统一错误响应；响应已提交时不重复写入。
     *
     * @param exchange 请求交换上下文
     * @param status HTTP 状态
     * @param errorCode 统一错误码
     * @return 响应流
     */
    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(JSON_UTF8);
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            // 鉴权失败响应可能包含用户相关语义，禁止中间层缓存
            response.getHeaders().setCacheControl("no-store");
        }
        DataBuffer buffer = response.bufferFactory().wrap(serialize(errorCode));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 构建统一错误响应，供 Sentinel 阻塞处理器按 {@code ServerResponse} 返回。
     *
     * @param status HTTP 状态
     * @param errorCode 统一错误码
     * @return 响应对象流
     */
    public Mono<ServerResponse> response(HttpStatus status, ErrorCode errorCode) {
        return ServerResponse.status(status)
                .contentType(JSON_UTF8)
                .body(BodyInserters.fromValue(serialize(errorCode)));
    }

    /**
     * 序列化错误结果；序列化失败时退化为固定结构，避免网关自身不可用。
     *
     * @param errorCode 统一错误码
     * @return JSON 字节
     */
    private byte[] serialize(ErrorCode errorCode) {
        try {
            return objectMapper.writeValueAsBytes(Result.fail(errorCode));
        } catch (JsonProcessingException ex) {
            String fallback = "{\"code\":" + errorCode.getCode()
                    + ",\"message\":\"" + errorCode.getMessage()
                    + "\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() + "}";
            return fallback.getBytes(StandardCharsets.UTF_8);
        }
    }
}
