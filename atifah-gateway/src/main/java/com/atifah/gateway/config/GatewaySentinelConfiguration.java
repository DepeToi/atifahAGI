package com.atifah.gateway.config;

import com.atifah.common.core.constant.CommonConstants;
import com.atifah.common.core.enums.ErrorCode;
import com.atifah.gateway.constant.GatewayConstants;
import com.atifah.gateway.support.GatewayBlockErrors;
import com.atifah.gateway.support.GatewayErrorWriter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * Sentinel 网关配置：把限流/熔断的阻塞响应统一为 {@code Result}。
 * 该 Bean 由 SCA 自动配置在初始化时注册到 {@code GatewayCallbackManager}，覆盖默认阻塞响应。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class GatewaySentinelConfiguration {

    /**
     * 自定义阻塞处理器：熔断/系统保护返回 503，其余（限流）返回 429。
     *
     * @param errorWriter 统一错误出口
     * @return 阻塞处理器
     */
    @Bean
    public BlockRequestHandler gatewayBlockRequestHandler(GatewayErrorWriter errorWriter) {
        return (exchange, throwable) -> {
            HttpStatus status = GatewayBlockErrors.isDegrade(throwable)
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.TOO_MANY_REQUESTS;
            ErrorCode errorCode = status == HttpStatus.SERVICE_UNAVAILABLE
                    ? ErrorCode.SERVICE_UNAVAILABLE
                    : ErrorCode.TOO_MANY_REQUESTS;
            String traceId = exchange.getAttribute(GatewayConstants.ATTR_TRACE_ID);
            try (MDC.MDCCloseable ignored = MDC.putCloseable(CommonConstants.MDC_TRACE_ID,
                    StringUtils.hasText(traceId) ? traceId : "-")) {
                log.warn("event=gateway_blocked path={} status={} code={}",
                        exchange.getRequest().getURI().getPath(), status.value(), errorCode.getCode());
            }
            return errorWriter.response(status, errorCode);
        };
    }
}
