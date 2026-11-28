package com.atifah.common.security.secret;

import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * 动态刷新后的密钥重放解密。
 *
 * <p>Nacos 配置动态刷新（refreshEnabled=true）会整体替换该 dataId 对应的属性源，新属性源
 * 不经过 EnvironmentPostProcessor，其中的 ENC() 密文若不重放解密，刷新后组件会拿到密文原值。
 * 本监听以最高优先级执行，保证 ConfigurationPropertiesRebinder 重绑定时拿到的是明文。
 * 重放解密不再执行明文守卫：启动后的属性源里存在明文（首次启动解密产物）是正常状态。</p>
 *
 * @author DepeToi
 * @since 1.0.0
 */
public class SecretRefreshListener implements ApplicationListener<EnvironmentChangeEvent>, Ordered {

    private final Environment environment;

    /**
     * 构造刷新监听器。
     *
     * @param environment 当前运行环境（刷新后属性源已并入其中）
     */
    public SecretRefreshListener(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {
        if (environment instanceof ConfigurableEnvironment configurable) {
            SecretPropertySourceScanner.decrypt(configurable, false);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
