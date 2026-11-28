# 契约目录（docs/contracts）

> `docs/contracts/` 是接口 / DDL / 事件变更的**唯一依据**：Claude 在架构阶段读写，CodeBuddy 按它实现，Codex 对照它评审。
> 契约冻结后优先级高于实现代码；实现与契约冲突时以契约为准，实现侧不得就地改契约迁就代码。

## 1. 目录结构

```text
docs/contracts/
├── api-conventions.md          # 接口总纲：响应、错误码、鉴权、分页、幂等、脱敏
├── openapi/
│   ├── _template.yaml          # OpenAPI 3.1 模板
│   └── <service>.yaml          # 每个服务一份（如 auth-service.yaml）
├── db/
│   ├── _template.sql           # 逻辑 Schema 模板
│   └── <service>.sql           # 每个服务一份（如 auth-service.sql）
└── events/
    ├── _template.md            # 事件契约模板
    └── <event-name>.md         # 每个事件一份，与 MqTopics 常量对应
```

- 服务名取 `atifah-<service>` 去前缀后的名称（管理端 BFF 为 `admin-service`）。
- 事件名用 kebab-case；模板文件以 `_` 开头，不视为已冻结契约。
- Flyway 迁移脚本放各服务 `src/main/resources/db/migration/`，逻辑结构必须与 `db/<service>.sql` 一致（DB-03：不一致时以契约为准，先改契约）。

## 2. 写入权限

- 只有 Claude（架构角色）可以修改本目录，且必须在 `arch/<topic>` 分支、单独开契约 PR；CodeBuddy 与 Codex 只读，发现缺口或矛盾标 `[Contract-Question]` 回到架构侧。
- 契约 PR 不得夹带功能代码；Feature 也不得夹带契约修改（违反按 Blocker 处理，见 `docs/review/standards.md` DOC-01 / SCOPE-01）。

## 3. 冻结与变更流程

- **冻结**：Claude 产出契约并经维护者确认后，在 `main` 打 `arch-baseline-vX.Y` 标签（首个为 `arch-baseline-v0.1`）。Feature 任务包的 `contract_baseline` 字段必须指向已存在且是 `main` 祖先的标签（`docs/tasks/README.md` §4）。
- **变更分级**（DOC-01，与 `AGENTS.md` §0 一致）：

  | 性质 | 前置产物 |
  | --- | --- |
  | 首次冻结 / 破坏性变更 / 跨服务语义 / 新组件 | 新增或更新 ADR |
  | 兼容新增（新增可选字段、新增接口、事件新增可选字段） | 契约 diff + PR 变更说明 |

- **破坏性判定**：删除/重命名字段、改变语义、收紧必填、错误码改义均为破坏性；接口升级路径版本（`/api/v2`），事件升级版本；错误码只增不改义。
- **流程**：
  1. 在 `arch/<topic>` 分支修改契约，按上表带上 ADR 或变更说明。
  2. 契约 PR 单独评审合并；首次冻结与破坏性变更合并后打新基线标签。
  3. 按 `api-conventions.md` §10 同步受影响文档与常量（`common-core`、前端 `src/api/types.ts`）。
  4. Feature 任务包更新 `contract_baseline` 后才可进入实现；不得在同一 PR 里同时改契约与实现。

## 4. 冻结检查清单

- [ ] 每个接口有请求/响应示例、错误码与鉴权要求（免鉴权写明理由）。
- [ ] 写接口声明 `Idempotency-Key`，例外与 `api-conventions.md` §7 一致。
- [ ] 分页、时间格式、ID 类型符合 `api-conventions.md`。
- [ ] DDL 含主键、唯一索引、审计字段、字符集与注释；敏感字段标注加密或脱敏方式。
- [ ] 事件契约含 Topic、生产者、消费者、key、幂等键、重试/死信策略。
- [ ] 与 `AGENTS.md`、`docs/rules/*`、`docs/architecture.md` 无矛盾。

## 5. 各角色如何消费

- **Claude（架构）**：架构阶段读写契约；任务包只引用契约路径与锚点，不复制内容。
- **CodeBuddy（实现）**：实现阶段只读，按契约实现，不自行扩展字段；缺口回到架构侧。
- **Codex（评审）**：对照契约检查 diff；接口路径、字段、错误码、DDL、事件 payload 与契约不一致按 Blocker。

## 6. 相关文档

- [接口总纲](api-conventions.md)
- [任务包规范](../tasks/README.md)
- [评审标准](../review/standards.md)
- [数据库规则](../rules/database.md)
- [ADR 索引](../adr/README.md)
