package com.atifah.common.security.config;

/**
 * JWT Provider 的密钥用途。
 *
 * @author DepeToi
 * @since 1.0.0
 */
public enum JwtUsage {

    /** 仅签发令牌，持有签名私钥。 */
    SIGNER,

    /** 仅验签令牌，持有验签公钥且禁止加载私钥。 */
    VERIFIER,

    /** 同时签发与验签，持有完整密钥对。 */
    BOTH
}
