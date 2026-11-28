package com.atifah.gateway.support;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;

/**
 * Sentinel 阻塞异常分类：熔断/系统保护与普通限流使用不同的 HTTP 状态。
 * 统一异常处理器与自定义 {@code BlockRequestHandler} 共用本类，避免两处规则漂移。
 *
 * @author DepeToi
 * @since 1.0.0
 */
public final class GatewayBlockErrors {

    private GatewayBlockErrors() {
    }

    /**
     * 判断阻塞是否由熔断或系统保护触发；被包装的异常同样按原因链识别。
     *
     * @param ex 阻塞异常
     * @return 是否为熔断/系统保护
     */
    public static boolean isDegrade(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof DegradeException || current instanceof SystemBlockException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}
