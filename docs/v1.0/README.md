# v1.0 Milestone Overview

> 文档状态：active
> 最后更新：2026-04-17
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
- [`hydrology/HYDROLOGY_PLAN.md`](./hydrology/HYDROLOGY_PLAN.md)：水文线总 plan。
- [`hydrology/step1.md`](./hydrology/step1.md)：水文线首步草稿。
- [`weather/WEATHER_PLAN.md`](./weather/WEATHER_PLAN.md)：天气线总 plan。
- [`weather/step1.md`](./weather/step1.md)：天气线首步草稿。

## 3. 治理规则

每条 track 的总 plan 必须显式写清以下内容：

- `Goal`：该 track 独立交付什么能力
- `Non-goals`：该 track 在 `v1.0` 不做什么
- `Dependencies`：依赖哪些现有模块或公共契约
- `Release impact`：是否阻塞 `v1.0` 主版本完成

跨 track 约束如下：

- `agent` 只定义 `hydrology` / `weather` 的接入点与消费边界，不代替其实现专题数据与渲染能力。
- `hydrology` 与 `weather` 共享同一个 `environment_context` 顶层结构与 `EnvAlertCode` 枚举（定义点见 [`hydrology/HYDROLOGY_PLAN.md`](./hydrology/HYDROLOGY_PLAN.md) §3.2–3.3 与 [`weather/WEATHER_PLAN.md`](./weather/WEATHER_PLAN.md) §3.2–3.3）。任一 track 对共享 schema 的变更必须双边同步，不得单边扩展顶层字段。
- `hydrology` 与 `weather` 在 v1.0 规划范围内交付三项终态（前端 2.5D 渲染、风险引擎接入、LLM / agent 接入）。**v1.0 对这两条线的最低交付要求是 Step 1 视觉链路**；Step 2 / Step 3 在规划内但不构成 v1.0 release blocker，未在 v1.0 内收口的项按 Release impact 规则回收到 [`../TODO.md`](../TODO.md) 或后续 milestone。
- 若环境专题能力需要进入 `risk` payload、`environment_context` 或 agent tool schema，必须先在各自总 plan 中定义契约边界，再同步更新 `docs/TODO.md` 与后续真值文档。

## 4. 当前完成标准

`v1.0` 主版本的最小完成标准以 `agent` 主线为准：

- advisory agent loop 具备受控工具调用能力
- `ADVISORY` 协议与前端消费路径收口
- agent loop 的快照、触发与生命周期边界稳定

`hydrology` 与 `weather` 的阶段成果按“可独立演进、可后续接入 agent”的原则推进，各自 Step 1（视觉链路）为 v1.0 内必须完成的最低交付；Step 2 / Step 3 未完成不影响 `agent` 主线出版本，回收至 [`../TODO.md`](../TODO.md) 或后续 milestone。
