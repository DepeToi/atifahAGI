package com.atifah.common.security.secret;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置密钥解析与解密。
 *
 * <p>按「优先级解析后的最终生效值」处理密文：先求出每个配置键在全部属性源中的生效值
 * （与消费者实际读到的值一致），只解密生效值为 ENC() 的键，解密结果集中写入最高优先级的
 * 派生属性源 {@link #DECRYPTED_SOURCE_NAME}。全部解密成功才原子替换派生源，刷新失败不会
 * 留下半明文半密文的混合状态；重复调用是幂等的（重算派生源）。被更高优先级源覆盖的
 * 本地 ENC() 密文不参与解密——否则生产环境用真实主密钥解仓库哑密文必然失败，与
 * 「Nacos 覆盖下发 + 本地兜底」的配置模型一致。值统一经字符串转换后判定，兼容本地
 * yaml 装载产物 OriginTrackedValue 与 Nacos 源的原始字符串。</p>
 *
 * <p>明文守卫（{@code enforceGuard=true}，仅启动阶段执行）检查 {@link #SECRET_KEYS}
 * 登记的密钥键的生效值：值非空且不是 ENC() 密文时直接拒绝启动，把"密钥禁止明文入库"
 * 从文档约定升级为机器强制。</p>
 *
 * @author DepeToi
 * @since 1.0.0
 */
public final class SecretPropertySourceScanner {

    /** 主密钥配置键：Nacos 公共配置优先，各服务本地 application.yml 兜底（仓库仅允许开发哑值）。 */
    public static final String MASTER_KEY_PROPERTY = "atifah.crypto.master-key";

    /** 仓库内置的开发哑主密钥：只用于解密仓库内哑密文，生产环境取到该值必须拒绝启动。 */
    public static final String DEV_MASTER_KEY = "Vct5lyW9M9uW9GltsCt7V41lWIzO6JNugYRTVMw1Li0=";

    /** 解密产物属性源名：只存放按优先级解析后解密出的明文，插入最高优先级；属派生数据，禁止手工配置。 */
    public static final String DECRYPTED_SOURCE_NAME = "atifahSecretDecryptedProperties";

    /**
     * 受明文守卫保护的密钥键清单：这些键的值允许为空，但非空时必须是 ENC() 密文。
     * 新增密钥配置项时同步维护本清单与 docs/rules/security.md SEC-02。
     */
    static final List<String> SECRET_KEYS = List.of(
            "spring.datasource.password",
            "spring.data.redis.password",
            "atifah.security.jwt.secret",
            "atifah.gateway.admin-auth.secret");

    /** 不参与解密的系统级属性源：ENC 密文只会出现在配置文件中，命令行参数与系统属性/环境变量不处理。 */
    private static final List<String> SKIPPED_SOURCE_NAMES = List.of("commandLineArgs", "systemProperties", "systemEnvironment");

    private SecretPropertySourceScanner() {
    }

    /**
     * 解析并解密配置中的 ENC() 密文。
     *
     * @param environment Spring 环境
     * @param enforceGuard 是否执行明文密钥守卫（仅启动阶段为 true，动态刷新重放为 false）
     */
    public static void decrypt(ConfigurableEnvironment environment, boolean enforceGuard) {
        if (enforceGuard) {
            enforcePlaintextGuard(environment);
        }
        rebuildDecryptedSource(environment);
    }

    /**
     * 重建解密产物属性源：收集全部属性源的键名，按优先级解析每个键的生效值，
     * 只解密生效值为 ENC() 的键，全部成功后原子写入/替换派生属性源；
     * 没有生效密文时移除派生源（上游密文被下线后不残留旧明文）。
     */
    private static void rebuildDecryptedSource(ConfigurableEnvironment environment) {
        MutablePropertySources sources = environment.getPropertySources();
        Set<String> names = new LinkedHashSet<>();
        for (PropertySource<?> source : sources) {
            if (isSkippedSource(source) || !(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                names.add(name);
            }
        }
        Map<String, Object> decrypted = new LinkedHashMap<>();
        byte[] masterKey = null;
        for (String name : names) {
            String value = resolveUpstreamValue(sources, name);
            if (value == null || !SecretCipher.isEncryptedValue(value)) {
                continue;
            }
            if (masterKey == null) {
                masterKey = requireMasterKey(environment);
            }
            decrypted.put(name, SecretCipher.decrypt(masterKey, value));
        }
        if (decrypted.isEmpty()) {
            sources.remove(DECRYPTED_SOURCE_NAME);
            return;
        }
        MapPropertySource replacement = new MapPropertySource(DECRYPTED_SOURCE_NAME, decrypted);
        if (sources.contains(DECRYPTED_SOURCE_NAME)) {
            sources.replace(DECRYPTED_SOURCE_NAME, replacement);
        } else {
            sources.addFirst(replacement);
        }
    }

    /**
     * 解析键在上游属性源（跳过解密产物源与系统级源）中的生效值，对齐消费者实际解析结果。
     * 值统一转字符串（OriginTrackedValue 的 toString 返回原始串），不做嵌套占位符展开。
     */
    private static String resolveUpstreamValue(MutablePropertySources sources, String name) {
        for (PropertySource<?> source : sources) {
            if (isSkippedSource(source)) {
                continue;
            }
            Object value = source.getProperty(name);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static boolean isSkippedSource(PropertySource<?> source) {
        return SKIPPED_SOURCE_NAMES.contains(source.getName())
                || DECRYPTED_SOURCE_NAME.equals(source.getName());
    }

    private static void enforcePlaintextGuard(ConfigurableEnvironment environment) {
        for (String key : SECRET_KEYS) {
            String value = environment.getProperty(key);
            if (!StringUtils.hasText(value) || SecretCipher.isEncryptedValue(value)) {
                continue;
            }
            throw new IllegalStateException("检测到明文密钥配置 " + key
                    + "：密钥必须用 SecretTool 加密为 ENC() 后写入配置文件（Nacos 侧同理），禁止明文入库；"
                    + "加密方法见 README「配置密钥加密」章节");
        }
        String masterKey = environment.getProperty(MASTER_KEY_PROPERTY);
        if (StringUtils.hasText(masterKey) && SecretCipher.isEncryptedValue(masterKey)) {
            throw new IllegalStateException("主密钥 " + MASTER_KEY_PROPERTY
                    + " 本身不能是 ENC() 密文（解密它需要先有主密钥，存在自举问题），必须为 Base64 明文");
        }
    }

    private static byte[] requireMasterKey(ConfigurableEnvironment environment) {
        String masterKey = environment.getProperty(MASTER_KEY_PROPERTY);
        if (!StringUtils.hasText(masterKey)) {
            throw new IllegalStateException("配置中存在 ENC() 密文但未配置主密钥 " + MASTER_KEY_PROPERTY
                    + "：请在 Nacos 公共配置 common-application.yaml 或本地 application.yml 配置"
                    + "（Nacos 优先、本地兜底）；生产命名空间必须下发生产主密钥");
        }
        try {
            return SecretCipher.parseMasterKey(masterKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("主密钥 " + MASTER_KEY_PROPERTY + " 不合法：" + e.getMessage(), e);
        }
    }
}
