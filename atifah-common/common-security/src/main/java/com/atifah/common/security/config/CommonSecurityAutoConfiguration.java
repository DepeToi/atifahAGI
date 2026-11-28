package com.atifah.common.security.config;

import com.atifah.common.security.jwt.JwtTokenProvider;
import com.atifah.common.security.secret.SecretRefreshListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 安全公共能力自动装配。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperties.class)
public class CommonSecurityAutoConfiguration {

    /**
     * 装配 JWT 处理器。
     *
     * @param properties JWT 配置
     * @return JWT 处理器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "atifah.security.jwt", name = "provider-enabled", havingValue = "true")
    public JwtTokenProvider jwtTokenProvider(JwtProperties properties) {
        return new JwtTokenProvider(properties);
    }

    /**
     * 装配 JWT 生产基线校验。
     *
     * @param environment 运行环境
     * @param properties JWT 配置
     * @return 启动校验器
     */
    @Bean
    @ConditionalOnProperty(prefix = "atifah.security.jwt", name = "provider-enabled", havingValue = "true")
    public JwtSecurityStartupValidator jwtSecurityStartupValidator(Environment environment,
                                                                   JwtProperties properties) {
        return new JwtSecurityStartupValidator(environment, properties);
    }

    /**
     * 装配动态刷新后的密钥重放解密监听。
     *
     * @param environment 当前运行环境
     * @return 刷新监听器
     */
    @Bean
    @ConditionalOnMissingBean
    public SecretRefreshListener secretRefreshListener(Environment environment) {
        return new SecretRefreshListener(environment);
    }

    /**
     * 装配密码编码器（BCrypt）。
     *
     * @return 密码编码器
     */
    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
