# 生产环境部署说明（Docker）

本文档给出生产环境的 Docker 部署基线。**只使用 Docker**，不使用 JVM 直跑。
仓库提供启动参数基线模板 [compose-template.yml](prod/compose-template.yml)
（`container,prod` + RS256 密钥挂载 + 占位变量防呆），实际编排清单与云资源仍由部署方
维护（见 [architecture.md](../docs/architecture.md)）；本文的配置、参数与检查项与部署平台无关。

| 项 | 值 |
| --- | --- |
| 启动 profile | `container,prod`（必须显式声明） |
| Nacos namespace | `atifah_prod`（示例，以部署资产为准） |
| 对外入口 | `443/TLS` → Nginx/负载均衡 → 网关 `8080` |
| 管理端口 | `9090` 仅绑定回环或受控运维网络 |
| 数据 | 真实数据，按 SEC-01 分级处理 |

> 仅带 `container` 时服务按 fail-closed 拒绝启动。`prod` 同时触发：强制 RS256、
> CORS 收敛、拒绝开发哑主密钥、管理端强密钥。

## 1. 前置条件

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| 主机 | Linux x86_64 | 多副本，跨可用区部署 |
| Docker | 24+ | 含 Compose v2，或使用 K8s |
| JDK / Maven | 21 / 3.9+ | 仅 CI 构建使用，运行镜像只含 JRE |
| Node.js | `^20.19.0 \|\| >=22.12.0` | 仅前端构建 |
| 中间件 | MySQL 8.0 / Redis 7 / Nacos 3 / RocketMQ 5 | 生产规格、独立部署、开启鉴权 |
| 镜像仓库 | 私有 Registry | 版本化 tag，禁止使用 `latest` |

## 2. 中间件与数据库

1. MySQL：执行 [sql/01-init-databases.sql](sql/01-init-databases.sql) 创建 10 个 `atifah_*` 库；
   生产使用**最小权限账号**，禁止 root 连接业务库。
2. Nacos 使用 MySQL 持久化时执行 [sql/02-nacos-config.sql](sql/02-nacos-config.sql)。
3. Redis 必须开启口令；MySQL、Redis、Nacos、RocketMQ 仅在受控网络内可达。
4. 建立备份策略并完成恢复演练；敏感数据导出需授权与审计。

## 3. Nacos 生产配置

| Data ID | Group | 必须包含 |
| --- | --- | --- |
| `common-application.yaml` | `ATIFAH_PUBLIC_GROUP` | `atifah.common.config-loaded=true`；真实主密钥；Redis 口令（`ENC()`）；生产连接地址；日志 JSON 结构化 |
| `atifah-gateway.yaml` | `ATIFAH_GATEWAY_GROUP` | 真实主密钥；RS256 + 公钥路径；CORS 具体域名；`admin-auth.secret`（`ENC()`，≥32 字节）；黑名单策略；鉴权开关保持 `true` |
| `${service}.yaml` | `ATIFAH_<SERVICE>_GROUP` | 按需的服务私有覆盖；禁止下发签名私钥 |

- 主密钥、Redis 口令、数据库口令一律 `ENC()` 密文，使用生产主密钥加密。
- 公共配置不得包含 JWT 签名密钥。
- Nacos 客户端凭据只经启动参数注入，不写入配置文件。

## 4. 密钥与证书

| 密钥 | 位置 | 要求 |
| --- | --- | --- |
| 主密钥 `atifah.crypto.master-key` | Nacos 生产公共配置 | 32 字节 Base64，独立于本地/测试；禁用仓库哑值 |
| 数据库 / Redis 口令 | 配置项 `ENC()` | 生产主密钥加密 |
| JWT 私钥（PKCS#8） | auth-service 部署机文件，权限 `600` | 仅 auth-service 持有 |
| JWT 公钥（X.509） | 网关部署机文件 | 仅网关持有 |
| 管理端密钥 | Nacos 网关私有配置 `ENC()` | ≥32 字节，独立于 C 端 |
| TLS 证书 | Nginx/负载均衡 | 有效期内，启用 HSTS |

RSA 密钥生成与加密工具：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem

java -cp atifah-common/common-security/target/classes com.atifah.common.security.secret.SecretTool genkey
java -cp atifah-common/common-security/target/classes com.atifah.common.security.secret.SecretTool encrypt <主密钥Base64> <明文>
```

## 5. 构建镜像

```bash
mvn -B -ntp verify
mvn clean package -DskipTests

docker build -t <registry>/atifah/gateway:<version>       atifah-gateway
docker build -t <registry>/atifah/auth-service:<version>  atifah-services/auth-service
docker build -t <registry>/atifah/admin-service:<version> atifah-admin-web/backend
# ...其余业务服务同理
docker push <registry>/atifah/<service>:<version>
```

- 镜像只打包构建产物，禁止在容器内执行 Maven。
- 发布前完成镜像漏洞扫描；同一版本只构建一次，测试与生产使用同一镜像。
- 镜像发布可走 GitHub Actions **Release**（`.github/workflows/release.yml`，手动触发推送 GHCR），
  自建 Registry 按本节命令手动构建推送。

## 6. Docker 部署

启动参数基线：

```bash
--spring.profiles.active=container,prod
--spring.cloud.nacos.discovery.namespace=<prod-namespace>
--spring.cloud.nacos.config.namespace=<prod-namespace>
--spring.cloud.nacos.discovery.username=<user>
--spring.cloud.nacos.discovery.password=<password>
--spring.cloud.nacos.config.username=<user>
--spring.cloud.nacos.config.password=<password>
# 网关：algorithm=RS256、usage=VERIFIER、public-key-location=<公钥文件>
# auth-service：algorithm=RS256、usage=SIGNER、private-key-location=<私钥文件>
```

- 启动顺序：中间件就绪 → `auth-service` → 其他业务服务 → `gateway` → `admin-service`。
- 滚动更新：逐实例替换，健康检查通过后再继续；网关最后承接流量。
- 资源：按压测结果设置 CPU/内存与 JVM `-XX:MaxRAMPercentage`；配置就绪/存活探针。
- 日志：输出到文件或采集侧，生产启用 JSON 结构化，保留期按合规要求。
- 禁止把密钥、口令写入镜像、环境变量或编排明文。

## 7. 网络与入口

1. `443/TLS 1.3` → Nginx/负载均衡 → 网关 `8080`；HTTP 强制跳转 HTTPS + HSTS。
2. 仅网关 `8080` 对入口层暴露；`8081–8090` 只在容器内网可达，禁止发布到宿主机或公网。
3. `9090` 仅运维网络可达，`/actuator/**` 不对外。
4. 禁止绕过网关直连业务服务；管理端不得直连业务库。
5. CORS 必须配置为具体域名，禁止 `*`。

## 8. 发布验证清单

| 检查项 | 期望 |
| --- | --- |
| 启动日志 | 无 `IllegalStateException`；生产护栏校验通过 |
| 环境声明 | `container,prod`；Nacos 指向生产 namespace |
| 健康检查 | 全服务 `UP` |
| 鉴权 | 未带 Token 返回 `40100`；401 响应 `Cache-Control: no-store` |
| JWT | 网关只持公钥、auth-service 只持私钥；HS256 密钥未出现在生产配置 |
| 主密钥 | 非仓库哑值；`ENC()` 全部可解密 |
| CORS | 仅放行生产域名 |
| 管理端密钥 | 非默认值、≥32 字节 |
| 端口 | 除入口层与受控管理端口外无对外监听 |
| 日志脱敏 | 抽查无手机号、密码、情绪正文 |
| Nacos | 生产 namespace 实例健康，无 `atifah_dev` 混入 |
| 黑名单策略 | 显式选择 fail-closed，或 fail-open 且已配置告警 |

## 9. 回滚

1. 镜像回退到上一版本 tag，按相反顺序滚动：`gateway` → `admin-service` → 业务服务。
2. Nacos 配置回滚到上一发布版本，重启相关服务。
3. Flyway 迁移只前进不后退；回滚代码前确认旧版本兼容已执行的结构变更。
4. 回滚后重新执行第 8 节清单。

## 10. 已知缺口（发布前须知）

- 管理端独立鉴权（登录、MFA、IP 白名单、RBAC）尚未实现，`/admin/api/**` 不得对公网开放。
- Access Token 即时撤销（auth-service 登出写 `auth:token:<jti>`）尚未实现，依赖 ≤15 分钟短 TTL。
- 除 auth-service 外的 8 个业务服务仍为骨架，对应 `/api/v1/**` 路由暂不可用。
- 如需预发/UAT 环境：与生产同构，使用独立 namespace 与独立密钥，profile 使用 `prod` 或未登记的
  其它 profile（均按生产语义处理）。
