package com.atifah.gateway.filter;

import com.atifah.gateway.constant.GatewayConstants;
import com.atifah.gateway.support.ClientIpResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 解析真实客户端 IP 并写入 exchange 属性，供访问日志与 IP 频控复用。
 * 位于 TraceId 之后、访问日志之前。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Component
@RequiredArgsConstructor
public class ClientIpGlobalFilter implements GlobalFilter, Ordered {

    private final ClientIpResolver clientIpResolver;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clientIp = clientIpResolver.resolve(exchange.getRequest());
        if (StringUtils.hasText(clientIp)) {
            exchange.getAttributes().put(GatewayConstants.ATTR_CLIENT_IP, clientIp);
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
