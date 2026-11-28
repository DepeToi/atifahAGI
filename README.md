# atifah — 情绪管理高并发微服务平台

atifah 是一个面向**情绪管理**场景的高并发微服务项目，采用 **Spring Boot 3.5 + Spring Cloud Alibaba** 技术栈，涵盖 App 端业务服务、管理后台（BFF + Web 前端）、API 网关与完整的中间件部署方案。

> **当前状态**：基础设施（网关、公共组件、部署编排、CI）已就绪；`auth-service` 已实现注册 / 登录 / 刷新 / 登出，其余 8 个业务服务与管理后台后端仍为**骨架模块**（启动类 + 配置 + Docker 化）；管理后台前端已具备页面框架；`atifah-app`（移动端）为占位规划。
> OpenAPI 契约目前只有 `auth-service`（[auth-service.yaml](docs/contracts/openapi/auth-service.yaml)），其余服务实现前先补契约。
> 网关鉴权链默认开启（`atifah.gateway.auth.enabled: true`），只有隔离的本地排障环境才允许显式关闭。

## 目录

- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [架构总览](#架构总览)
- [服务清单](#服务清单)
- [模块文档](#模块文档)
- [中间件与环境依赖](#中间件与环境依赖)
- [快速开始](#快速开始)
- [配置约定](#配置约定)
- [安全与合规基线](#安全与合规基线)
- [构建与 CI](#构建与-ci)
- [日志规范](#日志规范)
- [测试](#测试)
- [文档与契约](#文档与契约)
- [路线图](#路线图)

## 技术栈

| 层次 | 技术 | 版本 |
| --- | --- | --- |
| 语言 | Java | 21（虚拟线程） |
| 框架 | Spring Boot | 3.5.16 |
| 微服务 | Spring Cloud | 2025.0.3 |
| 微服务 | Spring Cloud Alibaba | 2025.0.0.0 |
| 注册/配置中心 | Nacos | v3.0.3（namespace `atifah_dev`） |
| 接入层 | Nginx | 1.27（本地可选 profile；生产规划：TLS/WAF/限流，见 `docs/architecture.md`） |
| 网关 | Spring Cloud Gateway | WebFlux 变体，HTTP/2 |
| ORM | MyBatis-Plus | 3.5.17（雪花 ID） |
| 缓存/分布式锁 | Redis + Redisson | Redis 7.4 / Redisson 3.52.0 |
| 消息队列 | Apache RocketMQ | 5.3.1（rocketmq-spring 2.3.6） |
| 流控熔断 | Sentinel | Dashboard 1.8.8 |
| 分布式事务 | Seata（预留，growth-service） | 2.5.0 |
| 认证 | JJWT | 0.12.7 |
| 数据库 | MySQL | 8.0.39（每服务独立库） |
| 管理后台前端 | Vue 3 + Vite + TypeScript + Element Plus + Pinia | Vue 3.5 / Vite 8 |
| 部署 | Docker Compose | 中间件 + 后端容器两套编排 |
| CI | GitHub Actions | 变更门禁 / JDK 21 / Node 22 / Maven verify / 前端构建 / compose 校验 / gitleaks 密钥扫描 |

## 项目结构

```text
atifah
├── atifah-common/            # 公共组件（六模块，见 atifah-common/README.md）
├── atifah-gateway/           # API 网关（Spring Cloud Gateway，:8080）
├── atifah-services/          # 9 个业务微服务
│   ├── auth-service/         # 认证服务（注册/登录/刷新/登出已实现）
│   ├── user-service/         # 用户服务（骨架）
│   ├── mood-service/         # 情绪打卡与日记（骨架）
│   ├── analysis-service/     # 趋势与量表报告（骨架）
│   ├── community-service/    # 社区树洞（骨架）
│   ├── content-service/      # 冥想内容（骨架）
│   ├── message-service/      # 消息推送 + WebSocket（骨架）
│   ├── growth-service/       # 签到积分与成长（骨架，Seata 预留）
│   └── stats-service/        # 运营统计（骨架）
├── atifah-admin-web/
│   ├── backend/              # 管理后台聚合服务 atifah-admin（BFF，:8090）
│   └── frontend/             # 管理后台前端（Vue 3 + Element Plus，:5173）
├── atifah-app/               # 移动端 App（占位，规划 Flutter）
├── deploy/
│   ├── docker-compose/       # 中间件编排 + 后端全量编排（docker-compose.app.yml）
│   ├── nacos/config/         # Nacos 公共配置 common-application.yaml
│   ├── sql/                  # MySQL 初始化（10 个库）+ Nacos 持久化表结构
│   ├── nginx/                # 管理后台前端静态托管 + 反代配置
│   └── dev-deploy.md         # Windows 11 本地开发部署完整手册
└── .github/
    └── workflows/ci.yml      # CI（后端 verify、前端构建、密钥扫描）
```

业务服务依赖按需引入：`atifah-services` 父 POM 只统一提供 `common-web` / `common-mybatis` / `common-security`；
`common-redis`、`common-mq` 不向下传递，由实际使用 Redis / RocketMQ 的服务在自己的 `pom.xml` 显式引入
（当前仅 auth-service 引入 `common-redis`，其余骨架服务启动不依赖 Redis / RocketMQ）。

## 架构总览

```text
  浏览器 / App ──▶ Nginx 接入层（生产：TLS 终止 / WAF / 限流；本地可选：静态托管 + /admin/api 反代）
                             │
                             ▼
                    ┌─────────────────────┐
                    │  atifah-gateway :8080 │  JWT 鉴权 / 路由 / CORS / 访问日志
                    └─────────┬───────────┘
        ┌─────────────────────┼──────────────────────────┐
        ▼                     ▼                          ▼
 /api/v1/** → 9 个业务服务   /ws/** → message-service   /admin/api/** → atifah-admin :8090
        │                     │                          │
        └────────── Nacos 服务发现（lb://atifah-<service>）┘

 中间件：MySQL（库级隔离）· Redis/Redisson · RocketMQ · Sentinel · Seata（预留）
```

- API 请求统一经网关进入（生产外部先经 Nginx 接入层，见 `docs/architecture.md`），网关按路径前缀通过 Nacos 负载均衡路由到对应服务；管理后台静态资源由 Nginx 托管（本地 `deploy/nginx/`），不经网关。
- **P0 网络边界**：Compose 只发布网关的 `8080` 业务端口和绑定回环地址的 `9090` 管理端口；
  `8081–8090` 是容器内部端口，只允许经 `atifah-net` 和网关访问，不映射到宿主机。
- 每个服务**独立数据库**（`atifah_<service>`），库级隔离，避免跨服务直连。
- 公共配置（连接池、虚拟线程、MyBatis-Plus、RocketMQ 等）统一放在 Nacos 的
  `common-application.yaml`（Group `ATIFAH_PUBLIC_GROUP`），各服务通过 `spring.config.import` 导入，
  并由 `NacosCommonConfigGuard` 校验装载标记，配置未拉取成功时**拒绝启动**。

## 服务清单

| 服务 | 容器内部端口 | 数据库 | Nacos 配置 Group | 职责 | 状态 |
| --- | --- | --- | --- | --- | --- |
| atifah-gateway | 8080 | — | ATIFAH_GATEWAY_GROUP | API 网关、JWT 鉴权、路由、CORS | ✅ 可用 |
| auth-service | 8081 | atifah_auth | ATIFAH_AUTH_GROUP | 注册 / 登录 / 令牌刷新 / 登出 | ✅ 已实现|
| user-service | 8082 | atifah_user | ATIFAH_USER_GROUP | 用户资料与账户 | 🔨 骨架 |
| mood-service | 8083 | atifah_mood | ATIFAH_MOOD_GROUP | 情绪打卡与日记 | 🔨 骨架 |
| analysis-service | 8084 | atifah_analysis | ATIFAH_ANALYSIS_GROUP | 情绪趋势与量表报告 | 🔨 骨架 |
| community-service | 8085 | atifah_community | ATIFAH_COMMUNITY_GROUP | 社区树洞 / 动态 | 🔨 骨架 |
| content-service | 8086 | atifah_content | ATIFAH_CONTENT_GROUP | 冥想音频 / 文章 / 课程 | 🔨 骨架 |
| message-service | 8087 | atifah_message | ATIFAH_MESSAGE_GROUP | 站内/推送消息 + WebSocket | 🔨 骨架 |
| growth-service | 8088 | atifah_growth | ATIFAH_GROWTH_GROUP | 签到、积分与成长值（Seata 预留） | 🔨 骨架 |
| stats-service | 8089 | atifah_stats | ATIFAH_STATS_GROUP | 运营统计 | 🔨 骨架 |
| atifah-admin | 8090 | atifah_admin | ATIFAH_ADMIN_GROUP | 管理后台聚合服务（BFF） | 🔨 骨架 |
| admin-web 前端 | 5173 | — | — | 管理后台 Web（Vue 3） | ✅ 页面框架 |

> 骨架服务（除 auth-service 外）已具备：启动类（`@EnableDiscoveryClient` + `@MapperScan`）、Nacos 配置导入、
> 数据源/Redis/RocketMQ 接入配置、Dockerfile 与独立 docker-compose，可启动并注册到 Nacos；
> 业务代码按「实体 → Mapper → Service → Controller」顺序开发，DDL 走 Flyway 迁移脚本。

## 模块文档

各模块的实现细节维护在各自的 README 中，根文档只做索引：

| 模块 | 文档 | 内容摘要 |
| --- | --- | --- |
| 公共组件 | [atifah-common/README.md](atifah-common/README.md) | 六个能力模块：统一响应与错误码、Web 通用能力、Redis、MyBatis、MQ、JWT |
| API 网关 | [atifah-gateway/README.md](atifah-gateway/README.md) | 路由、JWT 鉴权三条链、CORS、请求限制、稳定性、可观测 |
| 认证服务 | [atifah-services/auth-service/README.md](atifah-services/auth-service/README.md) | 注册/登录/刷新/登出、Flyway、令牌轮换与当前缺口 |
| 管理后台 | [atifah-admin-web/README.md](atifah-admin-web/README.md) | BFF（:8090）+ Vue 3 前端（:5173）、页面路由与接口约定 |
| 部署资产 | [deploy/README.md](deploy/README.md) | 中间件版本与端口、compose profile、部署编排参数 |

## 中间件与环境依赖

本地中间件（MySQL / Redis / Nacos / RocketMQ / Sentinel / Seata / Nginx）的版本、端口、
compose profile 与部署编排参数见 [deploy/README.md](deploy/README.md)；
一键编排入口为 `deploy/docker-compose/docker-compose.yml`。

## 快速开始

完整的 Windows 11 本地部署手册见 [deploy/dev-deploy.md](deploy/dev-deploy.md)，核心步骤：

```bash
# 1. 创建本地环境文件（Nacos 3 强制要求 AUTH_TOKEN；.env 已被 .gitignore 忽略）
cd deploy/docker-compose
cp .env.example .env
#    填写 NACOS_AUTH_TOKEN（32 字节以上，可用 openssl rand -base64 32 生成）
#    PowerShell 建议：Set-Content -Encoding ascii .env 'NACOS_AUTH_TOKEN=<32 位以上随机字符串>'

# 2. 拉起中间件（MySQL/Redis/Nacos/RocketMQ）
docker compose -f docker-compose.yml up -d
#    需要控制台时再追加：docker compose --profile ops up -d
cd ../..

# 3. 向 Nacos 发布公共配置（namespace atifah_dev，Data ID common-application.yaml，
#    Group ATIFAH_PUBLIC_GROUP）——控制台 http://localhost:8849 导入，
#    或按 deploy/nacos/README.md 的运维 API 导入。未发布则业务服务拒绝启动。

# 4. 构建后端
mvn clean package -DskipTests

# 5. 启动后端（方案 A：JVM，推荐日常开发；本地直跑无需任何参数，默认连接 atifah_dev）
java -jar atifah-gateway/target/atifah-gateway-1.0.0-SNAPSHOT.jar
java -jar atifah-services/auth-service/target/auth-service-1.0.0-SNAPSHOT.jar
#    或方案 B：Docker（11 个后端容器以 container + local profile 启动，只有 gateway 发布业务端口，不能与方案 A 同时运行）
docker compose --env-file deploy/docker-compose/.env \
  -f deploy/docker-compose/docker-compose.app.yml up -d --build

# 6. 启动管理后台前端
cd atifah-admin-web/frontend && npm install && npm run dev   # http://localhost:5173

# 7. 验证
curl http://127.0.0.1:9090/actuator/health  # Gateway 管理端点，仅宿主机回环可访问
```

> 启动即退出：先确认 Nacos 已发布 `common-application.yaml`（缺 `atifah.common.config-loaded=true` 时服务会按设计拒绝启动）；8080/9090 等端口冲突、JVM 与容器混跑也会导致启动失败。

认证接口示例见 [auth-service/README.md](atifah-services/auth-service/README.md#接口示例)。


## 配置约定

- **yaml 单一来源**：应用配置全部来自配置文件，不再使用运行时环境变量——
  本地 `application.yml`（本地默认值，直连 `127.0.0.1`）+ `application-{profile}.yaml`
  （环境差异，如容器沙箱的 `application-container.yaml`）+ Nacos 导入（运行时覆盖，
  生产命名空间如 `atifah_prod` 下发生产地址与密钥）。
- **环境由 Nacos namespace 区分**：本地/容器沙箱连接 `atifah_dev`（本地 application.yml 默认值），
  生产部署资产指向生产 namespace（如 `atifah_prod`），连接后全部差异配置（地址、密钥、主密钥）
  由该 namespace 下发。容器镜像 ENTRYPOINT 已内置 `container` profile（加载容器沙箱地址，
  见各模块 `application-container.yaml`），**容器内必须再显式声明环境**：本地容器追加
  `--spring.profiles.active=local`，生产容器追加 `--spring.profiles.active=prod`；
  仅带 `container` 时无法判断环境，安全护栏按 fail-closed 拒绝启动。未激活任何 profile 的
  本地直跑按本地语义启动。日志中的 `env` 字段取自激活 profile。
- **配置密钥加密**：库口令、JWT 密钥、管理端密钥等以 `ENC(...)` 密文入库（AES-256-GCM），
  启动时由 `SecretEnvironmentPostProcessor` 统一解密，下游组件拿到的仍是明文；
  主密钥 `atifah.crypto.master-key` 随配置单一来源管理——Nacos 公共配置优先、各服务本地
  application.yml 兜底（仓库仅含开发哑密钥），生产主密钥由 Nacos 生产命名空间下发；
  生产环境兜底取到开发哑密钥时启动直接失败。
  加密方法（`SecretTool`）：

  ```bash
  # 在仓库根目录（common-security 已编译）
  java -cp atifah-common/common-security/target/classes com.atifah.common.security.secret.SecretTool genkey
  java -cp atifah-common/common-security/target/classes com.atifah.common.security.secret.SecretTool encrypt <主密钥Base64> <明文>
  # 生成的 ENC(...) 粘贴进 application*.yml 或 Nacos 配置；明文密钥/主密钥本身禁止入库
  ```
- **JWT 密钥**：本地/测试 HS256（密钥密文入库）；生产由签发/验签服务（网关、auth-service）
  启用 RS256：
  - 仅网关与 auth-service 通过 `atifah.security.jwt.provider-enabled=true` 装配 JWT Provider；
    其他业务服务不加载 JWT Provider，公共 Nacos 配置也不再下发签名密钥；
  - 网关设置 `usage=VERIFIER`，只持有验签材料：经 Nacos 网关私有配置覆盖
    `algorithm: RS256` 与 `public-key-location`；检测到私钥会拒绝启动；
  - auth-service：设置 `usage=SIGNER`，只持有私钥（`private-key-location` 指向部署机上的
    PKCS#8 文件），不得把私钥分发给其他服务。
  签名字节只接受 **PKCS#8 私钥**（`BEGIN PRIVATE KEY`）与 **X.509 公钥**（`BEGIN PUBLIC KEY`），
  PKCS#1（`BEGIN RSA PRIVATE KEY`）不支持。生成示例：

  ```bash
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
  openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem
  ```
  除 `local` / `dev` / `test` / `container` 外自动要求 RS256。
  管理端使用独立的 `atifah.gateway.admin-auth.secret`（密文入库，生产由 Nacos 网关私有配置
  下发真实密钥密文覆盖，解密后不得为占位值），不得复用 C 端密钥。
- **JWT 黑名单**：网关连接 Redis 查询 `auth:token:<jti>`；黑名单开关与故障策略
  （`atifah.gateway.auth.blacklist.*`）本地默认 fail-open，生产经 Nacos 网关私有配置收敛。
- **网关鉴权**：`atifah.gateway.auth.enabled` 默认为 `true`。生产环境禁止关闭；如需本地关闭，
  必须确认环境隔离且不可从外部网络访问。
- **CORS 与运行环境**：本地默认通配 `*`；生产必须经 Nacos 网关私有配置把 CORS 来源收敛为
  具体域名，漏配或包含裸 `*` 时网关启动失败。网关管理端点固定在独立端口 `9090`，
  Compose 仅绑定宿主机回环地址。
- **Nacos 配置分层**：每服务导入「公共配置（ATIFAH_PUBLIC_GROUP）+ 服务私有配置（ATIFAH_<SERVICE>_GROUP）」，
  支持动态刷新（`refreshEnabled=true`，Nacos 侧 `ENC()` 值刷新后自动重放解密）；
  启动时装配的日志文件/格式与网关鉴权配置需重启，详见 [deploy/nacos/README.md](deploy/nacos/README.md)。
- **部署相关变量**：仅容器编排资源（`APP_UID` / `APP_GID` / `APP_MEM_LIMIT` / `BASE_IMAGE`）
  走 compose `.env`，见 [deploy/README.md](deploy/README.md#部署相关配置)。
- 密钥类文件（`.env`、私钥/证书）已被 `.gitignore` 忽略，禁止提交。

## 安全与合规基线

情绪日记、量表结果属于敏感个人信息，手机号、密码属于个人敏感字段。完整规则见 [docs/rules/security.md](docs/rules/security.md)，这里只列红线：

- **字段分级**：敏感个人信息字段级加密；手机号等个人敏感字段在展示与导出侧脱敏；密码只存哈希（BCrypt），不可逆、不可回填。
- **日志红线**：日志、异常、MQ 消息与 URL 不得出现情绪正文、量表结果、手机号、密码或 Token 明文；访问日志只记录方法、路径、状态码、耗时、用户 ID 与 IP。
- **密钥管理**：配置密钥以 `ENC()` 密文入库、启动时统一解密；主密钥随配置走 Nacos 优先 +
  本地 yaml 兜底（仓库仅开发哑值），Nacos 引导凭据只走启动参数；
  `.env`、证书与私钥文件不得提交。
- **访问路径**：唯一入口是网关（生产外部再经 Nginx 接入层）；管理端不直连业务库，服务之间不跨库 JOIN，也不绕过网关直连业务服务。

## 构建与 CI

```bash
mvn clean package            # 全量构建（含测试）
mvn clean package -DskipTests
mvn -o -q -DskipTests compile  # 快速编译检查（需先联网完整构建过一次，依赖已入本地仓库；不替代测试）
```

CI（`.github/workflows/ci.yml`）：

1. **detect**：变更分类（docs-only / deploy 变更检测）；
2. **gates**：变更分类一致性（SCOPE）、静态规则、任务包 front matter（DOC-04）与 diff 大小（SCOPE-02）门禁；
3. **backend**：JDK 21（Temurin），全量执行 `mvn -B -ntp verify`；
4. **frontend**：Node 22，`npm ci` + `npm run build`（含 `vue-tsc` 类型检查）；
5. **ops-config**：deploy/** 变更时校验 compose 文件语法；
6. **secret-scan**：gitleaks 密钥扫描。

本地验证直接用 Maven 与 npm 命令（见 [测试](#测试)）。现有 CI 只做验证与密钥扫描，不构建或推送镜像，也不代表已有生产发布流水线。

## 日志规范

完整约束见 [docs/rules/code-style.md](docs/rules/code-style.md) CODE-07；以下为摘要，目录、轮转与容器查看见 [deploy/dev-deploy.md](deploy/dev-deploy.md)。

- 控制台与文件双写，文件名 `<spring.application.name>.log`；
- 单文件 100MB、按天归档保留 30 个、总量上限 5GB（`logging.logback.rollingpolicy.*`，Nacos 可覆盖调整）；
- 生产经 Nacos 配置 `logging.structured.format.*: logstash` 输出 JSON（含 service / env / traceId，业务服务另含 userId）；
- 访问日志记录方法、路径、状态码、耗时、用户 ID、IP，**不记录** query / 请求体 / 响应体；
  网关启动后切换为 Logback `AsyncAppender`，避免在 Netty 事件循环同步写文件。

## 测试

- 后端：JUnit 5 + Mockito，不依赖真实 MySQL/Redis/Nacos。当前共 34 个测试类：
  common 13 个（`TraceIdUtils`、`RedisUtil`、`MessagePublisher`、JWT 签发解析与启动校验、配置密钥加解密与明文守卫、访问日志、TraceId 透传、全局异常等）、
  网关 14 个（三条鉴权链、白名单匹配、jti 黑名单、CORS/Admin 启动校验、错误处理、访问日志、TraceId 透传、
  请求体限制、真实 IP 解析、异步日志）与 auth-service 7 个测试类
  （控制器契约、注册幂等、登录失败计数、令牌工厂、刷新轮换、登出吊销、服务编排）；
  其余业务服务的测试随实现补充。
- 前端：`npm run type-check`（vue-tsc）。
- 运行：
  - 单模块及其依赖：`mvn -B -ntp -pl <module> -am verify`
  - 后端全量：`mvn -B -ntp verify`
  - 前端：`cd atifah-admin-web/frontend && npm run type-check`

## 文档与契约

| 文档 | 内容 |
| --- | --- |
| [docs/rules/](docs/rules/security.md) | 安全、编码、数据库、Git 细则 |
| [docs/contracts/](docs/contracts/api-conventions.md) | 接口总纲、OpenAPI 与 DB 契约（当前仅 auth-service） |
| [docs/architecture.md](docs/architecture.md) | 总体架构、容量假设、核心链路 |
| [docs/domain.md](docs/domain.md) | 业务域与服务边界 |
| [docs/db.md](docs/db.md) | 表模型与索引 |
| [deploy/dev-deploy.md](deploy/dev-deploy.md) | Windows 11 本地环境从零搭建 |

## 路线图

- [x] auth-service 注册 / 登录 / 刷新 / 登出（含幂等、失败计数与令牌轮换）
- [ ] 其余 8 个业务服务的领域建模与接口实现（mood / community / growth 为高并发重点：
      打卡热点、树洞匿名、签到积分；每个服务先补契约再实现）
- [ ] 管理后台 BFF（atifah-admin）业务接口与管理端独立鉴权
- [x] 网关默认开启 JWT 强制鉴权，并将内部服务与业务入口隔离
- [ ] Sentinel 限流规则持久化与生产策略
- [ ] growth-service 启用 Seata 分布式事务
- [ ] 移动端 App（atifah-app，规划 Flutter + Riverpod + 本地离线队列 + 幂等键同步，见 [atifah-app/README.md](atifah-app/README.md)）

---

更多文档请查看： [本地部署手册](deploy/dev-deploy.md) · [Nacos 配置导入](deploy/nacos/README.md) · [App 规划](atifah-app/README.md)

模块文档：[公共组件](atifah-common/README.md) · [API 网关](atifah-gateway/README.md) · [认证服务](atifah-services/auth-service/README.md) · [管理后台](atifah-admin-web/README.md) · [部署资产](deploy/README.md)
