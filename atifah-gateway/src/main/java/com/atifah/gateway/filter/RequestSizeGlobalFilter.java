package com.atifah.gateway.filter;

import com.atifah.common.core.constant.CommonConstants;
import com.atifah.common.core.enums.ErrorCode;
import com.atifah.gateway.config.GatewayRequestSizeProperties;
import com.atifah.gateway.constant.GatewayConstants;
import com.atifah.gateway.support.GatewayErrorWriter;
import com.atifah.gateway.support.RequestSizeExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求体大小限制：已知 {@code Content-Length} 超限直接拒绝，未知长度按字节计数。
 * 超限统一返回 {@code 413} / {@code 41300}，不缓存请求体。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestSizeGlobalFilter implements GlobalFilter, Ordered {

    /** 未配置时的兜底上限，与 {@link GatewayRequestSizeProperties} 默认值一致。 */
    private static final long DEFAULT_MAX_BODY_BYTES = 2L * 1024 * 1024;

    private final GatewayRequestSizeProperties properties;

    private final GatewayErrorWriter errorWriter;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getURI().getPath();
        long limit = resolveLimit(path);
        long declared = exchange.getRequest().getHeaders().getContentLength();
        if (declared > limit) {
            return reject(exchange, path, limit, declared);
        }
        if (declared >= 0) {
            return chain.filter(exchange);
        }
        // 未知长度（chunked）：流式转发时计数，超过上限立即中断
        ServerHttpRequest request = new SizeLimitedRequest(exchange.getRequest(), limit);
        return chain.filter(exchange.mutate().request(request).build());
    }

    /**
     * 解析当前路径的请求体上限：命中覆盖规则时取规则值，否则使用全局默认值。
     *
     * @param path 请求路径
     * @return 上限字节数
     */
    private long resolveLimit(String path) {
        long fallback = properties.getMaxBodySize() == null
                ? DEFAULT_MAX_BODY_BYTES
                : properties.getMaxBodySize().toBytes();
        for (GatewayRequestSizeProperties.Override override : properties.getOverrides()) {
            if (!StringUtils.hasText(override.getPath()) || !pathMatcher.match(override.getPath(), path)) {
                continue;
            }
            if (override.getMaxBodySize() == null || override.getMaxBodySize().toBytes() <= 0) {
                return fallback;
            }
            return override.getMaxBodySize().toBytes();
        }
        return fallback;
    }

    /**
     * 已知长度超限时直接返回错误，不读取请求体。
     *
     * @param exchange 请求交换上下文
     * @param path 请求路径
     * @param limit 允许的最大字节数
     * @param declared 声明的请求体长度
     * @return 错误响应
     */
    private Mono<Void> reject(ServerWebExchange exchange, String path, long limit, long declared) {
        String traceId = exchange.getAttribute(GatewayConstants.ATTR_TRACE_ID);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CommonConstants.MDC_TRACE_ID,
                StringUtils.hasText(traceId) ? traceId : "-")) {
            log.warn("event=request_too_large path={} declared={} limit={}", path, declared, limit);
        }
        return errorWriter.write(exchange, HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    /**
     * 未知长度请求体装饰器：累计字节数，超过上限立即抛错。
     */
    private static final class SizeLimitedRequest extends ServerHttpRequestDecorator {

        private final long limit;

        private SizeLimitedRequest(ServerHttpRequest delegate, long limit) {
            super(delegate);
            this.limit = limit;
        }

        @Override
        public Flux<DataBuffer> getBody() {
            AtomicLong total = new AtomicLong();
            return super.getBody().map(buffer -> {
                long size = total.addAndGet(buffer.readableByteCount());
                if (size > limit) {
                    DataBufferUtils.release(buffer);
                    throw new RequestSizeExceededException(limit);
                }
                return buffer;
            });
        }
    }
}
