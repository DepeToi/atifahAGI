package com.atifah.common.security.secret;

import com.atifah.common.security.environment.SecurityEnvironmentSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 配置密钥解密入口：config data（本地 yaml + Nacos 导入）装载完成后执行。
 *
 * <p>默认 order 在 ConfigDataEnvironmentPostProcessor 之后，因此能覆盖本地 application*.yml
 * 与 Nacos 导入的全部属性源；Nacos 动态刷新引入的新属性源由 {@link SecretRefreshListener}
 * 重放解密。</p>
 *
 * <p>主密钥 {@code atifah.crypto.master-key} 随配置单一来源管理：Nacos 公共配置优先，
 * 各服务本地 application.yml 兜底（仓库仅允许开发哑值），不经启动参数注入；
 * 生产环境若兜底取到开发哑密钥直接拒绝启动。</p>
 *
 * <p>运行环境由 Nacos namespace 区分，不强制显式声明激活 profile（未激活按本地语义处理，
 * 由生产部署资产内置 prod profile 触发生产语义）；{@code test} profile 跳过
 * （与 {@code NacosCommonConfigGuard} 一致，测试可直接使用明文哑值）。</p>
 *
 * @author DepeToi
 * @since 1.0.0
 */
public class SecretEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (List.of(environment.getActiveProfiles()).contains("test")) {
            return;
        }
        String profiles = SecurityEnvironmentSupport.require(environment);
        SecretPropertySourceScanner.decrypt(environment, true);
        rejectDevMasterKeyInProduction(environment, profiles);
    }

    /**
     * 生产环境禁止使用仓库内置开发哑主密钥兜底（fail-closed）。
     *
     * @param environment Spring 环境
     * @param profiles 已归一化的激活 profile 串
     */
    private void rejectDevMasterKeyInProduction(ConfigurableEnvironment environment, String profiles) {
        String masterKey = environment.getProperty(SecretPropertySourceScanner.MASTER_KEY_PROPERTY);
        if (SecurityEnvironmentSupport.isNonProduction(profiles)
                || !StringUtils.hasText(masterKey)
                || !SecretPropertySourceScanner.DEV_MASTER_KEY.equals(masterKey.trim())) {
            return;
        }
        throw new IllegalStateException("生产环境（profiles=" + profiles + "）检测到仓库内置开发哑主密钥："
                + "请在 Nacos 生产命名空间的 common-application.yaml 下发真实主密钥（"
                + SecretPropertySourceScanner.MASTER_KEY_PROPERTY
                + "，openssl rand -base64 32 生成），禁止沿用开发哑值");
    }
}
