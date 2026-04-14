# Step 7: 包结构重组（垂直功能域化）执行方案

> 文档状态：就绪
> 对应 ENGINE_ENHANCEMENT_PLAN.md 中新增的 Step 7
> 前置状态：Step 6 (Mock 清理与管线集成) 已完全合并

## 1. 命名与定位

本步骤命名为 **"包结构重组（垂直功能域化）"**。

定位：在所有引擎核心逻辑与风险管线增量计算、协议集成等重大重构（Step 1-6）收尾后，执行最后一次纯结构性的技术债清理。将现有混用横向分层（如 `api/`、`repository/`、`config/`）与纵向子模块（如 `llm/`、`mqtt/`、`engine/`）的包结构，全面重组为统一的**垂直功能域化（Vertical Slice）**架构。

## 2. 目标与范围

核心目标：
1. 建立清晰的功能边界，使 `source`（多源数据接入）、`tracking`（目标跟踪与状态存储）、`chart`（航图）、`risk`（风险管线）、`llm`（大语言模型分析）五大核心领域解耦。
2. 将无状态的工具类、跨域共享的数据结构、全局配置下沉至 `shared` 域。
3. 确保后续新增功能或微服务拆分时，领域上下文（Bounded Context）清晰明确。

**范围限定**：
- **纯重构（Pure Refactoring）**：仅修改 `package` 声明、`import` 语句以及对应的文件目录路径，**严禁任何业务逻辑或引擎算法的变更**。
- 不影响现有的对外 HTTP/SSE/WS 接口路径与协议定义。
- `llm` 包内部已经做过模块化解耦，保持现有内部结构不变。

## 3. 目标包结构设计

重组后的基础包路径为 `com.whut.map.map_service`。

```text
com.whut.map.map_service
├── source/                         ← 多源数据接入域（为未来雷达、CV等预留）
│   └── ais/                        (原 mqtt/ 包，重命名为 ais，存放 AIS 特有逻辑)
│       └── config/                 (原 config/ 中 AIS 专属配置)
├── tracking/                       ← 目标跟踪与状态存储域（多源融合后的状态管理）
│   └── store/                      (原 store/ 包)
├── chart/                          ← 航图服务
│   ├── api/S57Controller.java      (从原 api/ 迁入)
│   └── repository/                 (原 repository/ 包)
├── risk/                           ← 风险评估核心
│   ├── api/RiskSseController.java  (从原 api/ 迁入)
│   ├── config/                     (原 config/ 中风险相关配置)
│   ├── engine/                     (原 engine/ 包)
│   ├── pipeline/                   (原 pipeline/ 包，含 assembler)
│   ├── event/                      (原 event/ 包)
│   ├── scheduling/                 (原 config/scheduling/，因调度主要服务风险管线)
│   └── transport/                  (原 transport/risk/ 包)
├── shared/                         ← 跨域共享层
│   ├── config/                     (原 config/ 包剩余部分，如公共基础配置)
│   ├── domain/                     (原 domain/ 包，含 QualityFlag, ShipStatus 等)
│   ├── dto/                        (原 dto/ 包)
│   ├── transport/                  
│   │   └── protocol/               (原 transport/protocol/ 包)
│   └── util/                       (原 util/ 包，含 GeoUtils, AisProtocolConstants 等)
├── llm/                            ← 大语言模型代理（含原 config/websocket/ 等）
└── MapServiceApplication.java      ← 启动类
```

## 4. 详细迁移与重命名操作指南

### 4.1 Source 域 (`source/`)
1. 新建 `source` 目录，并在其下新建 `ais` 目录。
2. 将 `com.whut.map.map_service.mqtt` 包内的文件整体移动至 `com.whut.map.map_service.source.ais.mqtt`（或者直接平铺在 `source.ais` 下，视当前包内结构而定）。
3. 新建 `source/ais/config` 目录，将 `config/mqtt/MqttConfig.java`、`config/properties/MqttProperties.java`、`config/properties/AisProperties.java`、`config/properties/AisQualityProperties.java` 移动至此。

### 4.2 Tracking 域 (`tracking/`)
1. 新建 `tracking` 目录。
2. 将 `com.whut.map.map_service.store` 包整体移动至 `com.whut.map.map_service.tracking.store`。
3. 提示：该域目前存储以 AIS 为主产生的 `ShipStatus`，但在多源架构中，它将承担 Fusion 层融合后的 `TrackedTarget` 状态存储。

### 4.3 Chart 域 (`chart/`)
1. 新建 `chart` 目录。
2. 新建 `chart/api` 目录，将 `api/S57Controller.java` 移动至此。
3. 将 `com.whut.map.map_service.repository` 包整体移动至 `com.whut.map.map_service.chart.repository`。

### 4.4 Risk 域 (`risk/`)
1. 新建 `risk` 目录。
2. 新建 `risk/api` 目录，将 `api/RiskSseController.java` 移动至此。
3. 新建 `risk/config` 目录，将以下特定领域的配置类（及其对应的 properties 文件，若在相同包下）移入：`RiskAssessmentProperties.java`、`RiskScoringProperties.java`、`ShipDomainProperties.java`、`EncounterProperties.java`、`TrajectoryPredictionProperties.java`、`ShipStateProperties.java`、`RiskObjectMetaProperties.java`。
4. 将 `com.whut.map.map_service.engine` 包整体移动至 `com.whut.map.map_service.risk.engine`。
5. 将 `com.whut.map.map_service.pipeline` 包整体移动至 `com.whut.map.map_service.risk.pipeline`。
6. 将 `com.whut.map.map_service.event` 包整体移动至 `com.whut.map.map_service.risk.event`。
7. 新建 `risk/transport` 目录，将 `com.whut.map.map_service.transport.risk` 包整体移动至 `com.whut.map.map_service.risk.transport`。
8. 新建 `risk/scheduling` 目录，将 `com.whut.map.map_service.config.scheduling.SseKeepaliveScheduler` 移动至此。因该调度器强耦合了管线的清理与刷新（`shipStateStore` 驱逐触发 `shipDispatcher.refreshAfterCleanup`）以及风险数据的长连接（`sseEmitterRegistry`），它是风险链路的关键驱动者。

### 4.5 Shared 域 与 LLM 域
1. 新建 `shared` 目录。
2. 将剩余的跨域 `com.whut.map.map_service.config` 包内容移动至 `com.whut.map.map_service.shared.config`。
3. **注意 WebSocket 配置迁移**：将 `config/websocket/WebSocketConfig.java` 直接迁入 `llm/config/`，因为它专门通过 `WhisperProperties` 控制容器级消息缓冲区大小，以支撑 LLM 相关的音视频大载荷通信，与负责路由映射的 `ChatWebSocketConfig.java` 职责互补但不冗余，两者都属于 `llm` 领域。不要混入 `shared/config/`。
4. 将 `com.whut.map.map_service.domain` 包移动至 `com.whut.map.map_service.shared.domain`。
5. 将 `com.whut.map.map_service.dto` 包移动至 `com.whut.map.map_service.shared.dto`。
6. 新建 `shared/transport`，将 `com.whut.map.map_service.transport.protocol` 包移动至 `com.whut.map.map_service.shared.transport.protocol`。
7. 将 `com.whut.map.map_service.util` 包移动至 `com.whut.map.map_service.shared.util`。
   - *注：`AisProtocolConstants.java` 由于被 `engine/`（风险计算）广泛引用，保留在 `shared/util/` 供跨域访问。*

### 4.6 编译修复与测试包迁移
1. **测试包同步迁移**：在 `src/test/java/com/whut/map/map_service/` 目录下，执行与上述 `src/main` 完全一致的包结构重组操作，确保测试类（如 `engine/safety/ShipDomainEngineTest.java` 等）与被测类同包路径。
2. 使用 IDE 工具（如 IntelliJ IDEA 的 Refactor -> Move）进行包迁移，以自动更新跨包的 `import` 语句。
3. 全局搜索 `import com.whut.map.map_service.*` 并进行校验，确保没有漏改的包引用。
4. 检查 Spring Boot 的 `@ComponentScan` 和相关的依赖注入，由于仍处于 `com.whut.map.map_service` 子包下，Spring 的自动装配预期不会失效。

## 5. 验收标准

1. **零逻辑变更验证**：使用 `git diff --stat` 确认修改仅涉及文件路径变更及头部 `package` / `import` 声明，无主体逻辑变动。
2. **全量编译通过**：`./mvnw clean compile test-compile` 必须 100% 成功，无编译错误。
3. **单元测试与集成测试通过**：执行 `./mvnw test`，所有现存测试用例（包括由 Step 1-6 新增的测试用例）必须全部通过。
4. **端到端冒烟测试（本地校验）**：
   - 启动 `map-service` 与 `simulator`。
   - 打开前端看板，验证 `RiskSseController` 能持续推送正常格式的 `RISK_UPDATE` 事件，轨迹、区域绘制正常。
   - 验证 `S57Controller` 瓦片加载正常。
   - 验证 WebSocket (LLM 对话) 功能不受影响。