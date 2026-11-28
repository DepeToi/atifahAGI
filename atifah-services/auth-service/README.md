# auth-service

认证服务：注册 / 登录 / 令牌刷新 / 登出。端口 `8081`，库 `atifah_auth`，
Nacos Group `ATIFAH_AUTH_GROUP`。

- 接口契约已冻结：[auth-service.yaml](../../docs/contracts/openapi/auth-service.yaml)
- DDL 契约：[auth-service.sql](../../docs/contracts/db/auth-service.sql)

## 已实现接口

- `POST /api/v1/auth/register`（必须带 `Idempotency-Key`）
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`

## 实现要点

- 表结构由 Flyway 管理（`src/main/resources/db/migration/V1__init.sql`、
  `V2__auth_refresh_token.sql`），服务启动时自动迁移；已执行脚本禁止修改，变更走新版本号。
- 密码只存 BCrypt 哈希；账号不存在时也执行一次哈希比对，避免用响应时间判断账号是否存在。
- access token 携带 `typ=access` 与 `jti`；refresh token 只存哈希并轮换，超过重用宽限期（默认 30 秒）
  的重复使用视为泄露并触发全量吊销，宽限期内按并发轮换仅拒绝请求；
  登出当前只撤销 Refresh Token，写入 Redis 黑名单（`auth:token:<jti>`）为后续任务。
- 注册幂等键、登录失败计数复用 `common-redis` 的 `CacheKeys` / `RedisUtil`；该依赖在本模块 `pom.xml` 显式引入，父 POM 不统一提供。

## 当前缺口

网关强制鉴权（`atifah.gateway.auth.enabled`）现已默认开启；三条链已有单元测试，后续主要缺口是：

- 真实 Redis/Nacos 端到端联调；
- 尚未覆盖的 403 授权行为；
- auth-service 登出写 `auth:token:<jti>`；
- 需满足 [docs/rules/security.md](../../docs/rules/security.md) SEC-03 的其余开启条件。

## 接口示例

契约原文见 [auth-service.yaml](../../docs/contracts/openapi/auth-service.yaml)。

```bash
# 注册（Idempotency-Key 必须为 UUID）
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000' \
  -d '{"phone":"13800005678","password":"Passw0rd!23"}'

# 登录 / 刷新 / 登出
curl -X POST http://localhost:8080/api/v1/auth/login   -H 'Content-Type: application/json' -d '{...}'
curl -X POST http://localhost:8080/api/v1/auth/refresh -H 'Content-Type: application/json' -d '{"refreshToken":"..."}'
curl -X POST http://localhost:8080/api/v1/auth/logout  -H 'Content-Type: application/json' -d '{"refreshToken":"..."}'
```

## 运行

构建与启动命令见根 [README 快速开始](../../README.md#快速开始)。
