# Docs History - Archived Process Records

> 文档状态：archive index
> 用途：仅作为历史过程参考，不承担当前真值。

## 1. 这里有什么

本目录归档已完成版本（`v0.5` 至 `v0.9`）的阶段性 plan、step 拆解与早期协议草案：

- [`v0.5/`](./v0.5)、[`v0.5-mvp/`](./v0.5-mvp)：早期消息契约、语音聊天链路设计与首版 schema 草案。
- [`v0.6-llm-explanation/`](./v0.6-llm-explanation)：LLM 解释模块早期设计稿。
- [`v0.7-llm-enhancement/`](./v0.7-llm-enhancement)：`LlmClient` 抽象、prompt 管理、上下文注入、memory、解释链路的阶段记录。
- [`v0.8-engine-enhancement/`](./v0.8-engine-enhancement)：风险引擎增强阶段的 step 拆解。
- [`v0.9-frontend-enhancement/`](./v0.9-frontend-enhancement)：AI 工作区前端壳层与编辑/选择目标交互的实现记录。
- [`v1.0/`](./v1.0)：v1.0 milestone 收口归档；agent / hydrology / weather / visual / bugfix 全部 track 的 plan 与 step 文档。详见 [`v1.0/README.md`](./v1.0/README.md)（v1.0 closure summary）。

## 2. 阅读约束

- **本目录的内容不是当前真值**。当前实现真值以 [`../ARCHITECTURE.md`](../ARCHITECTURE.md)、[`../EVENT_SCHEMA.md`](../EVENT_SCHEMA.md)、[`../frontend-design.md`](../frontend-design.md) 为准。
- 若历史步骤描述与当前实现冲突，**以当前真值文档为准**，本目录不再回写。
- 历史文档可能包含已废弃的字段名、协议形态或模块路径，**直接引用前需在当前代码中复核**。
- 仅在以下场景查阅本目录：追溯某个决策的演进过程、复现历史协议格式、面试或 review 时回顾阶段经验。

## 3. 何时新增条目

- 一个 milestone 关闭时，将其 plan 与 step 文档整体迁入本目录，按 `vX.Y-<topic>/` 命名。
- 当前真值文档已沉淀的结论不再回归本目录。
- 单个 bugfix 文档若已被 ADR 或 EVENT_SCHEMA 吸收，可直接归档到对应 milestone 目录。
