# atifah-gateway

API 网关，Spring Cloud Gateway（WebFlux 变体，HTTP/2），端口 `8080`。

职责：路由、JWT 鉴权、CORS、请求限制、访问日志与稳定性兜底。对外唯一入口
（生产外部再经 Nginx 接入层，见 [docs/architecture.md](../docs/architecture.md)）。

## 路由

- `/api/v1/{auth,users,moods,analysis,community,contents,messages,growth,stats}/**`
  分别转发到对应服务；`/ws/**` 以 `lb:ws://atifah-message` 转发 WebSocket；
  `/admin/api/**` 转发到 atifah-admin。
- 路由权威定义在网关模块的 `application.yml`；Nacos `atifah-gateway.yaml` 当前只覆盖鉴权开关与白名单，
  不包含路由表。修改路由后需重新构建并重启网关。

## 鉴权

- 按路径前缀分三条互斥链：`/api/v1/**` C 端 JWT、`/admin/api/**` 管理端独立密钥、
  `/ws/**` 子协议握手校验。
- C 端白名单为「路径模式 + 方法」且空白名单拒绝所有（`atifah.gateway.auth.rules`）。
- 鉴权通过后向下游写入 `X-User-Id` / `X-Username`（管理端写 `X-Admin-Id`）；统一错误体为 `Result`。
- `atifah.gateway.auth.enabled` 默认 `true` 并保持 fail-closed。只有隔离的本地排障环境
  才允许经 Nacos 网关私有配置覆盖为 `false` 显式关闭，生产环境禁止关闭。

## 令牌与黑名单

- access token 带 `typ=access` 与唯一 `jti`；生产建议 RS256，auth-service 持私钥签发、
  网关只加载公钥。
- 网关按 `jti` 查询 Redis 黑名单（`auth:token:<jti>`，`atifah.gateway.auth.blacklist`）。
- auth-service 登出写黑名单尚未实现，当前登出只撤销 Refresh Token，Access Token 在剩余
  TTL（≤15 分钟）内仍有效。
- Redis 异常默认放行（fail-open），生产需显式选择 fail-closed 或配置告警。

## CORS

由网关统一处理，来源配置为
`spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowed-origin-patterns`
（本地默认 `*`）；生产（激活 profile 非 `local`/`dev`/`test`/`container`，经 Nacos 网关私有配置覆盖）
漏配或包含裸 `*` 时启动失败。响应头 `X-Trace-Id` 通过 `Access-Control-Expose-Headers` 暴露给前端。

## 请求限制

- 请求体默认 2MB（`atifah.gateway.request-size`，上传接口可按路径覆盖），超限返回
  `413` / `41300`。
- 请求头上限 16KB（`server.max-http-request-header-size`）。

## 稳定性

- 超时、熔断、限流统一返回 `503` / `50300` 或 `429` / `42900`；熔断由 Sentinel 按路由 ID
  执行，未引入 Resilience4j。
- 下游连接/响应超时当前使用 SCG/Netty 默认值，显式配置为规划。
- 限流规则当前通过 Sentinel Dashboard 配置；Nacos 持久化（`ATIFAH_GATEWAY_GROUP` 下的
  `atifah-gateway-gw-flow.json` / `degrade.json`）为规划，尚未接入 datasource，
  重启后 Dashboard 临时规则不保证保留。

## 真实 IP

仅当 TCP 对端命中 `TRUSTED_PROXIES`（默认回环 + RFC1918/ULA）时解析
`X-Forwarded-For` / `X-Real-IP`，直连伪造头不生效；解析结果供访问日志与 IP 频控复用。

## WebSocket

`/ws/**` 使用子协议令牌鉴权；空闲超时、最大帧大小与 `proxy-ping` 的显式配置为规划，
当前使用 Netty 默认值。

## 可观测

- Actuator 使用独立管理端口 `9090`，暴露 `health,info,gateway,metrics,prometheus`。
  Compose 仅把 `9090` 绑定到宿主机回环地址；业务端口 `8080` 不反向代理该管理端口。
- 接入 Sentinel Dashboard（`eager: true`）。
- 访问日志启动后包装为异步 appender（`GATEWAY_LOG_ASYNC` / `GATEWAY_LOG_ASYNC_QUEUE`），
  2xx/3xx 可按 `GATEWAY_ACCESS_LOG_SAMPLE_RATE` 采样，4xx/5xx 始终全量。

## 兼容性

- SCG 4.3+ 的 `spring.cloud.gateway.server.webflux.*` 命名空间。
- 排除 Windows 虚拟网卡注册。

## 运行

构建与启动命令见根 [README 快速开始](../README.md#快速开始)。
