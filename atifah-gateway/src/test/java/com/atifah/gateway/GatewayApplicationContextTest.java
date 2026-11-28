package com.atifah.gateway;

import com.atifah.gateway.config.GatewayAccessLogProperties;
import com.atifah.gateway.config.GatewayAdminAuthProperties;
import com.atifah.gateway.config.GatewayAsyncLogProperties;
import com.atifah.gateway.config.GatewayAuthProperties;
import com.atifah.gateway.config.GatewayClientIpProperties;
import com.atifah.gateway.config.GatewayRequestSizeProperties;
import com.atifah.common.security.config.JwtProperties;
import com.atifah.gateway.auth.RedisTokenBlacklistChecker;
import com.atifah.gateway.filter.AdminAuthFilter;
import com.atifah.gateway.filter.CUserAuthFilter;
import com.atifah.gateway.filter.WebSocketAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关上下文启动测试：确保所有新增配置属性都已注册，避免启动时缺 Bean。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.config.import=",
                "spring.cloud.sentinel.eager=false",
                "atifah.logging.async.enabled=false",
                "management.server.port=0",
                // test profile 下密钥解密 EnvironmentPostProcessor 跳过，密钥使用明文哑值（HS256 要求 ≥32 字节）
                "spring.profiles.active=test",
                "atifah.security.jwt.secret=jwt-test-secret-0123456789012345678901",
                "atifah.gateway.admin-auth.secret=admin-test-secret-0123456789012345"
        })
class GatewayApplicationContextTest {

    private final ApplicationContext context;

    GatewayApplicationContextTest(ApplicationContext context) {
        this.context = context;
    }

    @Test
    void registersAllGatewayConfigurationProperties() {
        GatewayAuthProperties authProperties = context.getBean(GatewayAuthProperties.class);
        assertNotNull(authProperties);
        assertTrue(authProperties.isEnabled(), "网关鉴权必须默认开启，避免业务路由意外裸奔");
        assertNotNull(context.getBean(GatewayAdminAuthProperties.class));
        assertNotNull(context.getBean(GatewayClientIpProperties.class));
        assertNotNull(context.getBean(GatewayRequestSizeProperties.class));
        assertNotNull(context.getBean(GatewayAccessLogProperties.class));
        assertNotNull(context.getBean(GatewayAsyncLogProperties.class));
        assertNotNull(context.getBean(CUserAuthFilter.class));
        assertNotNull(context.getBean(AdminAuthFilter.class));
        assertNotNull(context.getBean(WebSocketAuthFilter.class));
        assertNotNull(context.getBean(RedisTokenBlacklistChecker.class));
    }

    @Test
    void bindsJwtConfigurationFromLocalYaml() {
        JwtProperties properties = context.getBean(JwtProperties.class);
        assertEquals("HS256", properties.getAlgorithm());
        assertEquals("atifah", properties.getIssuer());
        assertNotNull(properties.getSecret());
    }
}
