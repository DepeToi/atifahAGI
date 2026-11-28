<!--
标题：type(scope): 摘要（Feature 为 type(scope): T-xxx 摘要）
一个 PR 只做一件事；变更说明、影响面、验证命令与回滚方式必须写清。
-->

## 变更类型

- 类型（必填）：Feature / Arch / Ops / Docs（混合变更按最高风险类型处理）
- 任务包 / ADR：`docs/tasks/T-xxx-<slug>.md`（Feature 必填）；`docs/adr/NNNN-*.md`（Arch 按 DOC-01）
- 契约基线：`arch-baseline-vX.Y`（Feature 填任务包的 `contract_baseline`；契约变更填拟打的标签）
- 改动路径范围（Feature 填任务包「允许修改」；其余类型逐项列出）：

## 变更说明

- 改了什么、为什么：
- 影响面（服务 / 接口 / 配置 / 数据）：
- 关联 Issue：

## 验证

```text
# 填写实际运行的命令与结果
```

- [ ] CI 针对当前提交通过
- [ ] 新增/修改测试已覆盖关键成功与失败路径
- [ ] 手动验证或未覆盖范围（如适用）：

## 风险与回滚

- 风险 / 兼容性 / 数据迁移影响：
- 回滚方式：

## 评审提示（可选）

- 重点看：
- 已知限制：
