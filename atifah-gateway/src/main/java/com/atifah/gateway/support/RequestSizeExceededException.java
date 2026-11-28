package com.atifah.gateway.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 请求体超过网关限制时抛出，由统一错误出口转换为 {@code 413} / {@code 41300}。
 *
 * @author DepeToi
 * @since 1.0.0
 */
public class RequestSizeExceededException extends ResponseStatusException {

    /**
     * 构造请求体超限异常。
     *
     * @param limit 允许的最大字节数
     */
    public RequestSizeExceededException(long limit) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, "request body exceeds " + limit + " bytes");
    }
}
