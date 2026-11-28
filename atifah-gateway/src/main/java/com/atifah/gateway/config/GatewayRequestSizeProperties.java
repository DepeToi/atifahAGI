package com.atifah.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关请求体大小限制配置。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "atifah.gateway.request-size")
public class GatewayRequestSizeProperties {

    /** 是否启用请求体大小限制。 */
    private boolean enabled = true;

    /** 默认请求体上限，未命中覆盖规则时生效。 */
    private DataSize maxBodySize = DataSize.ofMegabytes(2);

    /** 按路径覆盖的请求体上限，用于上传等特殊接口。 */
    private List<Override> overrides = new ArrayList<>();

    /**
     * 单条路径覆盖规则：Ant 风格路径 + 该路径的请求体上限。
     */
    @Getter
    @Setter
    public static class Override {

        /** Ant 风格路径模式，与路由 Path 谓词语义一致。 */
        private String path;

        /** 该路径的请求体上限；为空时使用全局默认值。 */
        private DataSize maxBodySize;
    }
}
