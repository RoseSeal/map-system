# Map-System 系统设计文档

> **版本 (Version)**：v0.5 → v2.0 Roadmap
> **目标 (Objective)**：面向无人船的海上态势感知与风险预警系统
> **当前里程碑 (Current Milestone)**：v0.5 — 跑通基本数据通路，实现最小可用系统
> **截止日期 (Deadline)**：2026-03-16（v0.5 汇报）
> **作者 (Author)**：xin

---

## 一、项目概述 (Project Overview)

### 1.1 系统定位

基于无人船 AIS (Automatic Identification System) 数据的海上态势感知系统。实时采集船舶动态数据，结合静态海图信息与安全模型，向 2.5D WebGL 前端推送船舶位置、航行预警和航线预测。

### 1.2 数据源

- **动态数据**：船舶 AIS 消息，通过 MQTT Broker 实时广播（航速、航向、经纬度等）
- **静态数据**：S-57 电子海图 (ENC)、Coastlines、Ports、预规划航线，存储于 PostgreSQL + PostGIS
- **历史数据**：国家数据中心 AIS 数据（武汉附近水域，约 1400 万条），用于离线模型计算和轨迹分析

### 1.3 当前工程状态

| 组件 | 状态 | 说明 |
|------|------|------|
| **backend/listener-service** | ✅ 已完成 | 订阅 MQTT → 解析 AIS → Batch 写入 PostgreSQL |
| **frontend/** | ✅ 可运行 | 基于 MapLibre + Deck.gl 的 2.5D WebGL 海图前端，当前 Mock 数据驱动 |
| **simulator/** | ✅ 可运行 | Python 脚本，读取国家数据中心 AIS 数据，发布到本地 MQTT Docker Broker |
| **backend/map-service** | 🔨 开发中 | 核心后端服务，v0.5 主要交付物 |
| **docs/** | ✅ 已有 | 本设计文档 |

---

## 二、系统架构 (System Architecture)

### 2.1 整体架构

系统分为三个核心模块与两个扩展模块：

```
                          ┌───────────────┐
                          │  MQTT Broker  │
                          └───────┬───────┘
                                  │
                     ┌────────────┼────────────┐
                     │            │            │
                     ▼            ▼            │
            ┌────────────┐ ┌──────────────┐   │
            │  Module A  │ │  Module B    │   │
            │  Listener  │ │  map-service │   │
            │            │ │              │   │
            │ MQTT→PgSQL │ │ ┌──────────┐ │   │
            │ (数据落库)  │ │ │ mqtt/    │ │   │
            └─────┬──────┘ │ │ 订阅 AIS │ │   │
                  │        │ ├──────────┤ │   │
                  ▼        │ │ engine/  │ │   │
            ┌──────────┐   │ │ CPA/TCPA │ │   │
            │PostgreSQL│   │ │ CV 预测  │ │   │
            │+ PostGIS │◄──┤ ├──────────┤ │   │
            │          │   │ │ model/   │ │   │
            │ 表:       │   │ │ 安全模型 │ │   │
            │ais_history│  │ │(启动加载)│ │   │
            │density    │  │ ├──────────┤ │   │
            │behavior   │  │ │websocket/│ │   │
            │nogo_area  │  │ │ 实时推送 │ ├──→ Frontend (2.5D WebGL)
            │coastlines │  │ ├──────────┤ │   │
            │routes     │  │ │ api/     │ │   │
            └─────┬─────┘  │ │ 海图 REST│ ├──→ Frontend (初始化拉取)
                  │        │ └──────────┘ │
                  │        └──────────────┘
                  │
                  ▼
            ┌──────────────┐
            │  Module C    │
            │  Processor   │         (v1)
            │              │
            │ 离线计算:     │
            │  密度模型     │
            │  行为模型     │
            │  禁航区       │
            └──────────────┘

            ┌──────────────┐
            │  Extension   │
            │  LLM Agent   │         (v2)
            │              │
            │ Prompt + RAG │
            │ 输出: 决策建议│
            └──────────────┘
```

### 2.2 模块职责

#### Module A — Data Ingestion Pipeline (数据采集管线)

**服务名**：`listener-service`
**状态**：✅ 已完成

职责单一：订阅 MQTT AIS 广播 → 解析 → Batch Queue 聚合 → 批量写入 PostgreSQL + PostGIS。

不做任何业务计算，只负责数据持久化。

#### Module B — Stream Processing & Gateway (流处理与网关)

**服务名**：`map-service`
**状态**：🔨 v0.5 开发中

核心后端服务，承担实时计算与数据推送。为控制 v0.5 复杂度，物理上保持单一 Spring Boot 服务，代码层面用 Package 做逻辑隔离，后续按需拆分。

**Package 结构：**

```
map-service/src/main/java/com/xin/map/
├── config/              # MQTT、WebSocket、缓存等配置
├── mqtt/                # MQTT 订阅，AIS 消息接入
├── engine/              # 计算引擎：CPA/TCPA、CV 预测、Ship Domain
├── model/               # 安全模型缓存（启动时从 DB 加载，事件驱动刷新）
├── websocket/           # WebSocket 推送（动态流）
├── api/                 # RESTful API（静态流）
├── domain/              # 数据模型：AisMessage, Warning, Route 等
└── MapServiceApplication.java
```

**输出分为两条流：**

| 流类型 | 协议 | 内容 | 推送频率 |
|--------|------|------|---------|
| **动态流** | WebSocket | AIS 位置更新、CPA/TCPA 预警、CV 航线预测、安全模型告警 | 实时（秒级） |
| **静态流** | RESTful HTTP + Cache | 海图数据 (Coastlines, Ports)、预规划航线 | 前端初始化时拉取 |

**关于 WebSocket 的技术决策：**
针对后端向前端的单向高频数据流，SSE (Server-Sent Events) 是更标准的方案。但考虑到未来可能需要双向通信（如用户点击某艘船查看详情），且现有前端已基于 WebSocket 实现，v0.5 沿用 WebSocket 协议。此架构妥协记录为 Tech Debt，留待 v1 前端重构时评估。

#### Module C — Offline Analytics (离线分析)

**服务名**：`processor`
**状态**：v1 实现

离线 / 批处理管道，定时或手动触发。从 PostgreSQL 拉取历史 AIS 轨迹与电子海图，计算三类安全模型：

| 模型 | 功能 | 告警触发条件 |
|------|------|------------|
| Position Density Model | 基于历史轨迹的区域密度分析 | 船舶进入低密度（罕见）区域 |
| Gridded Behavior Model | 基于网格的行为概率统计 | 船舶执行低概率行为 |
| No-Go Area | 聚合静态障碍 + 低密度区域 | 船舶进入禁航区 |

采用 ETL 流程 (Extract → Transform → Load)，支持流式批次处理防止内存溢出。计算结果写入 PostgreSQL，供 Module B 消费。

#### Extension — LLM Agent (大模型智能体)

**状态**：v2 实现

将 Module B 的实时计算结果封装为自然语言上下文，输入 LLM 进行推理，输出航行决策建议。

**技术方案：**

1. **上下文封装 (Prompt Engineering)**：将 CPA/TCPA 计算结果 + 历史轨迹组装为结构化文本
2. **LLM Decision Layer**：大模型负责推理和策略输出（不做数学计算）
3. **RAG (Retrieval-Augmented Generation)**：将历史船舶事故���录向量化，存入 PostgreSQL pgvector 插件，为 LLM 提供检索增强

**输入示例：**
> "目标船舶是一艘油轮，距离我方 0.5 海里，预计 3 分钟后发生碰撞，当前天气大雾。"

**输出示例：**
> "危险等级：极高。建议立即向右满舵 15 度，并用 VHF 频道呼叫对方。"

---

## 三、Module B 核心设计 (Core Design)

### 3.1 数据模型 (Domain Model)

```java
// AIS 消息
public class AisMessage {
    private String mmsi;          // 船舶 MMSI 唯一标识
    private Double longitude;     // 经度
    private Double latitude;      // 纬度
    private Double speed;         // 航速 (knots)
    private Double course;        // 航向 (degrees)
    private Double heading;       // 船首向 (degrees)
    private Long timestamp;       // Unix 时间戳
}

// 预警信息
public class Warning {
    private String targetMmsi;    // 目标船 MMSI
    private WarningLevel level;   // GREEN / YELLOW / RED
    private Double cpa;           // CPA (nautical miles)
    private Double tcpa;          // TCPA (seconds)
    private String message;       // 预警描述
}

// 安全领域
public class ShipDomain {
    private double[] center;      // [lon, lat]
    private Double semiMajor;     // 长半轴 (nm)
    private Double semiMinor;     // 短半轴 (nm)
    private Double orientation;   // 朝向 (degrees)
}
```

### 3.2 实时预警引擎 (Real-time Warning Engine)

v0.5 实现基于静态规则的实时预警，不涉及 AI 模型。

**规则定义：**

| 规则 | 计算方式 | 触发条件 |
|------|---------|---------|
| CPA 预警 | 计算本船与每艘目标船的 Closest Point of Approach | CPA < 0.5 nm → RED; CPA < 1.0 nm → YELLOW |
| TCPA 预警 | 计算 Time to CPA | TCPA < 600s 且 TCPA > 0（正在接近）→ 触发 |
| Ship Domain 预警 | 基于本船航速和航向计算安全领域椭圆 | 目标船进入椭圆区域 → 触发 |

**预警等级：**

```
GREEN  → 安全，无需关注
YELLOW → 需关注（CPA < 1.0 nm）
RED    → 危险（CPA < 0.5 nm 且 TCPA < 10 min）
```

### 3.3 航线预测 (Route Prediction)

**v0.5**：实现简易 Constant Velocity Model (CV)，基于目标船当前航速和航向做线性外推。

**v1.5**：升级为机器学习模型，需要在内存中维护轻量级 Sliding Window（滑动窗口）缓存近期轨迹点作为模型输入，避免实时查询数据库。

### 3.4 安全模型缓存策略 (Security Model Cache Strategy)

Module C 的离线计算结果需要被 Module B 高效消费。采用三层策略：

**第一层：Event-Driven Cache Invalidation (事件驱动缓存失效)**

复用现有 MQTT Broker 作为内部通信总线。Module C 完成计算并入库后，向内部专属 Topic（`usv/internal/model_sync`）发布轻量级 JSON 变更事件。发布与订阅强制采用 QoS 1 (At Least Once)，确保更新信号必达。

**第二层：Double Buffering (双缓冲 / 写时复制)**

Module B 监听到同步信号后，不对当前 Active Cache 执行任何 `clear()` 或写锁操作。后台线程初始化全新缓存对象，异步加载最新数据，完成后通过 Java 引用的 Atomic Swap（原子替换）瞬间完成新老缓存交接。确保高并发 AIS 流处理零阻塞。

**第三层：TTL Fallback (生存时间兜底)**

为内存缓存配置粗粒度 TTL（如 12 小时）。若遭遇 Network Partition 导致 MQTT 消息链路崩溃，TTL 超时将强制触发拉取，作为最后一道防线确保模型数据不会无限期陈旧。

### 3.5 WebSocket 推送格式 (Push Message Format)

**AIS 位置更新：**

```json
{
  "type": "AIS_UPDATE",
  "data": {
    "ownShip": {
      "mmsi": "412000001",
      "lon": 114.3,
      "lat": 30.5,
      "speed": 12.5,
      "course": 45.0,
      "heading": 44.8,
      "timestamp": 1709712000
    },
    "targetShips": [
      {
        "mmsi": "413000002",
        "lon": 114.5,
        "lat": 30.6,
        "speed": 8.2,
        "course": 220.0,
        "heading": 219.5,
        "timestamp": 1709712000
      }
    ]
  }
}
```

**预警信息推送：**

```json
{
  "type": "WARNING_UPDATE",
  "data": {
    "warnings": [
      {
        "targetMmsi": "413000002",
        "level": "RED",
        "cpa": 0.3,
        "tcpa": 480,
        "message": "目标船 413000002 将在 8 分钟后于 0.3 nm 处交会"
      }
    ],
    "ownShipDomain": {
      "center": [114.3, 30.5],
      "semiMajor": 0.5,
      "semiMinor": 0.3,
      "orientation": 45.0
    }
  }
}
```

**航线推送：**

```json
{
  "type": "ROUTE_UPDATE",
  "data": {
    "ownRoute": {
      "waypoints": [
        { "lon": 114.3, "lat": 30.5, "eta": "2026-03-16T10:00:00Z" },
        { "lon": 114.8, "lat": 30.8, "eta": "2026-03-16T12:00:00Z" }
      ]
    }
  }
}
```

---

## 四、数据库设计 (Database Schema)

```sql
-- AIS 历史数据表 (Module A 写入)
CREATE TABLE ais_history (
    id          BIGSERIAL PRIMARY KEY,
    mmsi        VARCHAR(20) NOT NULL,
    position    GEOMETRY(Point, 4326) NOT NULL,
    speed       DOUBLE PRECISION,
    course      DOUBLE PRECISION,
    heading     DOUBLE PRECISION,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ais_mmsi ON ais_history(mmsi);
CREATE INDEX idx_ais_time ON ais_history(received_at);
CREATE INDEX idx_ais_position ON ais_history USING GIST(position);

-- 预规划航线表
CREATE TABLE planned_route (
    id          SERIAL PRIMARY KEY,
    ship_mmsi   VARCHAR(20) NOT NULL,
    waypoints   GEOMETRY(LineString, 4326) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 以下表由 Module C 离线计算写入 (v1)
-- position_density_model   位置密度模型
-- gridded_behavior_model   行为网格模型
-- nogo_area                禁航区
```

---

## 五、目录结构 (Project Structure)

```
map-system/
├── backend/
│   ├── listener-service/            # Module A：MQTT → PostgreSQL (✅ 已完成)
│   │   ├── src/
│   │   └── pom.xml
│   ├── map-service/                 # Module B：核心后端服务 (🔨 开发中)
│   │   ├── src/main/java/com/xin/map/
│   │   │   ├── config/
│   │   │   ├── mqtt/
│   │   │   ├── engine/
│   │   │   ├── model/
│   │   │   ├── websocket/
│   │   │   ├── api/
│   │   │   ├── domain/
│   │   │   └── MapServiceApplication.java
│   │   └── pom.xml
│   └── pom.xml
├── frontend/
│   └── unmanned-fleet-ui/           # 2.5D WebGL 前端 (✅ 可运行)
├── simulator/
│   └── ais_publisher.py             # AIS 数据模拟器 (✅ 可运行)
├── database/
│   └── init.sql
├── docs/
│   └── design.md                    # 本文档
└── README.md
```

---

## 六、技术选型 (Tech Stack)

| 组件 | 选型 | 理由 |
|------|------|------|
| 后端框架 | Spring Boot 3.x | 已有基础，生态成熟 |
| MQTT Client | Eclipse Paho / Spring Integration MQTT | Listener 已在用 |
| 实时推送 | Spring WebSocket | 替代 HTTP Polling，支持双向通信 |
| 数据库 | PostgreSQL 16 + PostGIS 3.x | 已有，GIS 空间查询能力 |
| ORM | MyBatis | 灵活控制 SQL，适合空间查询 |
| 前端 | MapLibre GL JS + Deck.gl | 已有，WebGL 高性能渲染 |
| 容器 | Docker + docker-compose | MQTT Broker 等基础设施容器化 |
| 构建 | Maven | 已有 |

---

## 七、旧服务复用策略 (Legacy Service Strategy)

以下 4 个 Spring Boot 服务为此前开发的原型，核心算法思路有参考价值，代码需重构。

| 旧服务 | 决策 | 理由 | 目标版本 |
|--------|------|------|---------|
| **Path Safety Service** — 禁航区 REST API | 吸收 | 功能简单，合并到 Module B 的 `api/` | v1 |
| **Geointel Processor** — 离线 ETL 计算密度/行为/禁航区模型 | 重构复用 | 核心 ETL 算法有价值，提取计算逻辑重构为 Module C | v1 |
| **Geointel Dashboard** — 海图瓦片 MVT + 样式服务 + WebSocket | 提取复用 | 提取 MVT 生成与样式服务合并到 Module B 的 `api/`；WebSocket 部分自己重写 | v1 |
| **Anomaly Detection Service** — MQTT 实时异常检测 | 参考后重写 | 功能与 Module B `engine/` 高度重叠；CPA/TCPA 等逻辑自己重写，确保面试能讲清楚 | v0.5 |

**核心原则：算法思路可参考旧代码，核心逻辑必须自己写。**

---

## 八、版本路线图 (Version Roadmap)

### v0.5 — 最小可用系统 (2026-03-16)

**必须完成（汇报底线）：**

- [x] Module A：MQTT → PostgreSQL 数据落库
- [ ] Module B 核心功能：
  - [ ] 订阅 MQTT，解析 AIS 消息
  - [ ] 区分本船 / 目标船
  - [ ] CPA/TCPA 实时计算（自己写）
  - [ ] WebSocket 推送前端
  - [ ] 前端渲染船舶位置 + 预警信息

**可选（有时间就做）：**

- [ ] CV (Constant Velocity) 航线预测
- [ ] 静态海图 RESTful API
- [ ] Ship Domain 安全领域计算

**不做：**

- ❌ Module C 离线分析 → v1
- ❌ 旧服务复用整合 → v1
- ❌ LLM Agent → v2
- ❌ Kafka 削峰 → v2
- ❌ Redis 缓存 → v2

### v1 — 完整后端功能 (~2026-05)

- [ ] Module B 动态流补全：安全模型查询 + 告警推送
- [ ] Module C 离线分析管道上线
- [ ] 安全模型缓存策略实现（Event-Driven + Double Buffering + TTL Fallback）
- [ ] 旧服务复用整合
- [ ] 前端理解与视觉优化
- [ ] 接入水文信息

### v1.5 — AI 模型接入 (~2026-06)

- [ ] 航线预测升级为机器学习模型
- [ ] 内存 Sliding Window 缓存近期轨迹
- [ ] 模型推理超时降级策略 (Timeout + Fallback)

### v2 — 性能优化 + LLM 智能体 (~2026 暑假)

- [ ] Kafka 削峰：MQTT → Kafka → Batch Consumer → PostgreSQL
- [ ] Redis + Caffeine 多级缓存：海图瓦片、安全模型
- [ ] LLM Agent：Prompt Engineering + RAG (pgvector)
- [ ] JMeter 压测 + 性能优化
- [ ] 完整 Docker Compose 部署方案

---

## 九、v0.5 开发计划 (Development Schedule)

| 日期 | 任务 | 产出 |
|------|------|------|
| 3/04 ✅ | 撰写 v0.5 设计文档；迁移 Listener 和前端 | 目录结构建立，GitHub 仓库创建 |
| 3/05 ✅ | 清理重构 PostgreSQL；确认 Listener 运行 | 数据库可用 |
| 3/06 ✅ | Listener 重构 + MQTT Broker Docker + AIS Simulator + 数据链路跑通 | AIS → MQTT → Listener → PostgreSQL ✅ |
| 3/07-08 | 创建 map-service 骨架；MQTT 订阅 + AIS 解析 | 后端能收到并解析 MQTT 消息 |
| 3/09 | WebSocket 配置 + 前端对接 | 前端实时渲染船舶位置 |
| 3/10-11 | CPA/TCPA 计算引擎（自己写） | 预警算法可用 |
| 3/12 | 预警推送 + 前端预警渲染 | 预警可视化 |
| 3/13 | 航线数据查询 + 推送（可选） | 航线可视化 |
| 3/14-15 | 联调测试 + Bug 修复 + 汇报准备 | 系统稳定 |
| **3/16** | **汇报** | **v0.5 交付** |

---

## 十、风险与应对 (Risks & Mitigations)

| 风险 | 概率 | 应对 |
|------|------|------|
| 前端 WebSocket 对接困难 | 中 | 先用最简消息格式跑通，再迭代优化 |
| CPA/TCPA 算法实现有误 | 中 | 查阅论文确认公式；编写单元测试验证 |
| 12 天时间不够 | 中 | 砍掉航线模块和 Ship Domain，优先保证 AIS 通路 + CPA 预警 |
| MQTT 消息量过大导致 map-service 过载 | 低 | v0.5 使用模拟器控制发送速率；v2 引入 Kafka 削峰 |

### 时间不够时的优先级

```
P0（必须完成）：
  AIS 数据通路跑通：MQTT → map-service → WebSocket → 前端渲染
  至少一条预警规则可用：CPA < 阈值 → 推送前端

P1（尽量完成）：
  完整预警体系：CPA + TCPA + Ship Domain
  CV 航线预测

P2（可延后）：
  航线展示
  静态海图 REST API
```