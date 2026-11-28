# 安全与隐私规则（SEC）

> 适用范围：网关、所有业务服务、管理后台 BFF 与前端。
> 违反 SEC-01 ~ SEC-03 的 PR 直接 `REQUEST_CHANGES`。

## SEC-01 数据分级与处理

| 级别 | 数据 | 要求 |
| --- | --- | --- |
| 敏感个人信息 | 情绪日记正文、量表结果、语音/图片、匿名身份映射 | 字段级加密（AES-256-GCM），密钥由 KMS/Vault 管理；接口默认不返回明文 |
| 个人敏感字段 | 手机号、密码、设备标识 | 密码只存 BCrypt/Argon2id 哈希；手机号脱敏展示 |
| 一般业务数据 | 昵称、打卡分值、积分 | 按最小权限访问 |

- 禁止在日志、控制台、异常 message、MQ 消息、URL query 中出现上述敏感明文。
- 日志只打 ID / 状态 / 脱敏摘要；手机号格式 `138****5678`。
- 管理端查看明文必须专项授权并记录审计；匿名帖反查同样留痕。
- 数据导出/注销走 Saga 清理各服务数据，不得只删主表。
- 需字段级加密的「敏感个人信息」列名使用 `*_cipher`（如 `content_cipher`），禁止明文列；手机号属「个人敏感字段」，明文存储并脱敏展示。

## SEC-02 密钥与凭据

- 禁止明文密码、JWT 密钥、Token、AK/SK 入库（Git 与 Nacos 同等要求）。
- **连接串允许入库**：数据库、Redis、RocketMQ、Nacos 等中间件的连接串（主机、端口、库名与查询参数）可以写入
  application*.yml 与 Nacos 配置；其中的口令仍按下一条「配置密钥加密入库」处理，不得明文。
  需要隐藏生产内网拓扑时，可把整条连接串 `ENC()` 加密后由 Nacos 生产命名空间下发（`SecretTool` 支持任意配置值）；
  日志、异常 message、URL query 中仍不得出现连接串明文。
- **配置密钥加密入库**：登记密钥键（`spring.datasource.password` / `spring.data.redis.password` /
  `atifah.security.jwt.secret` / `atifah.gateway.admin-auth.secret`；新增密钥键须同步
  `SecretPropertySourceScanner.SECRET_KEYS` 与本条）的值必须用 `SecretTool` 加密为 `ENC(...)`
  （AES-256-GCM，JDK 自带实现，无新增依赖）后写入 application*.yml 或 Nacos 配置；
  运行时由 `SecretEnvironmentPostProcessor` 解密为明文，下游组件拿到的仍是明文。
  解密以「按属性源优先级解析后的最终生效值」为准：被 Nacos 同名键覆盖的本地 ENC() 密文
  不参与解密（否则生产用真实主密钥解仓库哑密文必然失败），未被覆盖的 ENC() 密文
  必须能被生效主密钥解开，解不开拒绝启动（fail-closed）。
- **明文守卫**：非 `test` profile 下，登记密钥键的值非空且不是 `ENC(...)` 密文时启动直接失败；
  解密失败（主密钥不匹配/密文损坏）同样拒绝启动，禁止静默降级。
- **主密钥管理**：`atifah.crypto.master-key`（32 字节 Base64，`openssl rand -base64 32` 生成）
  本身不得加密，随配置单一来源管理：**Nacos 公共配置（common-application.yaml）优先，
  各服务本地 application.yml 兜底**，不经启动参数注入。仓库与开发命名空间只允许出现
  开发哑密钥（只保护仓库哑密文）；生产主密钥仅在 Nacos 生产命名空间下发，
  生产环境兜底取到开发哑密钥时启动直接失败（防呆）。
  轮换 = 用新主密钥经 `SecretTool encrypt` 重新生成全部 ENC() 值。
- **不加密的两个例外**（自举或非配置形态）：Nacos 客户端引导凭据
  （`spring.cloud.nacos.{discovery,config}.username/password`，解密器要先连 Nacos 才能拉配置，
  生产经启动参数注入）；JWT RS256 公钥/私钥 PEM 文件（文件放部署机、权限 600，配置只存路径）。
- JWT Provider 只在网关与 auth-service 显式启用；网关必须使用 `VERIFIER`，auth-service 必须使用 `SIGNER`，启动时发现职责与密钥不匹配即拒绝启动。
- 公共 Nacos 配置不得下发 JWT 签名密钥，普通业务服务不得持有签名私钥或 HS256 密钥。
- **配置单一来源**：应用配置以 yaml 为唯一来源（本地 application.yml + application-{profile}.yaml +
  Nacos 导入），禁止新增运行时环境变量式配置项；Nacos 导入优先于本地 yaml，
  生产差异（含生产主密钥）经 Nacos 生产命名空间（如 `atifah_prod`）覆盖下发，
  仅 Nacos 引导凭据走启动参数。
- **环境区分**：环境由 Nacos namespace 区分（本地/沙箱 `atifah_dev`，生产 namespace 由部署资产
  指定），不强制显式声明激活 profile：未激活 profile 按本地语义处理；激活 profile 出现
  local/dev/test/container 之外的值（含生产部署资产内置的 prod）时按生产处理——
  自动要求 RS256、拒绝开发哑主密钥，不得依赖可选开关绕过。
  容器是例外：镜像 ENTRYPOINT 固定注入 `container`，容器内必须再显式声明 `prod`（生产）
  或 `local`/`dev`/`test`（本地/沙箱）；仅带 `container` 视为环境歧义，启动直接失败。
- 密钥、证书、`.env`、`*.local.yml` 不得提交 Git。

## SEC-03 认证与鉴权

- 唯一入口是网关：C 端 `/api/v1/**`，管理端 `/admin/api/**`，WebSocket `/ws/**`。
- 网关校验 JWT 后写入 `X-User-Id` / `X-Username`；管理端链写入 `X-Admin-Id`。业务服务直接读 `CommonConstants`，**不得重复验签**。
- 业务服务与管理端不得绕过网关直连；管理端不得直连业务库。
- 管理端使用独立鉴权链（独立密钥 + 独立白名单），不复用 C 端 Token；MFA、IP 白名单与 RBAC 为规划，未实现前不得宣称管理端安全基线完成。
- 网关鉴权开关 `atifah.gateway.auth.enabled` 默认 `true`；三条链按路径互斥：C 端 `/api/v1/**`（JWT + 路径/方法白名单，空白名单拒绝所有）、管理端 `/admin/api/**`（独立密钥）、WebSocket `/ws/**`（`Sec-WebSocket-Protocol: atifah.token.<jwt>`，不接受 query 令牌）。生产环境禁止关闭鉴权，只有隔离的本地排障环境才允许显式 `false`。
- 部署时必须只发布网关业务端口；业务与管理端容器端口仅存在于内部网络。网关管理端点使用独立端口，且只能绑定回环或受控运维网络，禁止随业务入口发布到公网。
- Access Token 的即时撤销仍依赖 auth-service 登出写 `auth:token:<jti>`；该能力完成前只能依赖不超过 15 分钟的短 TTL，不得宣称 Access Token 可即时撤销。
- JWT `jti` 黑名单查询失败时默认放行（fail-open）；生产必须显式选择 fail-closed 或 fail-open + 告警。
- 401 统一清理登录态并跳转登录页；403 不得泄露资源是否存在。

## SEC-04 写入幂等

- 业务写接口（POST / PUT / DELETE）必须校验 `Idempotency-Key`（`CommonConstants.HEADER_IDEMPOTENCY_KEY`）。
- 认证类接口例外：登录、刷新、登出不强制；注册必须带。详见 `docs/contracts/api-conventions.md` §7。
- 服务端用 Redis `CacheKeys.IDEMPOTENT`（24h）+ 数据库唯一索引双保险。
- `Idempotency-Key` 的格式、串行/并发语义与错误码以 `docs/contracts/api-conventions.md` §7 为准，本文件不重复阈值与实现细节。

## SEC-05 传输、CORS、上传与防刷

- 生产全站 HTTPS/TLS 1.3 + HSTS；数据库账号最小权限，禁止用 root 连接生产库。
- CORS 由网关统一处理；`allowed-origin-patterns` 默认 `*` 仅限本地，生产必须经 Nacos 网关私有配置收敛为具体域名（漏配或裸 `*` 时网关启动失败）。
- 文件上传校验类型、大小、后缀；远程抓取配置 SSRF 白名单。
- 防刷按需启用验证码、UID/IP 频控、设备指纹、行为风控；公开内容先审后发。
- 新增依赖先确认；不得为一个工具方法引入新库，禁止引入第二套 JSON / 日志 / HTTP 客户端。

## 评审提示

- 评审时优先检查：日志与异常中的敏感字段、硬编码密钥、绕过网关、幂等键缺失。
- 仅发现“可能存在风险”但无法确认时，标记 `[Question]`，不要直接判 `Blocker`；确认后可升级。
