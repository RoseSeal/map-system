# Map-System 系统设计文档

> 文档版本：v1.0
> 最后更新：2026-04-03
> 文档状态：current
> 基线来源：`docs/ARCHITECTURE.md`、`docs/EVENT_SCHEMA.md`

---

## 一、文档目的

本文档用于描述 Map-System 的当前系统设计，包括：

- 系统目标与边界
- 领域建模与核心处理链路
- 实时事件设计
- LLM 与语音交互集成方式
- 数据留存与工程结构

本文档不重复维护协议细节与 ADR 全量内容：

- 架构概览与 ADR 以 `docs/ARCHITECTURE.md` 为准
- 实时事件字段与示例以 `docs/EVENT_SCHEMA.md` 为准

---

## 二、系统定位

Map-System 是面向船舶态势感知与碰撞风险评估的实时系统，目标是在多源输入条件下，形成一条从消息接入、统一建模、风险计算、前端展示，到 LLM 解读与语音交互的完整链路。

当前系统定位有三个关键点：

- 面向实时风险评估，而不是离线分析平台
- 以统一内部实体 `ShipStatus` 解耦 AIS、CV、Radar 等异构输入
- 以演示可运行和后续扩展兼顾为原则，优先收敛主链路

---

## 三、设计目标与边界

### 3.1 设计目标

- 稳定接入外部船舶动态消息
- 将异构输入标准化为统一内部状态对象
- 实时输出 CPA/TCPA 驱动的风险快照
- 将风险快照通过 SSE 持续推送到 2.5D 前端
- 将风险解释与用户对话能力接入 LLM
- 支持文本与语音两种交互入口

### 3.2 当前边界

当前明确纳入主设计的能力：

- MQTT 消息接入
- `ShipStatus` 统一建模
- CPA/TCPA 风险计算
- 风险流 SSE 推送
- Chat WebSocket 双向交互
- LLM 风险解释
- `whisper.cpp` 非流式语音转写

当前明确不作为主链路前提的能力：

- `listener-service` 离线分析链路
- 多轮对话上下文管理
- 法规 RAG
- 多智能体编排
- 规则引擎与动画指令

这些能力可以继续扩展，但不应反向污染当前主设计的职责边界。

---

## 四、总体架构

### 4.1 架构总览

```text
Python Simulator / 外部输入源
  │
  ▼
MQTT
  │
  ▼
map-service
  ├─ mqtt / mapper          输入接入与 normalize
  ├─ domain                 统一领域对象（ShipStatus 等）
  ├─ store / pipeline       状态维护与处理编排
  ├─ engine                 CPA/TCPA、风险评估、安全域、预测
  ├─ assembler              风险 DTO 与 LLM 上下文组装
  ├─ service/llm            风险解释、聊天、语音编排
  ├─ transport/risk         SSE 风险流
  ├─ transport/chat         WebSocket 聊天流
  └─ api                    风险 SSE 与海图接口
       │
       ├──→ Frontend 2.5D 海图与控制面板
       └──→ LLM Provider（Gemini / 智谱）
                │
                └──→ whisper.cpp（语音输入时）
```

### 4.2 服务划分

#### `map-service`

系统核心服务，承担主运行链路中的全部关键职责：

- 订阅 MQTT 输入
- 将外部消息转换为 `ShipStatus`
- 维护目标状态集合
- 执行风险计算与对象组装
- 通过 SSE 推送风险快照与解释
- 通过 WebSocket 处理聊天与语音交互
- 编排 LLM 与 ASR 调用

#### `listener-service`

副服务，定位为外部消息入库与离线分析支撑。当前暂挂起，不在主运行链路内。

---

## 五、核心设计

### 5.1 统一领域实体：`ShipStatus`

系统核心设计决策是使用 `ShipStatus` 作为统一可信实体，而不是直接围绕 AIS 原始字段编程。

当前 `ShipStatus` 至少包含以下稳定语义：

- `id`：目标标识；无法解析时允许为空
- `role`：本船或目标船角色
- `longitude` / `latitude`：位置
- `sog` / `cog` / `heading`：动态信息
- `msgTime`：消息时间
- `confidence`：观测可信度

这样做的目的：

- 解耦 AIS、CV、Radar 等输入差异
- 让风险引擎只依赖统一语义，不依赖某个传感器字段
- 为后续多源融合保留空间

### 5.2 接入与标准化

接入层通过 MQTT 监听外部消息，再由具体 mapper 负责 normalize。当前实现以 AIS 为主，其他输入源沿用相同模式扩展。

设计要求：

- 输入源差异应被限制在 mapper 层
- 进入 pipeline 后只处理统一领域对象
- 原始输入异常应在接入层尽早隔离，不向核心引擎传播协议噪声

### 5.3 状态维护与调度

`ShipDispatcher` 是风险主链路的调度核心，负责：

- 基于 `ShipRole` 识别本船与目标船
- 结合 `ShipStateStore` 维护当前跟踪集合
- 在上下文准备完成后调用引擎与 assembler
- 将风险快照交给 `RiskStreamPublisher`
- 触发异步 LLM 风险解释

该设计使业务 pipeline 与具体 transport 解耦，避免领域层直接操作 SSE 或 WebSocket 细节。

### 5.4 风险计算

当前风险设计以 CPA/TCPA 为核心，并在结果层叠加图形化与风险分级信息。

已纳入主链路的核心能力：

- `CpaTcpaEngine`：两船 CPA / TCPA 计算，包含坐标投影修正
- `RiskAssessmentEngine`：生成风险等级与评估结果
- `RiskObjectAssembler`：组装风险快照 DTO

当前已具备实现但仍属于增强项的能力：

- `ShipDomainEngine`：本船安全领域
- `CvPredictionEngine`：短时轨迹预测

这些能力已经进入代码结构与风险对象语义，但其产品化程度仍以实际启用状态为准。

### 5.5 风险对象

风险主链路对前端输出的核心对象是风险快照，其稳定结构包括：

- `risk_object_id`
- `timestamp`
- `governance`
- `own_ship`
- `targets`
- `environment_context`

其中：

- `own_ship` 表示本船态势、健康状态、预测与安全域信息
- `targets` 表示目标船列表及其 `risk_assessment`
- `environment_context` 表示环境与附加预警上下文

具体字段以 `docs/EVENT_SCHEMA.md` 中 `RISK_UPDATE` payload 为准。

---

## 六、实时链路设计

### 6.1 风险主链路

```text
外部输入 / 模拟器
  │
  ▼
MQTT Listener
  │
  ▼
Mapper
  │
  ▼
ShipStatus
  │
  ▼
ShipDispatcher
  │
  ├──→ CpaTcpaEngine / RiskAssessmentEngine / 其他引擎
  │         │
  │         ▼
  │     RiskObjectAssembler
  │         │
  │         ▼
  └──→ RiskStreamPublisher
            │
            ├──→ `/api/v2/risk` SSE：`RISK_UPDATE`
            └──→ 异步触发 LLM 解释后继续发布 `EXPLANATION`
```

该链路的核心原则：

- 风险快照优先，解释异步下发
- 主链路不等待 LLM 才返回风险帧
- 风险发布统一经过 `RiskStreamPublisher`

### 6.2 Chat / Speech 链路

```text
Frontend
  │
  ▼
`/api/v2/chat` WebSocket
  │
  ▼
ChatWebSocketHandler
  ├──→ 文本：LlmChatService
  └──→ 语音：VoiceChatService
            │
            ├──→ whisper.cpp 转写
            └──→ LLM 回复
```

设计原则：

- chat 只承载会话交互
- speech 是独立消息类型，不再混入 `CHAT`
- `mode=preview` 时仅返回转写，不触发 LLM
- `mode=direct` 时转写后继续触发问答

---

## 七、事件与传输设计

### 7.1 连接拆分

系统采用双连接模型：

| 连接 | 路径 | 协议 | 方向 | 职责 |
| --- | --- | --- | --- | --- |
| risk | `/api/v2/risk` | SSE | 单向下行 | 风险快照、风险解释、风险错误 |
| chat | `/api/v2/chat` | WebSocket | 双向 | 文本问答、语音输入、聊天错误 |

这是当前系统最重要的传输层设计决策之一。

### 7.2 采用双连接的原因

- 风险流本质是无会话、单向广播，更适合 SSE
- chat 是带会话语义的双向交互，更适合 WebSocket
- 风险快照与解释解耦后，可以避免 LLM 阻塞主链路
- SSE 原生支持自动重连与 `Last-Event-ID`

### 7.3 关键语义约定

- `sequence_id`：
  - risk 连接中由 SSE 原生 `id:` 字段承担
  - chat 连接中存在于 server -> client WebSocket envelope
- `event_id`：
  - 统一作为业务事件标识
  - server -> client 与 client -> server 都保留
- `conversation_id`：
  - 仅属于 chat payload
  - 不属于 `EXPLANATION`

### 7.4 事件类型

#### risk SSE

- `RISK_UPDATE`
- `EXPLANATION`
- `ERROR`

#### chat WebSocket

client -> server:

- `PING`
- `CHAT`
- `SPEECH`

server -> client:

- `PONG`
- `CHAT_REPLY`
- `SPEECH_TRANSCRIPT`
- `ERROR`

事件字段、payload 示例与错误码枚举统一以 `docs/EVENT_SCHEMA.md` 为准。

---

## 八、LLM 与语音集成设计

### 8.1 LLM 集成原则

LLM 只消费系统已经结构化好的风险事实，不承担底层数值计算。

这样做的原因：

- 降低幻觉风险
- 提高风险解释可追踪性
- 将计算责任稳定留在领域引擎

### 8.2 Provider 抽象

系统通过 `LlmClient` 接口抽象具体模型供应商，当前已有：

- `GeminiLlmClient`
- `ZhipuLlmClient`

服务装配通过配置切换，并带有超时降级策略。

### 8.3 风险解释

风险解释链路设计为异步 fan-out：

- `RISK_UPDATE` 先发
- 解释生成完成后单独发 `EXPLANATION`
- 异常通过 `ERROR` 事件表达，而不是阻塞风险帧

解释事件与 chat 会话解耦，不携带 `conversation_id`。

### 8.4 语音输入

当前语音输入采用 Backend-orchestrated 方案：

- 前端采集音频并通过 WebSocket 发送
- `map-service` 调用 `whisper.cpp`
- 后端拿到 transcript 后决定是否继续调用 LLM
- 再由同一条 chat 连接把结果推回前端

当前为非流式方案，优先保证链路清晰与工程可控；后续如需 partial transcript 或边说边回，再评估流式 speech session。

---

## 九、前端协同设计

前端围绕两类实时数据源协同：

- risk SSE：态势、风险、解释
- chat WebSocket：文本问答、语音问答

前端当前的主要职责：

- 渲染 2.5D 海图与目标态势
- 展示目标风险等级与解释卡片
- 管理聊天面板与语音交互
- 在 `light / dark` 主题之间切换
- 使用浏览器原生 `SpeechSynthesis` 执行 TTS 播报

前端不负责风险计算，也不负责 ASR/LLM 编排。

---

## 十、数据与持久化设计

### 10.1 在线主链路

当前主链路是内存态 + 实时推送为主：

- 当前跟踪目标集合由 `ShipStateStore` 维护
- 风险帧通过 SSE 推送
- chat 事件通过 WebSocket 推送

### 10.2 持久化与副服务

静态空间数据与海图服务仍使用 PostgreSQL / PostGIS 方向；`listener-service` 则用于外部消息入库与离线分析衔接。

当前设计判断：

- 主运行链路不依赖 `listener-service`
- 离线分析恢复前，不将其写入主架构依赖
- 静态海图接口与动态风险协议分开维护

---

## 十一、工程结构

```text
map-system/
├── backend/
│   ├── map-service/
│   │   └── src/main/java/com/whut/map/map_service/
│   │       ├── api/
│   │       ├── mqtt/
│   │       ├── domain/
│   │       ├── store/
│   │       ├── pipeline/
│   │       ├── engine/
│   │       ├── assembler/
│   │       ├── service/llm/        ← LlmChatService, LlmExplanationService, VoiceChatService
│   │       ├── llm/
│   │       │   ├── client/         ← LlmClient 接口及 Gemini/Zhipu 实现（Step 1 迁入）
│   │       │   └── dto/            ← ChatMessage, LlmRiskContext 等 LLM 专用模型（Step 1 迁入）
│   │       ├── transport/
│   │       │   ├── protocol/
│   │       │   ├── risk/
│   │       │   └── chat/
│   │       └── dto/
│   └── listener-service/
├── frontend/
├── simulator/
└── docs/
    ├── design.md
    ├── ARCHITECTURE.md
    └── EVENT_SCHEMA.md
```

其中 `transport` 包是协议拆分后的关键结构调整，明确替代旧的混合式 `websocket` 语义。

---

## 十二、当前状态与后续扩展

### 12.1 已稳定纳入设计的能力

- MQTT 接入与 AIS normalize
- `ShipStatus` 统一建模
- CPA/TCPA 风险计算
- `risk = SSE`、`chat = WebSocket`
- LLM 风险解释异步下发
- Gemini / 智谱双 Provider 抽象
- `whisper.cpp` 非流式语音输入

### 12.2 下一阶段扩展方向

- 向 LLM 注入当前风险上下文，提升“评估当前状况”类问答质量
- 建立稳定的多轮上下文管理
- 完善本船安全领域与目标短时轨迹预测
- 接入法律法规 RAG
- 评估流式语音交互与更强 ASR 模型

这些扩展应继续遵循当前设计原则：领域计算前置、协议边界清晰、主链路与解释链路解耦。

---

## 十三、维护约定

- 当系统职责边界发生变化时，同时更新本文档与 `docs/ARCHITECTURE.md`
- 当实时事件字段或语义变化时，只在 `docs/EVENT_SCHEMA.md` 维护协议真值
- 当新增关键取舍时，在 `docs/ARCHITECTURE.md` 中补充 ADR
- 当本文档与协议文档冲突时，以 `docs/EVENT_SCHEMA.md` 为准；当本文档与架构职责描述冲突时，以 `docs/ARCHITECTURE.md` 为准
