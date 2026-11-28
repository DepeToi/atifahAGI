# 0001. AI 协作工作流与自动化门禁

- 状态：已采纳
- 日期：2026-11-28
- 决策人：人类维护者

## 背景

项目由三个 AI 工具（Claude 架构 / CodeBuddy 实现 / Codex 评审）与单一人类维护者协作开发。规则最初散落在会话与口头交接中，不可审计、不可复现；评审判据、状态权限、提交权限没有唯一出处，AI 之间存在越权与漂移风险。按 `docs/adr/README.md`「何时写」第 5 条，协作流程本身也需要 ADR 依据。

约束：

- 维护者是单人，不能承担全部机械检查与逐行评审；
- 产品涉及情绪日记、量表结果等敏感个人信息，安全规则优先于效率；
- 所有交接必须只通过仓库完成，不依赖对话记忆（`AGENTS.md` §0）。

## 决策

- **R1 角色分工**：Claude 只做架构（契约 / ADR / 任务包 / 公共能力 / 网关），CodeBuddy 只按任务包实现 Feature，Codex 只读评审，人类维护者确认合并；同一工作区同一时刻只允许一个角色写文件（GIT-07）。
- **R2 变更四分类**：Feature / Arch / Ops / Docs，各自有必需产物、评审口径与可写路径；混合变更按最高风险类型处理，Feature 不得夹带 Arch/Ops 路径（`AGENTS.md` §0）。
- **R3 契约冻结**：`docs/contracts/` 是接口 / DDL / 事件唯一依据，架构基线用 `main` 上的 `arch-baseline-vX.Y` 标签冻结；任务包 `contract_baseline` 必须指向存在且为 `main` 祖先的标签。
- **R4 任务包状态机**：`draft / ready / in-review / done / blocked` 五状态，只有授权角色可流转；禁止 status-only PR（维护者 `status-batch` 批量收口除外）；状态与 PR / 合并证据不符按 DOC-04 处理。
- **R5 评审与验证**：Codex 按 `docs/review/standards.md` 的规则编号与四级结论（Blocker / Major / Minor / Question）只读评审，按 A/B/C 级核验 CI 与风险测试；评审记录以 PR 评论为准。
- **R6 CI 自动化门禁**：机械可判定项交给 CI（`.github/scripts/`，纯 bash + git，零第三方依赖）：
  - `ci-detect.sh` 变更分类，docs-only PR 跳过构建 job；
  - `ci-scope-check.sh` 变更类型与路径一致性（SCOPE-01/03、DOC-01、GIT-07）+ Feature 任务包与「允许修改」路径校验；
  - `ci-static-gates.sh` 静态规则，只查 diff 新增行（DB-02 / CODE-01 / CODE-04 / CODE-06 / ARCH-03 / ARCH-04）；
  - `ci-task-validate.sh` 任务包 front matter 与基线标签存在性（DOC-04 机械部分）；
  - `ci-diff-size.sh` 生产语义 diff ≤400 行近似统计（SCOPE-02）；
  - `ci-compose-config.sh` compose 配置校验（Ops B 级验证自动化）。

  CI 只覆盖机械项，语义评审仍由 Codex 执行（落实 standards.md §7「增加自动化检查」的机制）。
- **R7 分支模型**：默认分支 `main`（受保护，禁直推 / force-push），Feature 用 `feat/T-xxx-<slug>`，架构 / 部署 / 文档分别 `arch/`、`ops/`、`docs/`；AI 的 commit / push / 开 PR 须按 GIT-01 逐任务授权。

## 备选方案

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| A. 单文件 AGENTS.md 承载全部规则 | 入口唯一、维护简单 | 超长失焦；模块规则无法就近生效；评审判据与角色细则混杂 | 否决 |
| B. 分层治理：根入口 + rules/contracts/tasks/review 分册 + 模块 AGENTS.md | 就近生效、按需加载、规则编号可被任务包与 CI 引用 | 文件多，需防重复抄写（规则只维护一份） | 采纳 |
| C. 全人工 / 全 AI 目检评审，无 CI 门禁 | 零基建成本 | 机械项不可复现、漏检率高、评审负担集中在单维护者 | 否决 |
| D. 规则编号 + CI 自动化门禁 + Codex 只读语义评审 | 机械项可复现，评审聚焦语义与风险 | 门禁为近似口径（diff 行数），脚本需随规则演进 | 采纳（与 B 配合） |

## 影响

- 正面：工作流首次可实际运行（`main` / 基线标签 / CI 就绪）；机械检查自动化后评审聚焦语义；变更分类与状态权限有了可执行判据。
- 负面 / 代价：CI 门禁依赖 PR 模板格式（「类型（必填）：」行）与路径分类表，模板或分类调整必须同步 `ci-scope-check.sh`；diff 体积为近似统计，超大一次性导入需维护者在 PR 说明豁免。
- 需要同步修改的文档与代码：`.github/workflows/ci.yml`、`.github/scripts/**`、`docs/review/standards.md`（§4.1 自动化门禁说明）、`.github/pull_request_template.md`。
- 回滚方式：revert ops PR（`.github/**`）恢复原 CI；角色 / 分类规则回滚需新 ADR 替代本文件。
- 上线残留项（待配置远程后执行）：推送 `main` 与标签 → 设默认分支 `main` → 开启分支保护（require CI、禁直推）→ `gh label create status-batch`。

## 关联

- 相关契约：`docs/contracts/README.md`（冻结与变更流程）
- 相关任务包：无（本 ADR 为流程基线，无 Feature 任务）
- 替代 / 被替代的 ADR：无（首个 ADR）
