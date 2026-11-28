# deploy

本地与容器化部署资产。

| 目录 | 内容 |
| --- | --- |
| `docker-compose/` | 中间件编排 + 后端全量编排（`docker-compose.app.yml`） |
| `nacos/config/` | Nacos 公共配置 `common-application.yaml` |
| `sql/` | MySQL 初始化（10 个库）+ Nacos 持久化表结构 |
| `nginx/` | 管理后台前端静态托管 + 反代配置 |
| `dev-deploy.md` | Windows 11 本地开发部署完整手册（JVM 或 Docker） |
| `test-deploy.md` | 测试 / 沙箱环境部署（Docker） |
| `prod-deploy.md` | 生产环境部署基线（Docker） |

## 中间件与环境依赖

一键编排：`deploy/docker-compose/docker-compose.yml`（网络名固定 `atifah-net`）

| 组件 | 版本 | 地址（本地开发） |
| --- | --- | --- |
| MySQL | 8.0.39 | `127.0.0.1:3306`（root/root，首次启动自动建 10 个 `atifah_*` 库） |
| Redis | 7.4 | `127.0.0.1:6379`（无密码） |
| Nacos | v3.0.3 | 注册/配置 `127.0.0.1:8848`，控制台 <http://localhost:8849>（配置持久化到 MySQL `nacos_config` 库） |
| RocketMQ | 5.3.1 | NameServer `9876`；Dashboard <http://localhost:8180>（`--profile ops`） |
| Sentinel Dashboard | 1.8.8 | <http://localhost:8858>（`--profile ops`，默认账号 sentinel/sentinel） |
| Seata Server | 2.5.0 | `8091`（`--profile seata` 按需启用） |
| Nginx | 1.27 | <http://localhost>（`--profile nginx`，管理后台静态托管） |

> RocketMQ Dashboard、Sentinel Dashboard 归在 `ops` profile，Nginx 归在 `nginx` profile；
> 不指定 profile 时只启动 MySQL / Redis / Nacos / RocketMQ，按需追加
> `docker compose --profile ops up -d`。

## 部署相关配置

- **应用配置单一来源**：中间件地址等应用配置全部来自配置文件——本地 `application.yml`（默认
  `127.0.0.1` 直连）、容器沙箱 `application-container.yaml`（compose 服务名地址，
  镜像 ENTRYPOINT 激活 `container`，compose 命令再追加 `local`）、Nacos 导入（生产命名空间下发生产地址与密钥密文）。
  不再使用运行时环境变量注入应用配置。
- **运行环境**：由 Nacos namespace 区分——本地 JVM 直跑与容器沙箱默认连接 `atifah_dev`
  （本地 application.yml）；测试/沙箱使用 `container,dev` + 自建 namespace；
  生产部署资产指向生产 namespace 并写入 `--spring.profiles.active=prod`。
  **容器内必须显式声明 `prod` 或 `local`/`dev`**，仅带 `container` 会按 fail-closed 拒绝启动。
- **配置密钥**：以 `ENC()` 密文入库，启动时由 common-security 统一解密；主密钥随配置走
  Nacos 公共配置优先 + 本地 application.yml 兜底，生产主密钥由 Nacos 生产命名空间下发
  （生产兜底取到开发哑密钥会拒绝启动）。加密工具见根 README「配置约定」。
- **日志目录**：默认 `<工作目录>/logs`（Docker 内挂载到仓库根 `logs/`）。
- **容器资源**：`APP_UID`/`APP_GID`（默认 1000:1000，对齐宿主机权限）、`APP_MEM_LIMIT`（默认 1g）、
  `BASE_IMAGE`（可覆盖为国内镜像源）——compose 内置默认值，需要覆盖时用 shell 环境变量。
- **Nacos 配置分层**：每服务导入「公共配置（ATIFAH_PUBLIC_GROUP）+ 服务私有配置
  （ATIFAH_<SERVICE>_GROUP）」，支持动态刷新（`refreshEnabled=true`，Nacos 侧 `ENC()` 值刷新后
  自动重放解密）；启动时装配的日志文件/格式与网关鉴权配置需重启，详见 [nacos/README.md](nacos/README.md)。
- **入口与端口边界**：Compose 仅发布网关业务端口 `8080`，网关管理端口 `9090` 只绑定宿主机回环；
  `8081–8090` 是容器内部端口，不映射到宿主机。禁止通过临时端口映射把内部服务暴露到生产网络。
- **网关鉴权**：`atifah.gateway.auth.enabled` 默认 `true`（Nacos 网关私有配置覆盖），
  生产禁止关闭；管理端密钥以 `ENC()` 密文经 Nacos 网关私有配置下发。
- 本地环境文件（`.env`，如临时创建）已被 `.gitignore` 忽略，禁止提交。

## 快速开始

完整步骤见 [dev-deploy.md](dev-deploy.md)；测试/沙箱见 [test-deploy.md](test-deploy.md)，
生产见 [prod-deploy.md](prod-deploy.md)；Nacos 公共配置导入见 [nacos/README.md](nacos/README.md)。
