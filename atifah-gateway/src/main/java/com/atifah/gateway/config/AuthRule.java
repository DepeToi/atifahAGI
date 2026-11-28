package com.atifah.gateway.config;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关免鉴权规则：路径模式 + HTTP 方法。
 *
 * @author DepeToi
 * @since 1.0.0
 */
@Getter
@Setter
public class AuthRule {

    /** 路径模式，语义与路由 Path 谓词一致，如 {@code /api/v1/auth/**}。 */
    private String path;

    /** 允许的方法；为空表示全部方法。 */
    private List<String> methods = new ArrayList<>();
}
