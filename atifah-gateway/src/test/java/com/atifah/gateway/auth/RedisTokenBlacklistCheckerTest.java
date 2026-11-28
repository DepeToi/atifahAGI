package com.atifah.gateway.auth;

import com.atifah.gateway.config.GatewayAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * jti 黑名单查询测试：Redis 异常时 fail-open / fail-closed 行为。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class RedisTokenBlacklistCheckerTest {

    @Test
    void failOpenReturnsFalseOnRedisError() {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        when(template.hasKey(anyString())).thenReturn(Mono.error(new IllegalStateException("down")));
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.getBlacklist().setFailOpen(true);
        RedisTokenBlacklistChecker checker = new RedisTokenBlacklistChecker(template, properties);

        assertFalse(checker.isRevoked("jti-1").block());
    }

    @Test
    void failClosedThrowsOnRedisError() {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        when(template.hasKey(anyString())).thenReturn(Mono.error(new IllegalStateException("down")));
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.getBlacklist().setFailOpen(false);
        RedisTokenBlacklistChecker checker = new RedisTokenBlacklistChecker(template, properties);

        assertThrows(TokenBlacklistUnavailableException.class,
                () -> checker.isRevoked("jti-1").block());
    }
}
