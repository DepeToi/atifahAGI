package com.atifah.gateway.filter;

import com.atifah.common.core.constant.CommonConstants;
import com.atifah.common.core.enums.ErrorCode;
import com.atifah.gateway.auth.TokenBlacklistChecker;
import com.atifah.gateway.auth.TokenBlacklistUnavailableException;
import com.atifah.gateway.constant.GatewayConstants;
import com.atifah.gateway.support.GatewayErrorWriter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * 鉴权过滤器基类：统一的失败响应、身份头注入与路径判断。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Slf4j
public abstract class AbstractAuthFilter implements GlobalFilter, Ordered {

    /** Bearer 令牌前缀。 */
    protected static final String BEARER_PREFIX = "Bearer ";

    /** 身份 ID 安全字符与长度限制，避免日志注入与非法下游头。 */
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** 请求头中的控制字符，避免头注入。 */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\r\\n\\p{Cntrl}]");

    /** 下游身份头最大长度。 */
    private static final int MAX_HEADER_VALUE_LENGTH = 64;

    private final GatewayErrorWriter errorWriter;

    private final TokenBlacklistChecker blacklistChecker;

    private final boolean blacklistEnabled;

    protected AbstractAuthFilter(GatewayErrorWriter errorWriter) {
        this(errorWriter, null, false);
    }

    protected AbstractAuthFilter(GatewayErrorWriter errorWriter, TokenBlacklistChecker blacklistChecker,
                                 boolean blacklistEnabled) {
        this.errorWriter = errorWriter;
        this.blacklistChecker = blacklistChecker;
        this.blacklistEnabled = blacklistEnabled;
    }

    /**
     * 验签通过后按 jti 查询撤销列表，再进入下游。
     *
     * @param exchange 注入身份后的请求上下文
     * @param chain 过滤链
     * @param chainName 链路名称
     * @param jti 令牌唯一 ID
     * @return 响应流
     */
    protected Mono<Void> chainAfterRevocationCheck(ServerWebExchange exchange, GatewayFilterChain chain,
                                                   String chainName, String jti) {
        if (!blacklistEnabled || blacklistChecker == null) {
            return chain.filter(exchange);
        }
        if (!StringUtils.hasText(jti)) {
            return unauthorized(exchange, chainName, "missing_jti");
        }
        return blacklistChecker.isRevoked(jti)
                .flatMap(revoked -> revoked
                        ? unauthorized(exchange, chainName, "revoked_token")
                        : chain.filter(exchange))
                .onErrorResume(TokenBlacklistUnavailableException.class,
                        error -> errorWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                                ErrorCode.SERVICE_UNAVAILABLE));
    }

    /**
     * 返回 401 并记录不含令牌内容的失败原因。
     *
     * @param exchange 请求交换上下文
     * @param chain 链路名称
     * @param reason 失败原因
     * @return 响应流
     */
    protected Mono<Void> unauthorized(ServerWebExchange exchange, String chain, String reason) {
        String traceId = exchange.getAttribute(GatewayConstants.ATTR_TRACE_ID);
        String path = exchange.getRequest().getURI().getPath();
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CommonConstants.MDC_TRACE_ID,
                traceId == null ? "-" : traceId)) {
            log.warn("event=auth_failed chain={} path={} reason={}", chain, path, reason);
        }
        return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
    }

    /**
     * 判断路径是否属于指定前缀，按路径段而非字符串前缀判断。
     *
     * @param path 请求路径
     * @param prefix 前缀，如 {@code /api/v1}
     * @return 是否属于该前缀
     */
    protected static boolean pathUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * 判断身份 ID 是否合法。
     *
     * @param id 身份 ID
     * @return 是否合法
     */
    protected static boolean isValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    /**
     * 清洗响应头值：去掉控制字符并限制长度，空值返回 null。
     *
     * @param value 原始值
     * @return 可安全写入请求头的值
     */
    protected static String sanitizeHeaderValue(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = CONTROL_CHARS.matcher(value).replaceAll("").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_HEADER_VALUE_LENGTH
                ? cleaned.substring(0, MAX_HEADER_VALUE_LENGTH)
                : cleaned;
    }

    /**
     * 从 claims 中取字符串并清洗。
     *
     * @param value claim 原始值
     * @return 清洗后的值，可为 null
     */
    protected static String claimText(Object value) {
        return value == null ? null : sanitizeHeaderValue(String.valueOf(value));
    }

    /**
     * 写入下游身份头并记录到 exchange 属性。
     *
     * @param exchange 请求交换上下文
     * @param idHeader 身份头名称
     * @param id 身份 ID
     * @param username 用户名，可为 null
     * @return 变更后的 exchange
     */
    protected static ServerWebExchange injectIdentity(ServerWebExchange exchange, String idHeader,
                                                      String id, String username) {
        ServerHttpRequest.Builder builder = exchange.getRequest().mutate().header(idHeader, id);
        if (username != null) {
            builder.header(CommonConstants.HEADER_USERNAME, username);
        }
        ServerWebExchange mutated = exchange.mutate().request(builder.build()).build();
        mutated.getAttributes().put(GatewayConstants.ATTR_USER_ID, id);
        return mutated;
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
