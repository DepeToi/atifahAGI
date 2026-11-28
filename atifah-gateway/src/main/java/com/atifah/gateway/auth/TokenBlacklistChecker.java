package com.atifah.gateway.auth;

import reactor.core.publisher.Mono;

/**
 * JWT jti 黑名单查询。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@FunctionalInterface
public interface TokenBlacklistChecker {

    /**
     * 判断令牌是否已被撤销。
     *
     * @param jti 令牌唯一 ID
     * @return 是否已撤销
     */
    Mono<Boolean> isRevoked(String jti);
}
