# v1.0 Milestone Overview

> 文档状态：active
> 最后更新：2026-04-28
> 用途：定义 `v1.0` 里程碑下的并行 track、阻塞关系与文档入口。
> 非目标：不替代各 track 的总 plan；不直接承担 step 级实施细则。

## 1. 里程碑语义

`v1.0` 是当前 active implementation milestone，而不是单一功能线名称。

本里程碑下同时维护三条并行 track：

| Track | 目标 | Release impact |
| --- | --- | --- |
| `agent` | 建立有界 agent loop、结构化 advisory 与前端消费闭环 | **阻塞 `v1.0` 主版本收口** |
| `hydrology` | 建立水文专题渲染与后续 agent 可选接入的基础能力 | 不阻塞 `v1.0` 主版本 |
| `weather` | 建立天气专题渲染与后续 agent 可选接入的基础能力 | 不阻塞 `v1.0` 主版本 |

并行 track 共享同一个版本号，但拥有独立的总 plan 和 step 文档。track 不单独升格为版本号。

## 2. 目录与入口

- [`agent/AGENT_LOOP_PLAN.md`](./agent/AGENT_LOOP_PLAN.md)：agent 主线总 plan。
- [`SOURCEBOOK.md`](./SOURCEBOOK.md)：`v1.0` 共用原始材料索引。
- [`VISUAL_UPGRADE_REFERENCE.md`](./VISUAL_UPGRADE_REFERENCE.md)：2026-04-18 前端视觉升级补丁的采纳范围、裁剪项与后续接线参考。
- [`hydrology/HYDROLOGY_PLAN.md`](./hydrology/HYDROLOGY_PLAN.md)：水文线总 plan。
- [`hydrology/step1.md`](./hydrology/step1.md)：水文线首步草稿。
- [`weather/WEATHER_PLAN.md`](./weather/WEATHER_PLAN.md)：天气线总 plan。
- [`weather/step1.md`](./weather/step1.md)：天气线首步草稿。
- [`bugfix/ENVIRONMENT_UPDATE_SPLIT.md`](./bugfix/ENVIRONMENT_UPDATE_SPLIT.md)：拆分 `RISK_UPDATE` / `ENVIRONMENT_UPDATE`，修复 AIS 停止时环境状态无法同步的问题。

## 3. 治理规则

每条 track 的总 plan 必须显式写清以下内容：

- `Goal`：该 track 独立交付什么能力
- `Non-goals`：该 track 在 `v1.0` 不做什么
- `Dependencies`：依赖哪些现有模块或公共契约
- `Release impact`：是否阻塞 `v1.0` 主版本完成

跨 track 约束如下：

- `agent` 只定义 `hydrology` / `weather` 的接入点与消费边界，不代替其实现专题数据与渲染能力。
- `hydrology` 与 `weather` 共享同一个 `ENVIRONMENT_UPDATE.environment_context` 顶层结构与 `EnvAlertCode` 枚举（定义点见 [`hydrology/HYDROLOGY_PLAN.md`](./hydrology/HYDROLOGY_PLAN.md) §3.2–3.3 与 [`weather/WEATHER_PLAN.md`](./weather/WEATHER_PLAN.md) §3.2–3.3）。任一 track 对共享 schema 的变更必须双边同步，不得单边扩展顶层字段。
- `hydrology` 与 `weather` 在 v1.0 规划范围内交付三项终态（前端 2.5D 渲染、风险引擎接入、LLM / agent 接入）。**v1.0 对这两条线的最低交付要求是 Step 1 视觉链路**；Step 2 / Step 3 已属于当前规划链，因而不进入 [`../TODO.md`](../TODO.md)。若 v1.0 关闭时仍未完成，应先迁移到新的 milestone / step 链；只有失去明确 owner 的剩余项才回收至 TODO。
- 若环境专题能力需要进入 `ENVIRONMENT_UPDATE.environment_context`、`RISK_UPDATE.environment_state_version` 或 agent tool schema，必须先在各自总 plan 中定义契约边界，再同步更新后续真值文档；只有未挂入现有规划链的剩余 backlog 才进入 [`../TODO.md`](../TODO.md)。
- `v1.0` 目录只保留已挂在当前实现链上的内容。参考稿中的“v1.1 / post-v1.0 / 后续可做”事项若没有明确 step 或 milestone owner，应回收到 [`../TODO.md`](../TODO.md)，而不是继续作为 `v1.0` 内部待办保留。

## 4. 当前完成标准

`v1.0` 主版本的最小完成标准以 `agent` 主线为准：

- advisory agent loop 具备受控工具调用能力
- `ADVISORY` 协议与前端消费路径收口
- agent loop 的快照、触发与生命周期边界稳定

`hydrology` 与 `weather` 的阶段成果按“可独立演进、可后续接入 agent”的原则推进，各自 Step 1（视觉链路）为 v1.0 内必须完成的最低交付；Step 2 / Step 3 未完成不影响 `agent` 主线出版本，但它们仍以各自 track plan 为准，不因“暂未完成”自动进入 [`../TODO.md`](../TODO.md)。只有脱离现有实施链的剩余事项才回收至 TODO。
