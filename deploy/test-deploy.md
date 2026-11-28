# 测试 / 沙箱环境部署说明（Docker）

本文档说明测试 / 沙箱环境的 Docker 部署方式。与本地开发（[dev-deploy.md](dev-deploy.md)）不同：
本环境**只使用 Docker**，中间件、数据库、Nacos 均为独立实例，不与本地或生产共用。

| 项 | 值 |
| --- | --- |
| 启动 profile | `container,dev` |
| Nacos namespace | 自建，示例 `atifah_test` |
| Nacos 分组 | 发现 `atifah_GROUP`；公共配置 `ATIFAH_PUBLIC_GROUP`；服务私有 `ATIFAH_<SERVICE>_GROUP` |
| 对外入口 | Nginx/负载均衡 → 网关 `8080`；管理端口 `9090` 仅内网 |
| 数据 | 合成或脱敏数据，禁止导入真实用户数据 |

> 不要使用 `test` profile 部署：它只用于自动化测试，会跳过配置密钥解密与公共配置装载校验。
> 容器内必须显式声明 `dev`，仅带 `container` 会因环境歧义拒绝启动。

## 1. 前置条件

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| 主机 | Linux x86_64 | 建议 ≥8C/16G，按服务数量调整 |
| Docker | 24+ | 含 Compose v2 |
| JDK / Maven | 21 / 3.9+ | 构建机或 CI 使用 |
| Node.js | `^20.19.0 \|\| >=22.12.0` | 仅管理后台前端构建 |
| 中间件 | MySQL 8.0 / Redis 7 / Nacos 3 / RocketMQ 5 | 独立实例，地址由部署资产提供 |
| 镜像仓库 | 私有 Registry | 存放构建产物镜像 |

## 2. 中间件与数据库

测试环境不使用 `deploy/docker-compose/docker-compose.yml`（该文件面向本地开发）。
中间件由部署资产独立维护，要求：

1. MySQL 初始化：执行 [sql/01-init-databases.sql](sql/01-init-databases.sql) 创建 10 个 `atifah_*` 库。
2. Nacos 若使用 MySQL 持久化：执行 [sql/02-nacos-config.sql](sql/02-nacos-config.sql)。
3. Redis 建议开启口令；口令以 `ENC()` 密文写入配置。
4. RocketMQ Topic / 消费组按 `MqTopics` / `MqConsumerGroups` 预先创建。

## 3. 导入 Nacos 配置

1. 创建 namespace（示例 `atifah_test`，命名空间 ID 与显示名一致，客户端只认 ID）。
2. 发布公共配置 `common-application.yaml` 到 `ATIFAH_PUBLIC_GROUP`，至少包含：

```yaml
atifah:
  common:
    config-loaded: true          # 缺失时服务拒绝启动
  crypto:
    master-key: <本环境独立主密钥 Base64>
```

3. 本环境的 Redis 口令、数据库口令等密钥键一律 `ENC()` 密文，用本环境主密钥加密。
4. 网关私有配置 `atifah-gateway.yaml`（`ATIFAH_GATEWAY_GROUP`）：鉴权开关保持 `true`、
   管理端密钥 `ENC()` 密文、CORS 建议收敛为测试域名。
5. 公共配置中不放 JWT 签名密钥；JWT Provider 只装配在网关与 auth-service。

主密钥与密文生成：

```bash
java -cp atifah-common/common-security/target/classes com.atifah.common.security.secret.SecretTool genkey
java -cp atifah-common/common-security/target/classes com.atifah.common.security.secret.SecretTool encrypt <主密钥Base64> <明文>
```

## 4. 构建镜像

在 CI 或构建机执行，禁止把本机临时打包的 jar 直接上测试环境：

```bash
mvn -B -ntp verify
mvn clean package -DskipTests

# 逐模块构建（构建上下文为模块目录），tag 使用版本号
docker build -t <registry>/atifah/auth-service:<version> atifah-services/auth-service
docker build -t <registry>/atifah/gateway:<version>     atifah-gateway
docker build -t <registry>/atifah/admin-service:<version> atifah-admin-web/backend
# ...其余业务服务同理

docker push <registry>/atifah/<service>:<version>
```

## 5. Docker 部署

编排文件由部署资产维护（K8s / ECS / Compose 均可），容器启动参数必须包含：

```bash
--spring.profiles.active=container,dev
--spring.cloud.nacos.discovery.namespace=atifah_test
--spring.cloud.nacos.config.namespace=atifah_test
# Nacos 开启鉴权时再追加：
# --spring.cloud.nacos.discovery.username=<user>
# --spring.cloud.nacos.discovery.password=<password>
# --spring.cloud.nacos.config.username=<user>
# --spring.cloud.nacos.config.password=<password>
```

Compose 形态的启动参数示例：

```yaml
services:
  auth-service:
    image: <registry>/atifah/auth-service:<version>
    command: ["--spring.profiles.active=container,dev",
              "--spring.cloud.nacos.discovery.namespace=atifah_test",
              "--spring.cloud.nacos.config.namespace=atifah_test"]
    restart: unless-stopped
    networks: [atifah-net]
```

启动顺序：中间件 → `auth-service` → 其他业务服务 → `gateway` → `admin-service`。
前端使用 `npm run build` 产物，由 Nginx 托管并反代 `/admin/api` 到网关
（配置参考 [nginx/nginx.conf](nginx/nginx.conf)）。

## 6. 健康检查与排障

```bash
curl -s http://<gateway-host>:9090/actuator/health   # 运维网络内访问
docker logs -f atifah-auth                            # 容器名以部署资产为准
```

| 现象 | 排查方向 |
| --- | --- |
| 启动即退出，提示 `profiles=container` | 启动参数缺少 `dev` |
| 提示 `common.config-loaded` 缺失 | 公共配置未发布到本 namespace 或装载失败 |
| `ENC()` 解密失败 | 主密钥与密文不匹配，或误用了其他环境主密钥 |
| 服务未注册 | Nacos 地址/namespace/凭据不正确 |
| 鉴权 401 | 检查网关 JWT 密钥与 auth-service 签发密钥是否同源 |

## 7. 回滚

1. 镜像回退到上一版本 tag，按相反顺序滚动：`gateway` → `admin-service` → 业务服务。
2. Nacos 配置使用历史版本回滚，重启受影响服务。
3. Flyway 迁移只前进不后退；涉及结构变更时按“先兼容、再清理”两阶段发布。
4. 回滚后重新执行第 6 节检查。

## 8. 安全与限制

- 网关联调数据必须脱敏；禁止真实手机号、情绪日记、量表结果。
- 管理端独立鉴权尚未接入，`/admin/api/**` 不得对公网开放。
- 网关鉴权保持开启；`8081–8090` 只在容器内网可达。
- 暴露范围限于办公网/VPN，日志与错误信息不得包含敏感明文。
