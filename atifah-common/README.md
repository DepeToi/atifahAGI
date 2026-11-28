# atifah-common

平台公共组件，按能力拆为六个 Maven 模块，业务服务引入依赖即可使用（通过 Spring Boot 3
`META-INF/spring/...AutoConfiguration.imports` 自动装配）。

| 模块 | 关键组件 | 说明 |
| --- | --- | --- |
| common-core | `Result<T>` / `PageResult<T>` / `ErrorCode` / `BusinessException` / `TraceIdUtils` / `CommonConstants` | 统一响应结构、错误码（40000 参数 / 40100 认证 / 40900 冲突 / 42900 限流）、链路 ID 规范化（32 位 hex） |
| common-web | `GlobalExceptionHandler` / `AccessLogFilter` / `TraceIdFilter` / `FeignTraceIdInterceptor` | 全局异常转 `Result`；访问日志（不记录 query/请求体，防敏感信息泄露）；TraceId 与用户身份跨服务透传 |
| common-redis | `RedisUtil` / `CacheKeys` | Redisson 封装（原子计数脚本、空白密码归一化）、统一缓存 Key 常量（幂等、登录失败计数等） |
| common-mybatis | `BaseEntity` / `MybatisPlusAutoConfiguration` | 公共字段（created_at/updated_at/deleted）、MyBatis-Plus 逻辑删除与雪花 ID |
| common-mq | `MessagePublisher` / `MqTopics` / `MqConsumerGroups` | RocketMQ 统一发布入口、Topic 与消费组常量 |
| common-security | `JwtTokenProvider` / `JwtProperties` / `NacosCommonConfigGuard` | JWT 签发与解析（仅显式启用 Provider 的服务加载）；公共配置装载守卫（缺 `atifah.common.config-loaded=true` 即拒绝启动） |

## 目录

```text
atifah-common/
├── common-core/       # 响应结构、错误码、异常、TraceId
├── common-web/        # 全局异常、访问日志、Trace 透传
├── common-redis/      # Redisson 封装与缓存 Key
├── common-mybatis/    # 公共实体字段与 MyBatis-Plus 配置
├── common-mq/         # RocketMQ 发布入口与常量
└── common-security/   # 按需 JWT 签发解析、运行环境校验、公共配置装载守卫
```

## 使用约定

- 依赖分层：`common-web`（含 `common-core`）、`common-mybatis`、`common-security` 由 `atifah-services` 父 POM
  统一提供给所有业务服务；`common-redis`、`common-mq` 不向下传递，由实际使用 Redis / RocketMQ 的服务在
  自己的 `pom.xml` 显式引入（当前仅 `auth-service` 引入 `common-redis`）。
- 引入 `common-redis` / `common-mq` 时，同时补齐对应配置（`spring.data.redis.*` / `rocketmq.*`）；未引入的
  服务不加载相关自动配置，也不需要 Redis / RocketMQ 可达。
- 业务服务只依赖所需的能力模块，不引入整包。
- 自动装配类位于各模块 `src/main/resources/META-INF/spring/` 下，勿删除或重命名。
- 统一响应、错误码与日志规范见 [docs/rules/](../docs/rules/security.md) 与 [docs/contracts/api-conventions.md](../docs/contracts/api-conventions.md)。
