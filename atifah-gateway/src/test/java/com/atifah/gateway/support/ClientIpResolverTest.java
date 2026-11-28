package com.atifah.gateway.support;

import com.atifah.gateway.config.GatewayClientIpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真实客户端 IP 解析测试：只信任受信代理的转发头。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(new GatewayClientIpProperties());

    @Test
    void usesForwardedForFromTrustedProxy() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/moods")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 1234))
                .header("X-Forwarded-For", "203.0.113.9, 10.0.0.2")
                .build();

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void ignoresForwardedForFromUntrustedPeer() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/moods")
                .remoteAddress(new InetSocketAddress("203.0.113.5", 1234))
                .header("X-Forwarded-For", "198.51.100.7")
                .build();

        assertEquals("203.0.113.5", resolver.resolve(request));
    }

    @Test
    void fallsBackToRemoteAddressWithoutForwardedHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/moods")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 1234))
                .build();

        assertEquals("10.0.0.1", resolver.resolve(request));
    }
}
