# Nacos 配置

本目录存放配置中心（Nacos）的**源文件**，便于版本管理。运行前需将其发布到 Nacos。

## 命名空间

**运行环境由 namespace 区分**：服务连接哪个 namespace，就使用哪套配置。命名空间 ID 与显示名一致；
客户端只认 **ID**。本地默认值写在各服务本地 `application.yml`（`namespace: atifah_dev`），
生产由部署资产（部署清单/打包配置）指向生产 namespace：

| namespace | 环境 | 注入方式 |
|---|---|---|
| `atifah_dev` | 本地开发 / 容器沙箱 | 本地 `application.yml` 默认值，无需设置 |
| `atifah_prod`（示例） | 生产 | 生产部署资产写入 `--spring.cloud.nacos.discovery.namespace=... --spring.cloud.nacos.config.namespace=...` |

默认命名空间 `public` 不放业务配置，保持为空。其他环境如需隔离，可自建 namespace 并在对应部署资产注入。
发布配置时 v3 API 带 `namespaceId` 参数，控制台先在顶部切换到目标命名空间。

## Group 划分

每个 namespace 内的配置按公共/私有拆分 group：

- 所有配置（公共与私有）的**归属应用**统一为 `atifah`。
- 公共配置统一放 **`ATIFAH_PUBLIC_GROUP`**（当前仅 `common-application.yaml`）。
- 各微服务私有配置放**各自的 group**：`ATIFAH_<服务名大写>_GROUP`，如 `ATIFAH_AUTH_GROUP`、`ATIFAH_MOOD_GROUP`、`ATIFAH_GATEWAY_GROUP`、`ATIFAH_ADMIN_GROUP`。
- 服务注册发现的 group 与配置 group 无关，统一保持 `atifah_GROUP`；所有服务必须同组，网关 `lb://` 路由才能发现实例，不要改动。

## 客户端鉴权

各服务从 `spring.cloud.nacos.{discovery,config}.username/password` 读取客户端凭据，本地默认为空
（本地 compose 已关闭鉴权，留空即可）。如后续开启鉴权，凭据属于"连接 Nacos 的引导凭据"（解密器
要先连上 Nacos 才能拉到配置，不走 `ENC()` 加密链路），必须经启动参数注入专用账号（最小权限，
不复用 `nacos` 管理员），禁止写入任何入库文件；缺失时服务注册与配置拉取会被 403 拒绝。

## 导入顺序与优先级

生效优先级（高 → 低）：

```text
启动参数  >  Nacos 导入（同一 import 列表内后导入者优先，故 私有 > 公共）  >  本地 application.yml 与 application-{profile}.yaml
```

- **启动参数最高**：Nacos 引导凭据与生产 namespace 等只允许经部署资产（启动参数/部署清单）注入，
  不属于任何配置文件；容器镜像 ENTRYPOINT 已内置 `container` profile，生产部署资产写入
  `--spring.profiles.active=prod` 触发生产安全语义（RS256、CORS 收敛、拒绝开发哑主密钥）。
- **Nacos 导入高于本地 `application.yml`**：`config data` 的导入项优先于声明导入的文件本身。因此本地
  `application.yml` 里的端口、数据源、生产者组、主密钥等只是**默认值兜底**，Nacos 一旦发布同名 key
  即被覆盖；生产地址、密钥与主密钥在 Nacos 生产命名空间（如 `atifah_prod`）覆盖下发。
- **私有配置高于公共配置**：各服务 `spring.config.import` 先导入公共配置、再导入私有配置，后导入者优先，
  故私有 group 可覆盖 `ATIFAH_PUBLIC_GROUP` 的同名项。
- **profile 配置与主文件同层**：`application-container.yaml` 按激活 profile 叠加在 `application.yml`
  之上，用于容器沙箱的中间件地址差异；生产差异统一走 Nacos，不再新增 profile 文件。

其中 `common-application.yaml` 为**必选导入**（不带 `optional:`）：Nacos 不可达或未发布该配置时服务
直接启动失败；各服务私有配置仍为可选（`optional:`），未发布时以本地默认值兜底。

## 自动刷新

各服务与网关的 `spring.config.import` 均带 `refreshEnabled=true`，Nacos 配置变更会触发 Spring Cloud
刷新事件并重新绑定 `@ConfigurationProperties`；是否即时生效取决于使用方是否在运行时读取配置。

- `logging.level.*`：可通过刷新事件在运行中生效。
- 启动时装配、修改后需重启：`logging.file.*`、`logging.pattern.*`、`logging.structured.*`、
  `logging.logback.rollingpolicy.*`；网关 `atifah.gateway.auth.enabled` 与
  `rules`；其他在 Bean 构造时捕获的配置。
- **`ENC()` 密文刷新**：Nacos 动态刷新会整体替换属性源，新属性源中的 `ENC()` 密文由
  common-security 的 `SecretRefreshListener`（最高优先级）在重绑定前重放解密，组件拿到的仍是明文。
- 无法确认时以重启为准；不要假设所有配置都能热更新。

## 配置清单

| data-id | Group | 用途 |
|---|---|---|
| `common-application.yaml` | `ATIFAH_PUBLIC_GROUP` | 10 个业务服务共享配置（Redis/MyBatis-Plus/Jackson/Sentinel/OpenFeign/RocketMQ/Actuator/日志 + 主密钥 `atifah.crypto.master-key`），不下发 JWT 密钥；密钥类（如 Redis 口令）以 `ENC()` 密文存放 |
| `atifah-gateway.yaml` | `ATIFAH_GATEWAY_GROUP` | 网关私有配置（鉴权开关/免鉴权规则 + 网关主密钥覆盖；网关不导入公共配置，生产主密钥在此下发；`auth.enabled` 与 `rules` 在启动时装配，修改后需重启网关） |
| `atifah-gateway-gw-flow.json` / `atifah-gateway-degrade.json` | `ATIFAH_GATEWAY_GROUP` | 网关 Sentinel 限流/熔断规则（已入库的规划资产，尚未接入 datasource，导入后生效） |
| `${spring.application.name}.yaml` | 各服务自己的 `ATIFAH_<服务名大写>_GROUP` | 各服务私有覆盖配置（可选，用于按服务覆盖共享项） |

容器沙箱地址（compose 服务名）不进 Nacos：由各模块本地 `application-container.yaml` 承载
（镜像/命令已激活 container；本地/沙箱必须同时激活 local，生产改为 prod，仅带 container 会被安全护栏拒绝启动）。

## 发布方式

> Nacos 3.x 已移除 v1/v2 Admin API，下面的示例统一使用 v3 接口；v1 示例在 3.x 会直接 404。
> 示例以 `atifah_dev` 为例，发其他环境替换 `namespaceId` 即可。

**方式一：Nacos 控制台**（本地 <http://localhost:8849/>，控制台在根路径，不是 `/nacos`）

本地 compose 通过 `NACOS_AUTH_ADMIN_ENABLE=false` 关闭了运维 API 鉴权，可直接操作；
开启运维 API 鉴权的环境需先用管理员账号 `nacos` 登录。

控制台顶部命名空间切到 `atifah_dev`，依次新建配置：
- Data ID：`common-application.yaml`
- Group：`ATIFAH_PUBLIC_GROUP`
- 归属应用：`atifah`
- 格式：YAML
- 内容：`config/common-application.yaml` 全文

各服务私有配置按需发布：Data ID `${spring.application.name}.yaml`，Group 用该服务自己的组，归属应用同为 `atifah`。

**方式二：OpenAPI 脚本**（需先启动 Nacos，并在 `deploy/docker-compose/.env` 配好 `NACOS_AUTH_TOKEN`）

```bash
curl -X POST "http://127.0.0.1:8848/nacos/v3/admin/cs/config" \
  --data-urlencode "namespaceId=atifah_dev" \
  --data-urlencode "dataId=common-application.yaml" \
  --data-urlencode "groupName=ATIFAH_PUBLIC_GROUP" \
  --data-urlencode "appName=atifah" \
  --data-urlencode "type=yaml" \
  --data-urlencode "content@config/common-application.yaml"
```

注意 v3 参数名是 `groupName`（v1 为 `group`）。鉴权开启时请求还需带 `accessToken`，
本地 compose 已关闭运维 API 鉴权，可直接调用。

> **Windows 下含中文的参数禁止内联**（如 `namespaceDesc=本地开发环境`）：终端会按 GBK 发出导致乱码入库
> （且不可逆）。中文值先写入 UTF-8 文件再传：`--data-urlencode "namespaceDesc@desc.txt"`，
> curl 读不到 `/tmp/...` 路径时用 `cygpath -w` 转 Windows 路径。ASCII 参数不受影响。

## 本地开发

```bash
docker compose -f deploy/docker-compose/docker-compose.yml up -d nacos
```

配置持久化到 MySQL（库 `nacos_config`，与中间件同实例；表结构见 `deploy/sql/02-nacos-config.sql`），
nacos 容器重建后配置不丢失。注意：

- 已有 mysql-data 卷的环境首次切换时需手动执行一次该 SQL 建库建表：
  `docker exec -i atifah-mysql mysql -uroot -proot < deploy/sql/02-nacos-config.sql`
- 从 Derby 切到 MySQL 属于更换存储，旧配置不会自动迁移，需重建命名空间并重新发布配置。
- `NACOS_CONFIG_NAMESPACE_COMPATIBLE_MODE=false` 关闭 2.x→3.x 兼容迁移：Nacos 3.0.3 的迁移预检
  SQL 在 Derby 上触发 bad grammar 会导致启动失败（全新 MySQL 库无 2.x 遗留数据，无需迁移）。

发布 `common-application.yaml`（命名空间 `atifah_dev`）后再启动各服务。各服务通过
`spring.config.import: nacos:common-application.yaml?group=ATIFAH_PUBLIC_GROUP` **必选**引入，
未发布时服务直接启动失败，故本地开发务必先发布。
