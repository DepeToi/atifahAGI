package com.atifah.gateway.auth;

import com.atifah.gateway.config.AuthRule;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 免鉴权规则匹配测试：路径模式、方法限制、空白名单与非法配置。
 *
 * @author DepeToi
 * @since 1.0.0
 */
class AuthRuleMatcherTest {

    @Test
    void matchesPathAndMethod() {
        AuthRuleMatcher matcher = new AuthRuleMatcher(List.of(
                rule("/api/v1/auth/**", "POST"),
                rule("/actuator/**", (String) null)));

        assertTrue(matcher.isWhitelisted("/api/v1/auth/login", HttpMethod.POST));
        assertFalse(matcher.isWhitelisted("/api/v1/auth/login", HttpMethod.GET));
        assertTrue(matcher.isWhitelisted("/actuator/health", HttpMethod.GET));
        assertFalse(matcher.isWhitelisted("/api/v1/moods", HttpMethod.POST));
    }

    @Test
    void emptyRulesDenyAll() {
        AuthRuleMatcher matcher = new AuthRuleMatcher(List.of());

        assertFalse(matcher.isWhitelisted("/api/v1/auth/login", HttpMethod.POST));
    }

    @Test
    void rejectsInvalidPathAndMethod() {
        assertThrows(IllegalStateException.class,
                () -> new AuthRuleMatcher(List.of(rule("api/v1/auth/**", "POST"))));
        assertThrows(IllegalStateException.class,
                () -> new AuthRuleMatcher(List.of(rule("/api/v1/auth/**", "P@ST"))));
    }

    @Test
    void rejectsDuplicateRules() {
        AuthRule first = rule("/api/v1/auth/**", "POST");
        AuthRule second = rule("/api/v1/auth/**", "POST");

        assertThrows(IllegalStateException.class,
                () -> new AuthRuleMatcher(List.of(first, second)));
    }

    private AuthRule rule(String path, String method) {
        AuthRule rule = new AuthRule();
        rule.setPath(path);
        if (method != null) {
            rule.setMethods(List.of(method));
        }
        return rule;
    }
}
