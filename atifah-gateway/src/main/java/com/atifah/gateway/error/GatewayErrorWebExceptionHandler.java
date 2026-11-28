package com.atifah.gateway.error;

import com.atifah.common.core.constant.CommonConstants;
import com.atifah.common.core.enums.ErrorCode;
import com.atifah.gateway.constant.GatewayConstants;
import com.atifah.gateway.support.GatewayBlockErrors;
import com.atifah.gateway.support.GatewayErrorWriter;
import com.atifah.gateway.support.RequestSizeExceededException;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/**
 * 网关全局错误出口：把路由、下游、限流等异常统一转换为 {@code Result}。
 * 响应式栈没有 servlet 的全局异常处理，故实现 {@link ErrorWebExceptionHandler}。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private final GatewayErrorWriter errorWriter;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        GatewayError error = resolve(ex);
        logFailure(exchange, error, ex);
        return errorWriter.write(exchange, error.status(), error.code());
    }

    /**
     * 将异常映射为 HTTP 状态与统一错误码；未知异常按 500 处理。
     *
     * @param ex 异常
     * @return 错误映射
     */
    private GatewayError resolve(Throwable ex) {
        if (BlockException.isBlockException(ex)) {
            return fromBlock(ex);
        }
        if (containsRequestSizeExceeded(ex)) {
            return new GatewayError(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE);
        }
        if (ex instanceof ResponseStatusException statusException) {
            return fromStatus(statusException.getStatusCode());
        }
        if (isUpstreamFailure(ex)) {
            return new GatewayError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE);
        }
        return new GatewayError(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
    }

    /**
     * 将 Sentinel 阻塞异常映射为统一错误：熔断/系统保护按 503，其余按 429。
     * 与自定义 {@code BlockRequestHandler} 保持一致，避免处理链顺序差异导致响应不同。
     *
     * @param ex 阻塞异常，可能被包装
     * @return 错误映射
     */
    private GatewayError fromBlock(Throwable ex) {
        if (GatewayBlockErrors.isDegrade(ex)) {
            return new GatewayError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE);
        }
        return new GatewayError(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_REQUESTS);
    }

    /**
     * 按 HTTP 状态映射统一错误码。
     *
     * @param statusCode HTTP 状态
     * @return 错误映射
     */
    private GatewayError fromStatus(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 400 -> new GatewayError(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST);
            case 401 -> new GatewayError(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
            case 403 -> new GatewayError(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN);
            case 404 -> new GatewayError(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
            case 405 -> new GatewayError(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED);
            case 413 -> new GatewayError(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE);
            case 429 -> new GatewayError(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_REQUESTS);
            case 502, 503, 504 -> new GatewayError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE);
            default -> statusCode.is4xxClientError()
                    ? new GatewayError(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST)
                    : new GatewayError(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
        };
    }

    /**
     * 判断异常链中是否包含请求体超限异常；流式读取时异常可能被上游包装。
     *
     * @param ex 异常
     * @return 是否请求体超限
     */
    private boolean containsRequestSizeExceeded(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof RequestSizeExceededException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /**
     * 判断是否为下游不可用：连接失败、连接/读取超时、下游提前关闭。
     *
     * @param ex 异常
     * @return 是否下游不可用
     */
    private boolean isUpstreamFailure(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException
                    || current instanceof ConnectException
                    || current instanceof io.netty.channel.ConnectTimeoutException
                    || current instanceof io.netty.handler.timeout.ReadTimeoutException
                    || current instanceof PrematureCloseException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /**
     * 记录网关错误；4xx 不带堆栈，5xx 保留一次堆栈，且不记录请求头。
     *
     * @param exchange 请求交换上下文
     * @param error 错误映射
     * @param ex 原始异常
     */
    private void logFailure(ServerWebExchange exchange, GatewayError error, Throwable ex) {
        String traceId = exchange.getAttribute(GatewayConstants.ATTR_TRACE_ID);
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod() == null
                ? "-"
                : exchange.getRequest().getMethod().name();
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CommonConstants.MDC_TRACE_ID,
                traceId == null ? "-" : traceId)) {
            if (error.status().is5xxServerError()) {
                log.error("event=gateway_error method={} path={} status={} code={}",
                        method, path, error.status().value(), error.code().getCode(), ex);
            } else {
                log.warn("event=gateway_error method={} path={} status={} code={}",
                        method, path, error.status().value(), error.code().getCode());
            }
        }
    }

    /**
     * 异常到统一响应的映射。
     *
     * @param status HTTP 状态
     * @param code 统一错误码
     */
    private record GatewayError(HttpStatus status, ErrorCode code) {
    }
}
