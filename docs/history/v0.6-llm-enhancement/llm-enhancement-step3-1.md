## Step 3.1 执行方案：后端内部实时风险摘要注入

### Summary

Step 3.1 只实现 Step 3 中“不涉及消息契约与前端行为”的部分，让聊天链路先具备基于最新风险快照回答通用态势问题的能力。

本步明确范围：
- 实现后端内部风险快照缓存
- 在 `ShipDispatcher` 计算并写入目标现距
- 新增风险摘要 formatter
- 在 `LlmChatService` 注入默认摘要
- 不修改 `ChatRequestPayload`
- 不引入 `selectedTargetIds`
- 不修改前端、WebSocket 协议和前端交互行为

### Key Changes

#### 1. 风险快照缓存
新增 `RiskContextHolder`，放在 `service/llm` 或 `llm` 领域内，职责仅为保存最近一次 `LlmRiskContext` 及其写入时间：
- 字段：`volatile LlmRiskContext current`、`volatile Instant updatedAt`
- 方法：`update(LlmRiskContext ctx)`、`getCurrent()`、`getUpdatedAt()`
- 空上下文允许存在；读取方自行决定是否注入上下文

`ShipDispatcher.publishRiskSnapshot(...)` 在发布 SSE/触发解释前刷新 holder：
- `riskContextHolder.update(snapshot.llmContext())`

#### 2. 现距计算放到 ShipDispatcher 编排路径
不在 `LlmRiskContextAssembler` 内部直接做距离计算。距离由 `ShipDispatcher` 编排阶段计算，再交给 assembler 填充：
- 在派生阶段生成 `Map<String, Double> currentDistancesNm`
- 计算来源为本船与每条目标船的当前平面距离，使用现有 `GeoUtils.distanceMetersByXY(...)` + `metersToNm(...)`
- `LlmRiskContextAssembler` 新增入参接收 `currentDistancesNm`，仅负责写入 `LlmRiskTargetContext.currentDistanceNm`

接口变化：
- `LlmRiskTargetContext` 新增 `double currentDistanceNm`
- `LlmRiskContextAssembler.assemble(...)` 增加 `Map<String, Double> currentDistancesNm`
- `ShipDispatcher.buildRiskSnapshot(...)` 在调用 assembler 前完成距离 map 计算

#### 3. 风险摘要格式化
新增 `RiskContextFormatter`，职责是把 `LlmRiskContext` + `updatedAt` 转为单段中文摘要文本，不承担数据查询或派生计算。

格式化规则固定为：
- 先输出更新时间与本船摘要
- 只展示风险等级非 `SAFE` 的目标
- 目标按风险等级高到低排序；同等级内按 `currentDistanceNm` 升序
- 仅保留 Top-N，Top-N 使用新增独立配置，不复用 `llm.maxTargetsPerCall`
- 不展示 `confidence`
- 末尾输出总追踪数、展示数、未展示数

新增配置：
- `llm.chat-context-max-targets`，默认 `5`
- 放入现有 `LlmProperties`，仅供聊天摘要使用

风险等级排序明确为：
- `ALARM` > `WARNING` > `CAUTION` > `SAFE`
- 未知或空风险等级排在最后，且不进入默认摘要

#### 4. 聊天链路注入摘要
修改 `LlmChatService.buildMessages(...)`：
- 读取 `PromptTemplateService.getSystemPrompt(PromptScene.CHAT)`
- 读取 `RiskContextHolder.getCurrent()` 与 `getUpdatedAt()`
- 若 holder 中存在有效上下文且 formatter 结果非空，消息序列为：
  - `SYSTEM`: chat system prompt
  - `USER`: 风险摘要
  - `USER`: 用户原始问题
- 若当前无上下文或无可展示目标，则保持当前行为：
  - `SYSTEM`
  - `USER`: 用户原始问题

本步不做：
- `selectedTargetIds`
- 目标详情定向注入
- 多轮记忆
- function calling

### Test Plan

需要覆盖以下场景：

- `RiskContextHolderTest`
  - `update()` 后可读取 `current` 和 `updatedAt`
  - 连续更新时取最新值

- `LlmRiskContextAssemblerTest`
  - assembler 能正确写入 `currentDistanceNm`
  - 缺失距离 map 项时使用明确默认值
  - 不影响现有 DCPA/TCPA / riskLevel 组装

- `RiskContextFormatterTest`
  - 仅输出非 `SAFE` 目标
  - 风险等级排序正确
  - Top-N 截断正确
  - 统计信息正确
  - 空上下文、无目标、全为 `SAFE` 时返回空或不注入文本

- `LlmChatServiceTest`
  - 有上下文时，发给 `llmClient.chat(...)` 的消息序列为 `SYSTEM + USER(摘要) + USER(问题)`
  - 无上下文时保持 `SYSTEM + USER(问题)`
  - 原有校验失败、超时、请求失败行为不变

- `ShipDispatcher` 相关测试
  - 每次成功构建快照后会刷新 `RiskContextHolder`
  - 传给 assembler 的距离 map 与当前船位一致

### Assumptions

- Step 3.1 只交付“默认摘要注入”，不提前做 Step 3.2 的协议扩展与前端选中目标行为
- `GeoUtils.distanceMetersByXY(...)` 已可作为当前距离的统一计算入口，本步不再新增第二套距离算法
- 默认摘要中的 Top-N 使用新配置 `llm.chat-context-max-targets`，避免与解释链路的 `llm.maxTargetsPerCall` 语义耦合
- 默认摘要只面向通用态势问答；“指定某艘船现在距离多少”依赖 Step 3.2 的 `selectedTargetIds` 才能稳定支持
