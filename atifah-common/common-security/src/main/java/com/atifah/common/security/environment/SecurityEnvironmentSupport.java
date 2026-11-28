package com.atifah.common.security.environment;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * 安全校验使用的运行环境解析。
 *
 * <p>环境由 Nacos namespace 区分（本地/沙箱连 atifah_dev，生产连生产 namespace），
 * 不强制显式声明激活 profile：未激活任何 profile 时按本地语义处理（fail-open）；
 * 显式激活 profile 时，只要出现 local/dev/test/container 之外的未知 profile
 * （如 uat、prod）即按生产处理（fail-closed）。生产语义（RS256 强制、CORS 收敛、
 * 开发哑主密钥拒绝等）由生产部署资产内置的 prod profile 触发。</p>
 *
 * <p>容器例外：镜像 ENTRYPOINT 固定注入 container，容器内必须再显式声明 prod 或
 * local/dev/test，仅带 container 视为环境歧义并拒绝启动（fail-closed）。</p>
 *
 * @author DepeToi
 * @since 1.0.0
 */
public final class SecurityEnvironmentSupport {

    /** 生产环境 profile。 */
    public static final String PRODUCTION_PROFILE = "prod";

    /** 容器 profile：镜像 ENTRYPOINT 固定注入，必须搭配 prod 或 local/dev/test 之一显式声明环境。 */
    private static final String CONTAINER_PROFILE = "container";

    /** 允许与 container 搭配、显式声明非生产的 profile。 */
    private static final Set<String> EXPLICIT_SANDBOX_PROFILES = Set.of("local", "dev", "test");

    /** 允许使用本地安全默认值的 profile：本地直跑、开发/测试与容器沙箱。 */
    private static final Set<String> NON_PRODUCTION_PROFILES = Set.of("local", "dev", "test", "container");

    private SecurityEnvironmentSupport() {
    }

    /**
     * 归一化激活 profile，并拒绝环境歧义的容器启动。
     *
     * <p>未激活任何 profile 是合法状态（按本地语义处理），返回空串；
     * 返回值为逗号分隔的小写 profile 串。</p>
     *
     * <p>镜像 ENTRYPOINT 固定注入 {@code container}，因此容器内必须再显式声明环境：
     * 生产容器追加 {@code prod}，本地/沙箱容器追加 {@code local}（或 {@code dev} / {@code test}）。
     * 仅带 {@code container} 时无法判断是否生产，直接拒绝启动，避免生产安全护栏被静默跳过。</p>
     *
     * @param environment Spring 环境
     * @return 归一化后的 profile 串，未激活时为空串
     * @throws IllegalStateException 容器内未显式声明 prod 或 local/dev/test
     */
    public static String require(Environment environment) {
        String[] activeProfiles = environment.getActiveProfiles();
        StringBuilder normalized = new StringBuilder();
        for (String profile : activeProfiles) {
            if (normalized.length() > 0) {
                normalized.append(',');
            }
            normalized.append(profile.trim().toLowerCase(Locale.ROOT));
        }
        String profiles = normalized.toString();
        rejectAmbiguousContainerProfiles(profiles);
        return profiles;
    }

    /**
     * 容器内必须显式声明运行环境，禁止仅凭 {@code container} 推断本地语义。
     *
     * @param profiles 归一化后的 profile 串
     * @throws IllegalStateException 仅激活 container 且未声明 prod 或 local/dev/test
     */
    private static void rejectAmbiguousContainerProfiles(String profiles) {
        if (!containsProfile(profiles, CONTAINER_PROFILE)) {
            return;
        }
        if (containsProfile(profiles, PRODUCTION_PROFILE)) {
            return;
        }
        for (String sandbox : EXPLICIT_SANDBOX_PROFILES) {
            if (containsProfile(profiles, sandbox)) {
                return;
            }
        }
        throw new IllegalStateException("容器内必须显式声明运行环境（profiles=" + profiles + "）："
                + "生产容器追加 --spring.profiles.active=prod，"
                + "本地容器追加 --spring.profiles.active=local；"
                + "仅带 container 无法判断环境，按 fail-closed 拒绝启动");
    }

    /**
     * 判断逗号分隔的 profile 串是否包含指定 profile。
     *
     * @param profiles 归一化后的 profile 串
     * @param target 目标 profile（小写）
     * @return 是否包含
     */
    private static boolean containsProfile(String profiles, String target) {
        if (!StringUtils.hasText(profiles)) {
            return false;
        }
        for (String profile : profiles.split(",")) {
            if (target.equals(profile.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否为允许使用本地安全默认值的环境。
     *
     * <p>未激活任何 profile（空串）按本地处理；激活 profile 全部属于
     * local/dev/test/container 时为非生产；出现任意未知 profile 按生产处理（fail-closed）。</p>
     *
     * @param profiles {@link #require} 返回的 profile 串
     * @return 是否为非生产环境
     */
    public static boolean isNonProduction(String profiles) {
        if (!StringUtils.hasText(profiles)) {
            return true;
        }
        for (String profile : profiles.split(",")) {
            if (!NON_PRODUCTION_PROFILES.contains(profile.trim().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
