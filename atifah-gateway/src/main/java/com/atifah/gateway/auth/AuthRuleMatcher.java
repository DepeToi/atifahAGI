package com.atifah.gateway.auth;

import com.atifah.gateway.config.AuthRule;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.util.StringUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 免鉴权规则匹配器：启动时编译并校验规则，空白名单表示拒绝所有。
 *
 * @author DepeToi
 * @since 1.0.0
 */
public class AuthRuleMatcher {

    /** HTTP 方法格式：统一转大写后须匹配 {@code ^[A-Z][A-Z0-9-]{0,31}$}（首字符必须为字母，总长 ≤32）。 */
    private static final Pattern METHOD_PATTERN = Pattern.compile("^[A-Z][A-Z0-9-]{0,31}$");

    private final List<CompiledRule> rules;

    /**
     * 编译白名单规则；配置非法或重复时启动失败。
     *
     * @param rules 白名单规则
     */
    public AuthRuleMatcher(List<AuthRule> rules) {
        PathPatternParser parser = new PathPatternParser();
        List<CompiledRule> compiled = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (AuthRule rule : rules) {
            String path = rule.getPath();
            if (!StringUtils.hasText(path) || !path.startsWith("/")) {
                throw new IllegalStateException("网关白名单规则 path 非法: " + path);
            }
            Set<String> methods = normalizeMethods(rule.getMethods());
            String key = path + "|" + methods;
            if (!keys.add(key)) {
                throw new IllegalStateException("网关白名单规则重复: " + key);
            }
            compiled.add(new CompiledRule(parser.parse(path), methods));
        }
        this.rules = List.copyOf(compiled);
    }

    /**
     * 判断请求是否命中白名单；空白名单始终返回 false。
     *
     * @param path 请求路径
     * @param method 请求方法，可为空
     * @return 是否免鉴权
     */
    public boolean isWhitelisted(String path, HttpMethod method) {
        if (rules.isEmpty()) {
            return false;
        }
        PathContainer pathContainer = PathContainer.parsePath(path);
        for (CompiledRule rule : rules) {
            if (rule.pattern().matches(pathContainer) && rule.matchesMethod(method)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 规范化方法列表并校验合法性。
     *
     * @param methods 原始方法列表
     * @return 大写方法集合；空集合表示全部方法
     */
    private Set<String> normalizeMethods(List<String> methods) {
        Set<String> normalized = new TreeSet<>();
        if (methods == null) {
            return normalized;
        }
        for (String method : methods) {
            if (!StringUtils.hasText(method)) {
                continue;
            }
            String upper = method.toUpperCase(Locale.ROOT);
            if (!METHOD_PATTERN.matcher(upper).matches()) {
                throw new IllegalStateException("网关白名单方法非法: " + method);
            }
            normalized.add(upper);
        }
        return normalized;
    }

    /**
     * 编译后的规则。
     *
     * @param pattern 路径模式
     * @param methods 允许的方法；空集合表示全部
     */
    private record CompiledRule(PathPattern pattern, Set<String> methods) {

        /**
         * 判断方法是否允许。
         *
         * @param method 请求方法
         * @return 是否允许
         */
        private boolean matchesMethod(HttpMethod method) {
            if (methods.isEmpty()) {
                return true;
            }
            return method != null && methods.contains(method.name());
        }
    }
}
