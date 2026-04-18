# 海图系统 (Map-System) — Architecture Overview v0.1

> 用途：项目架构概览 / AI 上下文输入 / 对外介绍
> 最后更新：2026-04-18
> 维护原则：仅记录稳定的架构事实、链路与关键决策

---

## 一、项目一句话

面向船舶态势感知与碰撞风险评估的实时系统，基于多源输入统一建模，包含 2.5D 海图前端、LLM 风险解读与语音交互能力。

## 二、系统定位

- 面向实时船舶态势监控与碰撞风险预警。
- 以多源输入统一汇聚为 `ShipStatus`，形成从消息接入、风险计算、可视化展示到 LLM 解读的完整链路。
- 兼顾演示验证与后续扩展。

## 三、技术栈

| 层 | 技术 |
| --- | --- |
| 消息接入 | MQTT |
| 后端核心 | Spring Boot (`map-service`) |
| 实时推送 | SSE（risk）+ WebSocket（chat） |
| 风险计算 | 自研 CPA/TCPA 引擎 |
| LLM 接入 | Gemini / 智谱，使用 `@ConditionalOnProperty` 切换，`CompletableFuture` 超时降级 |
| 事件协议 | Event Schema v2（见 `docs/EVENT_SCHEMA.md`） |
| 前端 | Vite + TypeScript + Tailwind，2.5D 海图，浏览器原生 `SpeechSynthesis` TTS |
| 语音识别 | `whisper.cpp`（ASR，已完成，Backend-orchestrated） |
| 模拟器 | Python，MQTT 推送，haversine 航迹推算，`--speed-scale` 加速 |
| 数据库副服务 | `listener-service`（暂挂起，离线分析用） |

## 四、系统模块地图

### 4.1 `map-service`

#### 接入层（Adapter）

| 模块 | 职责 |
| --- | --- |
| MQTT Listener | 订阅外部输入消息，触发处理链入口 |
| `WeatherMqttConfig` + `WeatherMessageHandler` | 订阅 `usv/Weather`，维护共享 `WeatherContextHolder` 快照供 SSE/风险元数据消费 |
| `RiskSseController` | 提供 `/api/v2/risk` SSE 风险事件入口 |
| `ChatWebSocketHandler` | 提供 `/api/v2/chat` 双向问答与语音交互入口 |

#### 领域层（Domain）

| 模块 | 职责 |
| --- | --- |
| `ShipStatus` | 内部可信船舶状态类，含 `confidence` 字段，用于解耦 AIS / CV 等数据源 |
| Mapper | 将 AIS / CV / radar 等外部消息转为 `ShipStatus`，承担 normalize 职责 |
| `CpaTcpaEngine` | 计算两船 CPA / TCPA，包含坐标投影修正 |
| `ShipDispatcher` | 按 `ShipRole` 路由到对应引擎，并调用 `RiskObjectAssembler` 聚合结果 |
| `RiskObjectAssembler` | 汇总风险数据，组装待推送与待 LLM 分析的 DTO |
| `RiskStreamPublisher` | 风险事件统一发送入口，负责 `sequence_id` / `event_id` 分配与 SSE 发布 |
| `LlmClient`（`llm/client/`） | LLM 调用抽象接口，支持 `generateText` 与多角色 `chat(List<LlmChatMessage>)` |
| `GeminiLlmClient`（`llm/client/`） | Gemini 实现 |
| `ZhipuLlmClient`（`llm/client/`） | 智谱实现 |
| `LlmChatMessage`（`llm/dto/`） | 多角色消息模型（`SYSTEM / USER / ASSISTANT`） |
| `LlmRiskContext`（`llm/dto/`） | 投喂给 LLM 的风险上下文结构体 |
| `RiskContextHolder`（`llm/context/`） | 持有最近一次风险快照，由 `ShipDispatcher` 在每次计算完成后更新 |
| `ExplanationCache`（`llm/context/`） | 按 `targetId` 缓存最近一条有效解释文本，并在目标消失或降为 SAFE 时驱逐 |
| `RiskContextFormatter`（`llm/context/`） | 将 `LlmRiskContext` 格式化为 LLM 可读摘要；对选中目标可补充最近有效解释文本 |
| `ConversationMemory`（`llm/memory/`） | 按 `conversationId` 维护多轮对话历史，含 TTL 过期、滑动窗口截断与最后一轮替换能力 |
| `PromptTemplateService`（`llm/prompt/`） | 从 `classpath:prompts/` 加载 system prompt 模板 |
| `LlmTriggerService`（`llm/service/`） | 判断是否触发风险解释，结果通过回调交付，不持有 transport 层引用 |

### 4.2 副服务

| 模块 | 职责 |
| --- | --- |
| `listener-service` | 外部输入消息入库，服务离线分析与大数据处理，当前暂挂起 |

## 五、核心消息链路

### 5.1 链路 A：风险评估主链路

```text
Python Simulator / 外部输入源（`usv/AisMessage` + `usv/Weather`）
  ├──→ MQTT Listener（AIS）
  │      │  原始消息
  │      ▼
  │    Mapper (normalize)
  │      │  ShipStatus（内部可信格式）
  │      ▼
  │    ShipDispatcher
  │      │  按 ShipRole 分发
  │      ▼
  │    CpaTcpaEngine
  │      │  CPA / TCPA 计算结果
  │      ▼
  │    RiskObjectAssembler（读取 `WeatherContextHolder`）
  │      │  风险 DTO（含置信度、危险等级）
  │      ▼
  │    RiskStreamPublisher ──→ RiskSseController ──→ 2.5D Frontend（`RISK_UPDATE`）
  │      │
  │      └──→ LlmClient（异步 fan-out，带超时降级）
  │             │  风险解释事件
  │             ▼
  │           RiskStreamPublisher ──→ RiskSseController ──→ 2.5D Frontend（`EXPLANATION` / `ERROR`）
  │
  └──→ WeatherMessageHandler（Weather MQTT）
         │  `usv/Weather`
         ▼
       WeatherContextHolder
```

### 5.2 链路 B：用户与 LLM 对话链路

```text
2.5D Frontend（文本输入 / 语音输入）
  │  WebSocket 消息 / 音频数据
  ▼
ChatWebSocketHandler
  │  `CHAT` / `SPEECH`
  ├──→ whisper.cpp（语音输入时）
  │      │  transcript
  │      ▼
  └──→ LlmChatService / VoiceChatService
         │  从 ConversationMemory 读取历史，按 `selected_target_ids`
         │  注入风险上下文与最近有效 explanation，调用 LlmClient
         │  `edit_last_user_message=true` 时成功后替换最后一轮
         │  结果写回 ConversationMemory
         │  `CHAT_REPLY` / `SPEECH_TRANSCRIPT` / `ERROR`
         ▼
       ChatWebSocketHandler ──→ 2.5D Frontend
```

## 六、能力边界

- 已具备多源消息接入、风险计算、SSE 风险推送、WebSocket 问答交互、TTS 播报与 ASR 语音输入能力。
- 实时事件协议已完成 v2 整理，`risk` 与 `chat` 分别通过 SSE 与 WebSocket 承载；具体字段与事件类型以 `docs/EVENT_SCHEMA.md` 为准。
- LLM 风险解释已从风险主链路中解绑，以异步事件下发；聊天链路支持多轮对话，由 `ConversationMemory` 维护历史，每次请求注入最新风险上下文；当用户选中目标时，最近有效解释文本也会作为补充上下文注入。
- Chat 支持在当前会话的最后一条文本用户消息上执行非破坏式重答：仅在新回复成功生成后才替换最后一组 `USER / ASSISTANT`，失败时保留旧轮次。
- Chat 会话由客户端 `conversation_id` 锚定，后端未绑定 WebSocket session 或用户身份；该实现适用于当前单操作者前端模型，不提供多客户端会话隔离保证。
- `service.llm` 包依赖收口完成：仅依赖 `llm.*`、`domain.*` 和 `config.properties`，不持有 `dto.websocket`、`engine.risk` 或 `transport` 层引用；transport 层负责协议校验与错误码映射。
- ASR 已通过 `whisper.cpp` 接入，当前采用后端统一编排的非流式方案。
- 动画指令、规则引擎、多智能体 / GraphRAG 仍处于规划阶段。
- `listener-service` 当前不在主运行链路中。

## 七、功能完成状态

### P0

- [x] MQTT 接入与多源消息 normalize
- [x] CPA/TCPA 风险引擎
- [x] SSE 风险态势推送（`/api/v2/risk`）
- [x] 单轮 LLM 风险态势解读
- [x] Gemini / 智谱双实现与超时降级
- [x] 移除前端消息气泡功能，消除目标船模型渲染串扰
- [x] 为后端追踪目标集合增加失联目标超时清理

### P1

- [x] 语音播报 TTS（`SpeechSynthesis`）
- [x] 语音输入 ASR（`whisper.cpp`，非流式，Backend-orchestrated）
- [x] LLM 解释从风险主流程解绑，形成独立异步流
- [x] 增加前端亮色主题，形成 `light / dark` 可切换模式
- [x] 向 LLM 注入当前风险上下文

### P2

- [x] 引入统一 `Event` 抽象（`type / source / payload`）
- [x] 风险流与聊天流拆分为 SSE + WebSocket 双连接
- [x] 为协议消息增加版本号
- [x] 新增 `docs/EVENT_SCHEMA.md` 协议文档
- [x] 建立多轮上下文管理（`ConversationMemory`，滑动窗口 + TTL 过期）
- [x] LLM 模块依赖收口（`service.llm` 不再反向依赖 transport / engine 层）
- [x] 风险解释 prompt 增强（注入现距与相对方位，专业化 system prompt）

### P3

- [x] 本船安全领域（动态四参数椭圆模型）
- [x] 目标船航迹预测（CV 模型 + 历史轨迹存储）
- [x] 会遇态势识别（对遇/追越/交叉分类）
- [x] 多因子风险评估增强（域侵入 + 预测 CPA + 会遇修正 → 加权综合评分）
- [x] AIS 数据质量校验（Mapper 层来源校验 + qualityFlags + confidence 消费）
- [x] Mock 清理与管线集成（消除 assembler 硬编码，端到端串联引擎输出）
- [ ] JSON 指令驱动动画
- [ ] 显式实现 agent loop
- [ ] 法律法规与历史危险场景 GraphRAG

### P4
- [ ] 带唤醒词的纯语音交互
- [ ] `whisper.cpp` 升级至 `large-v3`

### 副线

- [ ] `listener-service` 离线分析链路恢复与衔接

## 八、近期路线图（Roadmap）

### P0 - 缺陷修复与运行稳定性（已完成）

- ~~移除前端消息气泡功能~~（已完成）
- ~~为后端追踪目标集合增加超时清理机制~~（已完成）

### P1 - 演示前必须完成（已完成）

- ~~增加前端亮色主题~~（已完成）
- ~~向 LLM 注入当前风险上下文~~（已完成，Step 3）

### P2 - 架构强化（已完成）

- ~~建立多轮上下文管理，形成稳定的对话状态维护能力~~（已完成，Step 4）
- ~~LLM 模块依赖收口，消除反向依赖~~（已完成，Step 5）
- ~~风险解释 prompt 增强~~（已完成，Step 5）

### P3 - 功能扩展

- **Engine 增强**（已完成，详见 `docs/history/v0.7-engine-enhancement/ENGINE_ENHANCEMENT_PLAN.md`）：
  - 本船安全领域：动态四参数椭圆模型，替代 assembler 硬编码尺寸。
  - 目标船航迹预测：CV 恒速模型 + 历史轨迹存储，输出预测轨迹点序列。
  - 会遇态势识别：对遇/追越/交叉三类分类，参考内河避碰规则。
  - 多因子风险评估增强：整合域侵入检测、预测 CPA、会遇类型修正，形成加权综合评分。
  - AIS 数据质量校验：Mapper 层来源特定校验，产出 qualityFlags 与动态 confidence。
  - Mock 清理与管线集成：消除 assembler 全部硬编码，端到端串联引擎输出。
- 引入 JSON 指令驱动动画，用于前端联动与解释增强。
- 显式实现 agent loop，承接后续更复杂的任务编排。
- 接入法律法规与历史危险场景 GraphRAG，为解释与建议提供规则依据、相似案例参考与可追溯推理链。

### P4 - 体验增强

- 实现带唤醒词的纯语音交互，减少对录音按钮点击的依赖。
- 将 `whisper.cpp` 模型升级至 `large-v3`，进一步评估识别准确率提升空间。

## 九、多源数据融合（Fusion 层）设计预案

> 当前系统仅有 AIS 单一输入源，不需要融合层。本节记录后续接入雷达、计算机视觉等第二输入源时的架构演进方向。

### 当前架构

```
External Source → Mapper → ShipStatus → ShipStateStore → Pipeline (Engines → Assemblers → Transport)
```

单源场景下，Mapper 直接产出 `ShipStatus`，由 `ShipStateStore` 持有最新状态，Pipeline 基于此做风险计算。

### 引入 Fusion 的时机

当满足以下条件时引入 Fusion 层：

- 实际有第二个输入源（雷达或 CV）需要接入
- 同一目标可能被多个传感器同时观测，需要多源合并

不为假设性的未来需求提前构建。

### 目标架构

```
AIS Source   → AisMapper   ──┐
Radar Source → RadarMapper ──┼→ QualityAssessor → FusionEngine → TrackedTarget → Pipeline
CV Source    → CvMapper    ──┘
```

### 分层职责

| 层 | 职责 | 说明 |
| --- | --- | --- |
| **Source Mapper** | 协议解析 + 单位归一 + 来源特定校验 | 每个来源单独实现。AIS Mapper 已有，新来源新增对应 Mapper。产出 `ShipStatus` + `sourceConfidence` + `qualityFlags` |
| **Quality Assessor** | 统一质量评估 | 不关心数据来自哪个传感器，关心：时间新鲜度、运动学连续性（与历史轨迹是否突变）、关键字段完整性。产出统一 `qualityScore`。单源阶段可由 Mapper 层的 confidence + qualityFlags 替代 |
| **Fusion Engine** | 多源合并 | 当同一目标有多条不同来源的观测时，基于各来源的 qualityScore 加权合并位置、速度等状态量，产出 `TrackedTarget`（融合后的统一目标状态）。可选算法：加权均值、卡尔曼滤波。需要目标关联（Association）作为前置步骤 |
| **Pipeline** | 风险计算 | 消费融合后的目标状态，与当前 `ShipStatus` → `Pipeline` 的模式一致 |

### Mapper 职责边界原则

- 规则依赖原始协议细节 → 放 Mapper（如 AIS MMSI 校验、heading=511 处理）
- 规则应跨来源统一适用 → 放 Quality Assessor（如时间新鲜度、运动学连续性）
- 规则决定最终业务态势 → 放 Fusion 或 Risk Engine

### confidence 语义

引入 Fusion 后，confidence 应分层表达：

| 字段 | 含义 | 产出层 |
| --- | --- | --- |
| `sourceConfidence` | 来源自身给出的或 Mapper 基于来源规则算出的可信度 | Mapper |
| `qualityScore` | 统一质量评估后的结果 | Quality Assessor |
| `fusionConfidence` | 多源融合后的最终可信度 | Fusion Engine |
| `qualityFlags` | 具体质量问题标签（`STALE`, `POSITION_OUTLIER`, `SPEED_JUMP` 等） | Mapper + Quality Assessor |

单独一个 confidence 数值可解释性不足，必须配合 qualityFlags 使用。

### CV 等自带 confidence 的来源

计算机视觉等来源可能自身已输出 confidence。此值应作为 `sourceConfidence` 接入，但不等同于系统最终可信度。仍需经过 Quality Assessor 的统一评估（框稳定性、多帧跟踪一致性、地理映射误差、与其他来源是否冲突等）。

### 迁移路径

从当前单源架构迁移到 Fusion 架构时：

1. `ShipStatus` 补充 `sourceType` 字段，标记数据来源
2. 新增 `QualityAssessor` 组件，初期复用 Mapper 层已有的 qualityFlags
3. 新增 `FusionEngine`，初期仅做目标关联 + 最新优先（Latest-wins），后续升级为加权融合
4. `ShipStateStore` 改为持有 `TrackedTarget`（融合后状态）而非原始 `ShipStatus`
5. Pipeline 下游无需修改（只消费融合后的目标状态）

## 十、引擎管线上下文聚合（可选优化预案）

> 当前引擎输入以多个独立 Map 参数传递，可在后续迭代中统一为类型化的上下文对象。本节记录该重构方向的设计预案，不影响现有引擎增强计划的实施路径。

### 背景

`RiskAssessmentEngine.consume()` 当前接收 6 个独立参数（`ownShip`、`allShips`、`cpaResults`、`shipDomainResult`、`cvPredictionResults`、`encounterResults`），各 per-target 结果以独立 `Map<String, T>` 传入。随着引擎增强步骤推进，参数数量仍会增加（如未来目标船域侵入比）。

### 目标结构

使用 Java 21 sealed class + record 构建类型化上下文层次：

```java
sealed abstract class EnrichedShipStatus permits OwnShipContext, TargetShipContext {
    public abstract ShipStatus base();
}

record OwnShipContext(
    ShipStatus base,
    ShipDomainResult safetyDomain,   // Step 1 产出
    PlannedRoute plannedRoute        // 接口待定，初期为 null
) extends EnrichedShipStatus {}

record TargetShipContext(
    ShipStatus base,
    CpaTcpaResult cpaResult,
    CvPredictionResult cvPrediction,
    EncounterClassificationResult encounterType
    // 后续扩展: Double domainPenetration
) extends EnrichedShipStatus {}
```

引擎签名变为：

```java
RiskAssessmentResult consume(OwnShipContext ownShip, Collection<TargetShipContext> targets)
```

### 职责变化

- `ShipDispatcher.buildRiskSnapshot()` 承担组装职责：从 `ShipDerivedOutputs` 各 map 中查询并构造 `TargetShipContext` 列表；引擎内部参数减少，逻辑更集中。
- `buildTargetAssessment()` 签名对称变为 `(OwnShipContext, TargetShipContext)`，用 sealed + pattern matching 替代 null 检查约定。

### PlannedRoute 接口

本船预规划航线需在系统外部录入后存入系统。接入接口尚未确定，候选方案：REST 接口预存、WebSocket 客户端下发、数据库 lookup（按 MMSI）。确定前 `plannedRoute` 字段为 null，相关逻辑跳过。

### 可扩展性

`OwnShipContext` 设计为可扩展至舰队场景：若后续本船升级为本船舰队，新增 `FleetContext permits OwnShipContext` 层或将 `OwnShipContext` 替换为 `List<OwnShipContext>` 均不影响 `TargetShipContext` 侧逻辑。

### 实施前提

此重构为纯结构性变更，不改变任何风险计算逻辑，可在 Engine 增强全部步骤完成后独立执行。

## 十一、Architecture Decisions / Review Findings

关键设计取舍与对应 review 沉淀已迁移至：

- `docs/ADR_AND_REVIEW_FINDINGS.md`

## 十二、维护要求

- 新增模块或职责变更时，同步更新模块地图。
- 消息链路变更时，同步更新链路图。
- 新增关键设计取舍或对应 review 沉淀时，同步更新 `docs/ADR_AND_REVIEW_FINDINGS.md`。
- 功能阶段变化时，同步更新完成状态与 TODO。
