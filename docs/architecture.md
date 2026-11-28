# 情绪管理微服务项目：设计思路与技术架构

> 文档版本：v1.1（2026-11-25）
>
> 状态图例：✅ 已实现 · 🔨 骨架 · ⏳ 规划（未引入依赖，启用前需维护者确认）
>
> 技术基线：JDK 21 / Spring Boot 3.5.x / Spring Cloud 2025.0.x / Spring Cloud Alibaba 2025.0.0.0

## 0. 文档定位与实现现状

本文档描述**目标架构**与关键取舍，不代表所有能力都已落地。接口、DDL 与事件以 （contracts 文件夹下的文件）为唯一依据；
落地约束见 [`rules/`](rules/code-style.md)。

| 能力 | 状态（2026-11-25） | 说明 |
| --- | --- | --- |
| 网关：路由、JWT 三条鉴权链、Sentinel 限流、请求体限制、真实 IP、TraceId、CORS、访问日志 | ✅ 已实现 | `atifah.gateway.auth.enabled` 默认开启；生产只发布网关入口 |
| 公共模块 `atifah-common` | ✅ 已实现 | 统一响应与异常、TraceId、Redis、MyBatis-Plus/Flyway、MQ、JWT |
| `auth-service`（注册 / 登录 / 刷新 / 登出） | ✅ 已实现 | Flyway 建表、BCrypt、refresh 轮换；网关侧 `jti` 黑名单已实现，auth-service 登出写侧待实现 |
| 其余 8 个业务服务 | 🔨 骨架 | 仅启动类、Nacos 接入与数据源配置 |
| 管理后台 BFF / 前端 | 🔨 骨架 / ✅ 页面框架 | BFF 无业务接口；前端页面、路由与请求封装已就绪 |
| 部署编排、CI、日志双写 | ✅ 已实现 | 两套 Compose、GitHub Actions、控制台与文件双写 |
| 分库分表、Caffeine、XXL-Job、Seata、ES、OSS/MinIO、SkyWalking、Dubbo、灰度分流 | ⏳ 规划 | 根 `pom.xml` 未引入依赖，不得按已实现能力验收 |

> 标注「规划」的内容属于设计目标；实现前先在 `docs/contracts/` 补契约，并经维护者确认。

## 1. 设计目标与容量假设

**业务特点**

- **敏感数据**：情绪日记、量表结果属于敏感个人信息，隐私与加密优先。
- **明显写入高峰**：打卡集中在早 7–9 点、晚 21–23 点，需要削峰。
- **读多写少**：社区内容、冥想音频、文章以读为主，需要多级缓存。
- **社交与实时性**：树洞/社区涉及 Feed 流、点赞计数、实时消息。
- **合规要求高**：内容审核、危机干预、未成年人保护、数据可删除。

**容量假设**（可按比例缩容）

| 指标     | 假设       |
| ------ | -------- |
| 注册用户   | 1000 万   |
| DAU    | 100 万    |
| 峰值在线   | 20 万     |
| 打卡峰值   | 3000 QPS |
| 读峰值    | 3 万 QPS  |
| IM 长连接 | 5 万      |

**总体风格**：App 端前后端分离 + BFF；管理后台前端独立（Vue 3 SPA），后端由 admin-service 提供 BFF 接口；微服务 + 事件驱动 + 读写分离 + 多级缓存 + 最终一致。

## 2. 业务域与微服务拆分

业务域划分为账号、情绪、分析、社区、内容、消息、增长、运营八个域；对应 9 个 C 端业务服务 + `admin-service` BFF。完整的域职责、服务归属、数据边界与跨域规则见 [`domain.md`](domain.md)。

拆分原则：一个服务一个逻辑库（初期可共用一个 MySQL 实例，但 Schema 独立），禁止跨库 JOIN；跨服务同步查询走 OpenFeign，异步事件走 RocketMQ；初期允许合并部署（`auth` 并入 `user`、`growth` 并入 `user`），但代码边界与库边界保持不变。
依赖层面：`atifah-services` 父 POM 只统一提供 `common-web` / `common-mybatis` / `common-security`；`common-redis`、`common-mq` 由实际使用 Redis / RocketMQ 的服务显式引入，避免骨架服务被迫连接中间件，也避免无用自动配置。

## 3. 总体技术架构

```
┌───────────────────────────────────────────────────────────────────────┐
│ 客户端层   iOS/Android App（Flutter）   管理后台 Web（Vue 3 SPA）          │
└───────────────┬───────────────────────────────────┬───────────────────┘
                │ HTTPS                             │ HTTPS
┌───────────────▼───────────────────────────────────▼───────────────────┐
│ 接入层     CDN + 对象存储            Nginx                            │
│            TLS1.3、HTTP/2、静态缓存、WAF、IP/连接限流、灰度、安全响应头   │
└───────────────┬───────────────────────────────────┬───────────────────┘
                │                                   │
┌───────────────▼───────────────────────────────────▼───────────────────┐
│ 网关层     Spring Cloud Gateway（无状态多副本）                         │
│   /api/v1/**  C 端               /admin/api/**  管理端独立鉴权链         │
│   路由 / JWT 验签 / Sentinel 限流 / 请求体限制 / TraceId / CORS          │
└───────────────┬───────────────────────────────────────────────────────┘
                │ OpenFeign（同步查询） + RocketMQ（异步事件）+ WebSocket
┌───────────────▼───────────────────────────────────────────────────────┐
│ 业务服务层（Nacos 注册发现 + 配置中心）                                 │
│  auth   user   mood   analysis   community   content   message        │
│  growth   stats   admin(BFF)                                           │
│  公共能力：Sentinel 流控熔断（Seata、XXL-Job 为规划）                    │
└───────┬──────────────┬──────────────┬──────────────┬──────────────────┘
        │              │              │              │
┌───────▼─────┐ ┌──────▼──────┐ ┌─────▼───────┐ ┌────▼────────┐
│ MySQL 8     │ │ Redis 7     │ │ RocketMQ 5  │ │ Nacos       │
│ 主从/分库分表│ │ 缓存/锁/计数 │ │ 异步/削峰/事务│ │ 注册/配置    │
└─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘
┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
│ Sentinel    │ │ Seata       │ │ ES（可选）   │ │ OSS/MinIO   │
│ 流控/熔断    │ │ 分布式事务   │ │ 社区搜索      │ │ 语音/图片    │
└─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘
┌───────────────────────────────────────────────────────────────────────┐
│ 基础设施层  Docker + Docker Compose（开发环境）                       │
│ 可观测性：SkyWalking / Prometheus + Grafana / Loki / Alertmanager      │
└───────────────────────────────────────────────────────────────────────┘
```

> 图示为目标架构：CDN/WAF、灰度分流、分库分表、ES、OSS/MinIO、SkyWalking、Seata、XXL-Job 均属规划能力，
> 当前落地范围以 §0 为准。网关只负责鉴权、限流、请求体限制与 TraceId 透传，**幂等由业务服务按 `Idempotency-Key` 实现**。

### 技术选型

| 层次    | 选型                                                      | 理由                      |
| ----- | ------------------------------------------------------- | ----------------------- |
| 接入层   | Nginx                                                   | 静态资源托管、反向代理、限流/连接复用、WAF |
| 运行时   | JDK 21 + Spring Boot 3.5.x                              | LTS、虚拟线程、容器友好           |
| 微服务   | Spring Cloud 2025.0.x + Spring Cloud Alibaba 2025.0.0.0 | 版本通过 BOM 对齐             |
| 注册/配置 | Nacos                                                   | AP 注册中心，配置热更新、灰度开关      |
| 网关    | Spring Cloud Gateway                                    | 响应式、可编程限流，适合高并发入口       |
| 入口代理  | Nginx                                                   | TLS 终止、反向代理、静态资源与 IP 限流 |
| 管理后台  | Vue 3 + Vite + TypeScript + Element Plus                 | 前后端分离、组件生态成熟、独立构建与发布      |
| RPC   | OpenFeign（默认）/ Dubbo 3（热点链路可选）                          | 优先简单，避免双协议复杂度           |
| 流控熔断  | Sentinel                                                | 网关+服务双层防护；规则 Nacos 持久化为规划 |
| 分布式事务 | Seata AT                                                | 仅用于少量强一致场景，避免滥用         |
| ORM   | MyBatis-Plus                                            | 分页插件；SQL 统一写 XML，逻辑清晰 |
| 分库分表  | ShardingSphere-JDBC                                     | 无侵入、支持分片+读写分离           |
| 数据库   | MySQL 8.0/8.4                                           | 事务、主从、成熟运维              |
| 缓存    | Redis 7 + Redisson + Caffeine                           | 分布式缓存/锁/计数 + 本地热点缓存     |
| 消息    | RocketMQ 5.x                                            | 高吞吐、事务消息、延时消息、顺序消息、死信队列 |
| 定时任务  | XXL-Job                                                 | 周报生成、对账补偿、批量任务          |
| 文件    | OSS/MinIO 预签名直传                                         | 服务端不中转大文件               |
| 可观测   | SkyWalking + Prometheus/Grafana + Loki                  | 链路、指标、日志三件套             |
| 容器    | Docker + Docker Compose                                 | 本地开发环境                   |
| CI    | GitHub Actions                                          | 构建、测试、安全扫描               |

> **已落地 vs 规划**：Nginx、虚拟线程、MyBatis-Plus/Flyway、Redis/Redisson、RocketMQ、Sentinel、Nacos、OpenFeign、Docker/CI 已落地；
> **Dubbo、ShardingSphere、Caffeine、XXL-Job、Seata、OSS/MinIO、SkyWalking、ES** 属规划或可选组件，根 `pom.xml` 未引入依赖，启用前必须经维护者确认。

> Servlet 业务服务统一启用 `spring.threads.virtual.enabled=true`，以虚拟线程处理 I/O 密集请求；Gateway 保持响应式事件循环，不启用该配置。自定义线程池、MQ 消费线程与定时任务仍需显式配置。

## 4. 核心链路设计

> 以下链路为目标设计。当前只有 auth-service 的认证链路已实现，其余服务的实现前必须先补 `docs/contracts/` 契约。

### 4.1 心情打卡

```
App ──POST /api/v1/moods──▶ Nginx ──▶ Gateway
                                      │ ① JWT 验签、Sentinel 限流（鉴权默认开启）
                                      ▼
                                 mood-service
                                      │ ② 参数校验 + Idempotency-Key 幂等 + 唯一索引 (user_id, record_date)
                                      │ ③ 本地事务写 MySQL
                                      │ ④ 发送事件 mood-recorded（事务消息/本地消息表为规划）
                                      │ ⑤ 更新 Redis 当日状态
                                      ▼ 返回（目标 <100ms）
                                   RocketMQ
              ┌───────────────┬───────┴────────┬──────────────┐
              ▼               ▼                ▼              ▼
        stats-service   analysis-service   growth-service  message-service
        日统计聚合       连续天数/趋势      积分/任务/成就   成就通知/提醒
                         周报数据预热
```

关键点：

- **同步只做核心落库**，统计、积分、通知全部异步。
- **幂等**：由业务服务校验 `Idempotency-Key`（`CacheKeys.IDEMPOTENT`，24h）+ 数据库唯一索引双保险；网关不做去重。
- **可靠性**：事务消息 + 本地消息表 + 定时对账为规划；当前 `MessagePublisher` 只提供同步/异步发送，失败场景需业务方自行补偿或重试。
- **顺序性**：`MessagePublisher` 统一把业务键写入 RocketMQ `KEYS`（通常为 userId），保证同一用户事件顺序消费。

### 4.2 情绪分析与报告

- 打卡事件驱动 `analysis-service` 预计算日统计，趋势页直接读聚合结果。
- 周报/月报由 XXL-Job（规划）批量生成，写入 `t_user_report`，生成完成发通知。
- AI 情绪摘要异步调用模型服务，独立线程池 + 独立限流 + 超时降级，失败不影响主流程。
- 量表结果加密存储，仅用户本人和管理端脱敏视图可见。

### 4.3 社区 Feed（推拉结合）

```
发布 ──▶ community-service 落库(待审核) ──▶ MQ post-published
                                              │
                        ┌─────────────────────┼──────────────────┐
                        ▼                     ▼                  ▼
                   内容审核服务          粉丝收件箱扩散        统计/热榜
                   (机审+人审)        Redis ZSET inbox       Redis ZSET
```

- **普通用户**：写扩散，发布后推送到活跃粉丝的 `feed:inbox:{userId}` ZSET（裁剪 1000 条）。
- **大 V / 热点内容**：读扩散，读取时从作者发件箱拉取后合并。
- **推荐流**：内容池 + 时间衰减热度分，Redis ZSET 缓存，定时刷新。
- **点赞**：Lua 脚本原子判断去重 + `HINCRBY` 计数，RocketMQ 批量回写 MySQL。
- **审核**：公开内容先审后发；私密日记仅自见；审核结果通过 MQ 回写状态。

### 4.4 消息与实时通信

- 在线：`message-service` 内 WebSocket 网关推送，长连接节点通过 Redis 记录路由与在线状态。
- 离线：站内信落库 + 厂商推送通道（APNs/FCM/国内厂商或统一推送平台）。
- 频控：Redis 记录用户/设备推送频次，避免打扰。
- 提醒：RocketMQ 延时消息实现“21:00 未打卡提醒”。
- 长连接扩容：按连接数水平扩展，接入层开启粘性会话。

## 5. 高并发与一致性方案

### 5.1 分层抗压

| 层次  | 手段                                                    |
| --- | ----------------------------------------------------- |
| 接入层 | Nginx 静态资源、反代与 IP 限流；CDN/WAF/连接复用为规划 |
| 网关层 | JWT 本地验签（无状态，默认开启）、Sentinel QPS/热点参数限流；灰度分流为规划 |
| 服务层 | 全部无状态、水平扩展、线程池隔离、超时控制 |
| 缓存层 | Redis L2 + 随机 TTL；Caffeine L1、热点探测为规划 |
| 存储层 | 主从读写分离；ShardingSphere 分库分表与慢查询治理为规划 |
| 消息层 | RocketMQ 削峰、批量消费；消费限流、死信队列策略待消费端实现 |

### 5.2 缓存设计

| 场景       | 方案                                               |
| -------- | ------------------------------------------------ |
| 用户/内容详情  | Redis(30–60min 随机 TTL) + MySQL；Caffeine L1(3–10s) 为规划 |
| 今日打卡状态   | `CacheKeys.MOOD_TODAY`，过期到次日凌晨                |
| 连续打卡     | Redis Bitmap/Set，异步落库                            |
| 点赞去重     | Redis Set + Lua 原子操作                             |
| Feed 收件箱 | Redis ZSET，score=时间戳                             |
| 热门/榜单    | Redis ZSET                                       |
| 分布式锁     | Redisson（watchdog 续期，Lua 安全释放）                   |
| 接口幂等     | `CacheKeys.IDEMPOTENT`，24h                           |

**三大缓存问题**：穿透 → 空值缓存 + 布隆过滤器；击穿 → 逻辑过期/互斥重建；雪崩 → 随机 TTL + 多级缓存 + Sentinel 兜底。缓存一致性采用 Cache Aside，关键场景可加延迟双删或订阅 Binlog（Canal）刷新。

### 5.3 异步与一致性

- **同步调用**：仅用于用户可感知的查询；设置连接/读超时，禁止长调用链。
- **异步事件**：通知、统计、积分、审核、报告生成全部走 MQ。
- **事务消息 + 本地消息表 + 定时对账**（规划）：保证“打卡成功 → 加积分”最终一致。
- **Seata AT**（规划）仅用于无法异步的跨服务强一致场景（如积分兑换），启用前需维护者确认与范围约束。
- **消费幂等**：`msgId` 去重表/Redis SETNX + 业务唯一键 + 状态机。
- **顺序性**：同一用户事件使用相同 message key，消费端单线程/分段有序。

### 5.4 MQ Topic 规划

| Topic | 生产者 | 消费者 | 已登记消费组（`MqConsumerGroups`） | 用途 |
| --- | --- | --- | --- | --- |
| `mood-recorded` | mood | stats / analysis / growth | `STATS_MOOD_CONSUMER` / `ANALYSIS_MOOD_CONSUMER` / `GROWTH_MOOD_CONSUMER` | 打卡派生事件 |
| `post-published` | community | community 扩散 / audit / stats | `COMMUNITY_FANOUT_CONSUMER` | 内容发布与分发 |
| `content-audit-result` | admin / 审核 | content | `AUDIT_RESULT_CONSUMER` | 审核状态回写 |
| `notification` | 各服务 | message | `MESSAGE_NOTIFICATION_CONSUMER` | 统一通知出口 |
| `stats-event` | 各服务 | stats | —（未登记） | 埋点与运营统计 |
| `points-changed` | growth | user / stats | —（未登记） | 积分变更 |
| `user-logoff` | user | 各服务 | —（未登记） | 注销数据清理（Saga） |

Topic 与消费组必须先登记 `MqTopics` / `MqConsumerGroups` 再引用；发布只走 `MessagePublisher`。
重试 16 次 + 指数退避 → 死信队列 → 告警 + 人工补偿为**规划**的统一策略，消费端尚未实现。

### 5.5 分库分表

分库分表为**规划**能力（当前未引入 ShardingSphere，全部单库单表）。启用时：分片键必须命中查询条件；`t_mood_record`、`t_mood_diary`、`t_post`、`t_message` 按 `user_id` 哈希分片，`t_comment` 按 `post_id` 分片；统一雪花 ID，跨分片统计走 `stats-service` 聚合表。完整规则与表清单见 [`db.md`](db.md) §3。

## 6. 数据模型（核心表）

核心表清单、敏感字段加密、索引与 DDL 规范见 [`db.md`](db.md)。本节只保留两条架构约束：

- 每个服务只写自己的库；禁止跨库 JOIN，跨服务数据走 API / MQ。
- 日记正文、量表结果必须字段级加密（AES-256-GCM），密钥由 KMS/Vault 管理；日志禁止打印正文；备份加密存储。

> 现状：已实现的只有 `auth-service` 的 BCrypt 密码哈希与手机号展示脱敏；字段级加密组件尚未引入，
> 需在 mood / analysis 服务实现前补齐，并按 SEC-01 单独评审。

## 7. 安全与隐私合规

1. **认证授权**：JWT + 不透明 Refresh Token（哈希落库、轮换与撤销）；Access Token 短时有效；网关侧已实现 `jti` 黑名单查询，auth-service 登出写黑名单待实现。三条网关鉴权链默认开启；管理端独立密钥属性已预留（默认占位值，生产必须外部注入），MFA、IP 白名单与完整 OAuth2.1 为规划。
2. **传输与存储**：全站 HTTPS/TLS1.3、HSTS；密码只存 BCrypt 哈希（当前实现；Argon2id 为可选演进）；日记正文、量表结果字段级加密（规划，见 §6）；手机号明文存储但接口与日志必须脱敏；数据库最小权限账号。
3. **数据最小化**：只收集必要字段；默认私密；用户可导出、可注销、可要求删除；注销走 Saga 清理各服务数据。
4. **内容安全**：文本敏感词 + 模型审核、语音转写审核、图片审核；公开内容先审后发；举报闭环。
5. **危机干预**：识别自伤/自杀等高风险表达，分级处理——低风险展示关怀内容，中风险展示心理援助资源，高风险进入人工干预流程（操作留审计、严格授权）。
6. **未成年人保护**：未成年人模式、内容分级、时长提醒；不满 14 周岁需监护人同意。
7. **后台安全**：RBAC + 数据权限 + 全操作审计；匿名帖反查需专项授权并留痕。
8. **防刷对抗**：设备指纹、验证码、UID/IP 频控、行为风控、文件类型与大小校验、SSRF 白名单。

## 8. 部署架构（开发环境）

开发环境提供两套后端运行方案，中间件、Nacos 配置与构建产物共用；两套方案端口一致，不可同时启动：

| 方案 | 后端运行方式 | 适用场景 |
| --- | --- | --- |
| A. JVM（默认） | 宿主机 `java -jar` / IDEA | 日常开发、断点调试 |
| B. Docker | 后端容器（`deploy/docker-compose/docker-compose.app.yml`） | 镜像验证、环境一致性联调 |

```
宿主机
├── Docker Compose（deploy/docker-compose/docker-compose.yml，网络 atifah-net）
│   ├── MySQL 8（3306，初始化各服务 Schema）
│   ├── Redis 7（6379）
│   ├── Nacos 3（8848，控制台 8849，配置持久化到 MySQL）
│   ├── RocketMQ（NameServer 9876 / Broker 10911；Dashboard 8180，--profile ops）
│   ├── Sentinel Dashboard（8858，--profile ops）
│   ├── Seata Server（8091，--profile seata，可选）
│   └── Nginx（80，--profile nginx，可选：本地静态托管验证）
├── 方案 A：atifah-gateway / 业务服务（宿主机 JVM，8080–8090；本机开发调试）
│   方案 B：网关与业务服务容器（仅 gateway 发布 8080/9090，其余服务只在 Docker 内网）
└── atifah-admin-web/frontend（Vite，5173，代理 /admin/api → 网关）
```

- 启动中间件：`docker compose -f deploy/docker-compose/docker-compose.yml up -d`；控制台组件用 `--profile ops` 追加。
- `auth-service` 首次启动由 Flyway 自动建表（`src/main/resources/db/migration`），无需手工执行 DDL；新服务沿用同一约定。
- 方案 A：`mvn clean package -DskipTests` 后 IDE 运行或 `java -jar` 启动网关与业务服务。
- 方案 B：同样先构建 jar，再通过 `--env-file deploy/docker-compose/.env` 和
  `-f deploy/docker-compose/docker-compose.app.yml` 启动；运行环境由 Nacos namespace 区分，容器镜像已内置 `container` profile；
  镜像由各模块目录下的 `Dockerfile` 从本模块 `target/*.jar` 构建，容器经 `atifah-net` 访问中间件。
  只有 gateway 发布 `8080`，管理端点 `9090` 仅绑定宿主机回环；业务容器端口不得发布到宿主机；
  单服务独立部署用各模块自己的 `docker-compose.yml`，统一 compose 复用同一批 Dockerfile。
- 启动前端：`cd atifah-admin-web/frontend && npm run dev`。
- Nacos 共享配置发布到 `atifah_dev`；未发布时业务服务拒绝启动。
- 完整步骤、端口与排障见 [`deploy/dev-deploy.md`](../deploy/dev-deploy.md)。

**本地资源建议**：

| 场景 | 建议配置 | 说明 |
| --- | --- | --- |
| 方案 A（JVM） | 8 vCPU / 16 GB / 200 GB SSD | 全部中间件 + 按需启动服务进程；12 vCPU / 32 GB 更佳 |
| 方案 B（Docker） | 12 vCPU / 32 GB / 200 GB SSD | 单个后端容器默认内存上限 1g，全部 11 个容器上限合计约 11GB，建议按需启动 |

> 生产环境部署清单与云资源不在当前仓库范围；需要时另立 Ops 变更。

## 9. 可观测性与非功能目标

| 指标             | 目标               |
| -------------- | ---------------- |
| 可用性            | 99.95%           |
| 核心读接口 P99      | ≤ 200ms          |
| 核心写接口 P99      | ≤ 300ms          |
| 打卡成功率          | ≥ 99.9%          |
| 核心 MQ 消费延迟 P99 | < 1s（统计类 < 5s）   |
| RPO / RTO      | ≤ 5min / ≤ 30min |

- **链路**：Gateway 生成/校验 `X-Trace-Id`（32 位十六进制），经 MDC、OpenFeign、MQ 消息属性贯穿；SkyWalking 仍为 P4 可选项，启用前经维护者确认。
- **指标**：JVM、QPS/RT/错误率、Sentinel 指标、MQ 堆积、Redis 命中率、MySQL 慢查询。
- **日志**：一请求一行访问日志（网关 + 各服务），不记录 body/query/Token；本地文本、生产 JSON（Boot 原生 `logstash` 格式，经 Nacos 配置 `logging.structured.format.*: logstash`）同时输出 stdout 与文件：stdout 供容器/Loki 采集，文件按服务名落盘并轮转：单文件 100MB、按天归档保留 30 个、总量 5GB 上限（`logging.logback.rollingpolicy.*`，Nacos 可覆盖调整）。日志级别、字段、敏感数据与异步传递约束见 [`rules/code-style.md`](rules/code-style.md) CODE-07，部署与查看见 [`../deploy/dev-deploy.md`](../deploy/dev-deploy.md) §6。
- **告警**：错误率、P99 超阈值、MQ 堆积、消费失败、DB 主从延迟、证书到期。

## 10. 演进路线

- **P0（1–2 周）✅ 已完成**：工程骨架、Nacos/Gateway/公共模块、docker-compose、CI。
- **P1（2–3 周）🔄 进行中**：auth 已完成；user/mood/content 核心闭环与管理后台 MVP 待做。
- **P2（2–3 周）⏳ 未开始**：社区、消息推送、增长模块、MQ 事件体系。
- **P3（1–2 周）⏳ 未开始**：多级缓存、Feed 推拉、点赞计数、分库分表、限流降级专项。
- **P4（2 周）⏳ 未开始**：可观测性、全链路压测、安全与隐私加固。

**关键取舍**：不要一开始就追求“全量微服务”，低流量服务允许合并部署；强一致范围最小化，优先最终一致 + 对账；缓存和 MQ 必须配套幂等、补偿和监控；情绪数据隐私的优先级高于任何性能优化。

## 11. 相关文档与契约

| 文档 | 用途 |
| --- | --- |
| [`domain.md`](domain.md) / [`db.md`](db.md) | 业务域与服务边界 / 数据模型、加密与分片 |
| [`rules/`](rules/code-style.md) | 安全、编码、数据库、Git 细则 |
| [`../deploy/dev-deploy.md`](../deploy/dev-deploy.md) | Windows 11 本地部署与排障 |
