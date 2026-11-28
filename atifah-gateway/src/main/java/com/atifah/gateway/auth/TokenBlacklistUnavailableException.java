package com.atifah.gateway.auth;

/**
 * 黑名单查询不可用且策略为 fail-closed。
 *
 * @author DepeToi
 * @since 1.0.0
 */
public class TokenBlacklistUnavailableException extends RuntimeException {

    public TokenBlacklistUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
