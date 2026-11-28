package com.atifah.gateway.auth;

import com.atifah.common.core.constant.JwtConstants;
import com.atifah.gateway.config.GatewayAuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Redis 的 jti 黑名单查询；登出由 auth-service 写入 {@code auth:token:<jti>}。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "atifah.gateway.auth",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RedisTokenBlacklistChecker implements TokenBlacklistChecker {

    private static final long WARN_INTERVAL_MS = 60_000L;

    private final ReactiveStringRedisTemplate redisTemplate;

    private final GatewayAuthProperties.Blacklist properties;

    private final AtomicLong lastWarnAt = new AtomicLong(0);

    /**
     * 构造黑名单查询器。
     *
     * @param redisTemplate 响应式 Redis 模板
     * @param authProperties 鉴权配置
     */
    public RedisTokenBlacklistChecker(ReactiveStringRedisTemplate redisTemplate,
                                      GatewayAuthProperties authProperties) {
        this.redisTemplate = redisTemplate;
        this.properties = authProperties.getBlacklist();
    }

    @Override
    public Mono<Boolean> isRevoked(String jti) {
        return redisTemplate.hasKey(JwtConstants.BLACKLIST_KEY.formatted(jti))
                .timeout(properties.getTimeout())
                .onErrorResume(error -> {
                    if (properties.isFailOpen()) {
                        warnThrottled(error);
                        return Mono.just(false);
                    }
                    return Mono.error(new TokenBlacklistUnavailableException("黑名单查询失败", error));
                });
    }

    /**
     * Redis 故障时按分钟限流 WARN，避免逐请求刷日志。
     *
     * @param error 查询异常
     */
    private void warnThrottled(Throwable error) {
        long now = System.currentTimeMillis();
        long previous = lastWarnAt.get();
        if (now - previous >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(previous, now)) {
            log.warn("event=token_blacklist_unavailable action=pass error={}",
                    error.getClass().getSimpleName());
        } else {
            log.debug("event=token_blacklist_unavailable action=pass error={}",
                    error.getClass().getSimpleName());
        }
    }
}
