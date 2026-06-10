# step-2 — `HistoricalCaseQueryPort` 抽象与 LightRAG 适配器（Java 侧）

> 版本：[v1.1-graphrag](./OVERVIEW.md) · status: active
> 本步 status：pending review
> 最后更新：2026-06-10

---

## 1. 来源与边界（锚定 overview，不重定义）

引自 [OVERVIEW.md](./OVERVIEW.md) step-2 条目，本步不得越界：

- **objective**：在 map-service 内新增独立的历史案例检索 port 与对接 sidecar 的 adapter，受独立 feature flag 控制。
- **input dependencies**：[step-1](./step-1.md)（sidecar 的 HTTP 检索契约，见 step-1 §6）。
- **deliverables**：`HistoricalCaseQueryPort` 接口 + 其 LightRAG HTTP adapter 实现；态势特征 → 案例结果的查询/返回 DTO；独立 `@ConditionalOnProperty` 开关；覆盖启用/禁用两路的单测（以 mock sidecar 驱动）。
- **DoD**：启用 flag 时 adapter 调用 sidecar 返回结构化案例；禁用时 bean 不加载且既有 `GraphQueryPort` 链路无回归；单测通过。
- **scope ceiling**：不改动 `GraphQueryPort` / `MemoryGraphAdapter`；adapter 只做 HTTP 调用与 DTO 映射，不内嵌检索/排序逻辑；不引入结构化 evidence 协议。

---

## 2. 当前状态与可复用资产

- **既有 port 模式**（`llm/agent/graph/`）：`GraphQueryPort` 是纯 interface，`MemoryGraphAdapter` 为普通实现类，由 `MemoryGraphLoader`（`@Configuration` + `@Bean @ConditionalOnProperty(prefix="llm.graph", name="enabled", havingValue="true")`）装配；query/context 为 record（`RegulatoryQuery` 等）。step-2 平行复刻这一模式，不与之合并（overview 决策 1）。
- **既有 HTTP sidecar 客户端模式**（`WhisperClientImpl` + `WhisperProperties`）：whisper 也是 HTTP sidecar，用 `RestTemplate` + `SimpleClientHttpRequestFactory`（连接/读超时来自 `@ConfigurationProperties(prefix="whisper")`），baseUrl 由属性给出。step-2 的 adapter 复刻此形态。
- **可直接复用的枚举**（step-1 §5 已对齐到这些 Java 契约）：`OwnShipRole`、`EncounterType`、`VisibilityCondition`、`RiskLevel`。case query DTO 直接以这些枚举为字段，无需翻译层。
- **配置约定**：属性键用 kebab-case（`llm.graph.enabled`、`whisper.timeout-ms`）；无全局 Jackson 命名策略（默认 camelCase）。

---

## 3. 设计决策与被否方案

### 3.1 独立包 `llm.agent.casegraph`，与 `graph` 包平级

新 port 及其类型置于新包 `com.whut.map.map_service.llm.agent.casegraph`，与既有 `llm.agent.graph` 平级。物理隔离落实 overview 决策 1「独立 port，并存而非合并」，并避免与规则图类型混淆。

**被否**：放进 `llm.agent.graph` 包——会让规则图与案例图类型混居，违背 ISP 与并存意图。

### 3.2 复用既有领域枚举，不新建

`HistoricalCaseQuery` 以 `OwnShipRole` / `EncounterType` / `VisibilityCondition` / `RiskLevel` 为字段。step-3 从 `AgentSnapshot` 派生这些枚举值可直接喂入，无转换。

**被否**：为案例图另立一套枚举——与 step-1 的枚举对齐决策矛盾，引入翻译层。

### 3.3 HTTP 客户端复刻 Whisper，但 `RestTemplate` 由 config 注入（可测性）

adapter 用 `RestTemplate` + `SimpleClientHttpRequestFactory`（超时取自 `CaseGraphProperties`），POST JSON 到 `{baseUrl}/retrieve`。与 Whisper 唯一不同：`RestTemplate` 在 config bean 内构造并经构造器注入 adapter（Whisper 是在客户端内部 `buildRestTemplate()`）。

**理由**：注入式便于用 `MockRestServiceServer` 绑定该 `RestTemplate` 做契约测试（DoD 要求 mock sidecar 单测）；这是 step-2 自身实现选择，非 overview 偏离。

**被否**：照搬 Whisper 的内部构造——难以 mock，单测被迫起真实 HTTP。

### 3.4 wire DTO 用 snake_case 映射，与领域类型分离

sidecar 契约是 snake_case JSON（`own_ship_role`、`top_k`），而项目无全局命名策略。故 adapter 内部维护一组 **wire DTO**（`RetrieveRequest`/`Situation`/`RetrieveResponse`/`CaseDto`，作为 adapter 的 `static` 嵌套 record），以 `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)` 映射；adapter 负责领域类型 ↔ wire DTO 的转换。`mode` 在 wire 层以**小写 `String`** 承载（请求侧由 `CaseQueryMode.name().toLowerCase()` 写入 `RetrieveRequest.mode`，响应侧 `RetrieveResponse.mode` 经 `valueOf(toUpperCase)` 还原），从而 `CaseQueryMode` 领域枚举保持无 Jackson 注解。领域类型（port API）保持 camelCase、不带 Jackson 注解。

**被否**：让领域记录直接背 Jackson snake_case 注解——把传输细节泄进 port API，污染 step-3 的消费类型。

### 3.5 失败语义与默认开关

- **失败语义**：传输失败、非 2xx、`503 index_missing` → adapter 抛 typed `CaseGraphUnavailableException`；`200` 且 `cases: []` → 正常返回空列表（非异常）。如何降级（advisory 仍出结果）由 step-3 决定，不在本步。
- **默认 `enabled=false`**：`llm.case-graph.enabled` 默认 false（opt-in）。既满足 DoD #5「禁用时 bean 不加载、无回归」，又天然支撑课程 demo 的「前后对比」（翻 true 启用）。

**被否**：默认 true——sidecar 未起或 index 未 bootstrap 的环境会在装配/调用期报错，破坏既有链路的回归安全。

### 3.6 条件装配复刻 `MemoryGraphLoader`

新增 `CaseGraphConfig`（`@Configuration`），以 `@Bean @ConditionalOnProperty(prefix="llm.case-graph", name="enabled", havingValue="true")` 产出 `HistoricalCaseQueryPort`。禁用时 bean 不存在。**同一 flag** 也是 step-3 工具注册的门控（step-3 消费）。

---

## 4. 包与类型设计

```
llm/agent/casegraph/
  HistoricalCaseQueryPort.java       # 端口接口
  HistoricalCaseQuery.java           # 查询 record（领域类型）
  CaseQueryMode.java                 # enum LOCAL/GLOBAL/HYBRID
  HistoricalCaseResult.java          # 结果 record（领域类型）
  HistoricalCase.java                # 单条案例 record
  CaseRetrievalMetrics.java          # 延迟/token（可空，主要供日志）
  CaseGraphUnavailableException.java # 检索不可用的 typed 异常
  LightRagCaseGraphAdapter.java      # 实现：HTTP 调用 + 领域↔wire 映射；wire DTO 为其内部 static record
  CaseGraphConfig.java               # @Configuration + 条件 @Bean

llm/config/
  CaseGraphProperties.java           # @ConfigurationProperties(prefix="llm.case-graph")
```

wire DTO（`RetrieveRequest` / `Situation` / `RetrieveResponse` / `CaseDto`）作为 `LightRagCaseGraphAdapter` 的 **`static` 嵌套 record** 定义，仅适配器自身可见，传输细节不外泄。不放独立子包——Java 子包是不同 package，父包类无法访问其 package-private 类型。

### 4.1 端口接口

```java
public interface HistoricalCaseQueryPort {
    HistoricalCaseResult findSimilarCases(HistoricalCaseQuery query);
}
```

### 4.2 领域类型（port API，camelCase，无 Jackson 注解）

```java
public record HistoricalCaseQuery(
        EncounterType encounterType,        // 主召回特征（复用既有枚举）
        OwnShipRole ownShipRole,
        VisibilityCondition visibilityCondition,
        RiskLevel riskLevel,
        String waterArea,                   // 受控词表字符串，可空
        String ownShipType,                 // 辅助 rerank，可空
        String targetShipType,              // 辅助 rerank，可空
        int targetCount,
        String queryText,                   // 自然语言 query，可空
        CaseQueryMode mode,                 // 可空 → adapter 用 properties 默认
        Integer topK                        // 可空 → adapter 用 properties 默认
) {}

public enum CaseQueryMode { LOCAL, GLOBAL, HYBRID }   // 领域枚举，无 Jackson 注解；小写转换由 wire 层承担

public record HistoricalCaseResult(
        CaseQueryMode mode,
        String queryEffective,
        List<HistoricalCase> cases,         // step-3 主消费对象
        String answer,                      // 可空，主要供 step-4；Java 侧不强制使用
        CaseRetrievalMetrics metrics        // 可空
) {}

public record HistoricalCase(
        String caseId,
        String title,
        double relevance,
        String waterArea,
        String visibility,
        String ownShipRole,
        String encounterType,
        String riskLevel,
        String targetSummary,
        List<String> colregsRules,
        String outcome,
        String actionDigest,
        String lesson
) {}

public record CaseRetrievalMetrics(long latencyMs, Integer promptTokens, Integer completionTokens) {}
```

> `HistoricalCase` 的 `ownShipRole`/`encounterType`/`riskLevel`/`outcome` 以 `String` 承载（案例图返回的描述性值，非本系统判定结果），不强转枚举，避免 sidecar 语料越界值导致反序列化失败；step-3 按字符串组装 evidence。注意这与请求侧 `HistoricalCaseQuery` 不同：请求侧由本系统 snapshot 派生，值受控，故直接用 `OwnShipRole`/`EncounterType`/`RiskLevel`/`VisibilityCondition` 枚举。

### 4.3 适配器签名与行为

```java
public class LightRagCaseGraphAdapter implements HistoricalCaseQueryPort {
    public LightRagCaseGraphAdapter(RestTemplate restTemplate, CaseGraphProperties properties, ObjectMapper objectMapper) { ... }

    @Override
    public HistoricalCaseResult findSimilarCases(HistoricalCaseQuery query) { ... }
}
```

`findSimilarCases` 行为：① 校验 `queryText` 与结构化特征不同时为空（否则不发请求，抛 `IllegalArgumentException`）；② 领域 query → `RetrieveRequest`（mode/topK 缺省时填 properties 默认；结构化特征装入 `Situation`）；③ POST `{baseUrl}/retrieve`；④ 非 2xx / `503` / 传输异常 → `CaseGraphUnavailableException`；⑤ `RetrieveResponse` → `HistoricalCaseResult`，`cases` 逐条映射为 `HistoricalCase`。adapter 不做任何检索/排序/截断逻辑（截断已由 sidecar 按 `top_k` 完成）。

### 4.4 配置类

```java
@Data @Component @ConfigurationProperties(prefix = "llm.case-graph")
public class CaseGraphProperties {
    private boolean enabled = false;
    private String url = "http://127.0.0.1:8100";
    private long timeoutMs = 10000L;
    private CaseQueryMode defaultMode = CaseQueryMode.LOCAL;
    private int defaultTopK = 5;
}
```

```java
@Configuration @RequiredArgsConstructor
public class CaseGraphConfig {
    private final CaseGraphProperties properties;
    private final ObjectMapper objectMapper;

    @Bean
    @ConditionalOnProperty(prefix = "llm.case-graph", name = "enabled", havingValue = "true")
    public HistoricalCaseQueryPort historicalCaseQueryPort() {
        RestTemplate restTemplate = buildRestTemplate(properties.getTimeoutMs());
        return new LightRagCaseGraphAdapter(restTemplate, properties, objectMapper);
    }
}
```

---

## 5. HTTP 映射（对照 step-1 §6.2）

| 领域 `HistoricalCaseQuery` | wire `RetrieveRequest`（snake_case） |
|---|---|
| `queryText` | `query` |
| `ownShipRole`/`encounterType`/`waterArea`/`visibilityCondition`/`targetCount`/`riskLevel` | 装入嵌套 `situation.{own_ship_role,encounter_type,water_area,visibility,target_count,risk_level}` |
| `ownShipType`/`targetShipType` | 装入嵌套 `situation.{own_ship_type,target_ship_type}`，供 sidecar 辅助 rerank |
| `mode`（缺省 → `defaultMode`） | `mode`（小写） |
| `topK`（缺省 → `defaultTopK`） | `top_k` |

| wire `RetrieveResponse` | 领域 `HistoricalCaseResult` |
|---|---|
| `mode` / `query_effective` / `answer` | 同名映射 |
| `cases[]` | `cases[]`（`case_id→caseId`、`colregs_rules→colregsRules`、`action_digest→actionDigest` 等）|
| `metrics.{latency_ms,tokens.prompt,tokens.completion}` | `CaseRetrievalMetrics` |

枚举透传：`ownShipRole`/`encounterType`/`visibilityCondition`/`riskLevel` 装入 `situation` 时取 `enum.name()`（`GIVE_WAY`/`WARNING` 等），与 sidecar 受控集合一致（step-1 §5 枚举对齐，含 `risk_level ← RiskLevel`）。`mode` 由 adapter 在 wire 层转小写写入 `RetrieveRequest.mode`（`String`），匹配 `local`/`global`/`hybrid`；`CaseQueryMode` 领域枚举本身不带 Jackson 注解。

---

## 6. 配置改动

`application.properties` 新增（默认禁用，opt-in）：
```
llm.case-graph.enabled=false
llm.case-graph.url=http://127.0.0.1:8100
llm.case-graph.timeout-ms=10000
llm.case-graph.default-mode=LOCAL
llm.case-graph.default-top-k=5
```
本地启用经 `application-local.properties`（gitignored）覆写 `llm.case-graph.enabled=true`，与 demo 前后对比一致。

---

## 7. 测试计划

| 层级 | 测试 | 手段 | 门控 |
|---|---|---|---|
| 适配器契约 | `LightRagCaseGraphAdapterTest`：stub `POST /retrieve` 返回 step-1 §6.2 样例 JSON → 断言 `HistoricalCaseResult` 正确映射（cases 条数、`caseId`、`relevance`、`colregsRules`、`mode`）；stub `503` → 断言 `CaseGraphUnavailableException`；stub `cases:[]` → 断言空列表非异常；`queryText` 与结构化特征皆空 → 断言 `IllegalArgumentException` 且不发请求 | `MockRestServiceServer` 绑定注入的 `RestTemplate` | 是 |
| 条件装配 | `CaseGraphConfigTest`：`enabled=true` → 上下文含 `HistoricalCaseQueryPort` bean；`enabled=false`/缺省 → 不含该 bean | `ApplicationContextRunner` **仅装 `CaseGraphConfig`**，提供 `CaseGraphProperties` 与 stub `ObjectMapper`，按 `llm.case-graph.enabled` 取值断言 bean 在/不在 | 是 |
| 映射单元 | wire DTO 的 snake_case 序列化/反序列化往返（`own_ship_role`↔`ownShipRole`、`top_k`↔`topK`、`mode` 小写 `String`↔`CaseQueryMode`）| `ObjectMapper` 直测 | 是 |

全部测试不依赖真实 sidecar / LLM，可在 CI 跑。端到端（真实 sidecar）属 step-3 的 advisory 验收，本步不承担。

**关于「`GraphQueryPort` 无回归」**：不在条件装配测试里 co-load `MemoryGraphLoader`（它另需 `ResourceLoader` / `LlmProperties` 并会读取真实 `llm.graph.resource-path`，混入会让本测试变脆）。该非回归由两点保证：(i) `CaseGraphConfig` 与 `MemoryGraphLoader` 是相互独立的 `@Configuration`、各自独立 flag、无共享 bean，案例图开关在结构上不触及规则图；(ii) 既有规则图测试在本步不被改动、保持绿。如需一道显式护栏，可加一个 `llm.case-graph.enabled=false` 的最小 `@SpringBootTest` 冒烟，确认上下文照常加载且 `GraphQueryPort` 仍在——作为可选项，不作门控。

---

## 8. 文件影响清单

新增：
- `llm/agent/casegraph/` 下：`HistoricalCaseQueryPort`、`HistoricalCaseQuery`、`CaseQueryMode`、`HistoricalCaseResult`、`HistoricalCase`、`CaseRetrievalMetrics`、`CaseGraphUnavailableException`、`LightRagCaseGraphAdapter`（含 4 个 wire DTO 作为 static 嵌套 record）、`CaseGraphConfig`
- `llm/config/CaseGraphProperties.java`
- 测试：`LightRagCaseGraphAdapterTest`、`CaseGraphConfigTest`、wire DTO 映射测试

修改：
- `application.properties`：新增 `llm.case-graph.*`（5 行，默认禁用）

不修改：
- `GraphQueryPort`、`MemoryGraphAdapter`、`MemoryGraphLoader`、`llm.graph.*` 配置（scope ceiling）
- `AgentToolRegistry`、advisory、prompt、`EVENT_SCHEMA.md`（step-3）

---

## 9. 不变式

- `HistoricalCaseQueryPort` 与 `GraphQueryPort` 互不引用、互不替代；案例图禁用不影响规则图。
- adapter 不含检索/排序/截断逻辑（全部在 sidecar）；adapter 只做映射与 HTTP。
- 领域类型（port API）不带 Jackson 注解；传输细节封在 adapter 的 static 嵌套 wire record 内。
- `enabled=false` 时 `HistoricalCaseQueryPort` bean 不存在，应用启动与既有链路与 v1.0 一致。
- step-2 不新增 agent 工具、不改 advisory / prompt / SSE。

---

## 10. 本步不做（显式分类）

| 项 | 分类 | 何时/何处 |
|---|---|---|
| `query_historical_case_graph` 工具注册 + 从 `AgentSnapshot` 构造 `HistoricalCaseQuery` | deferred | step-3 |
| advisory evidence 注入 + prompt 契约 + source-grounding 校验 + `EVENT_SCHEMA.md` | deferred | step-3 |
| 检索失败时 advisory 的降级策略 | deferred | step-3（消费 `CaseGraphUnavailableException`）|
| 结构化 evidence 协议 | not doing | overview non-goal（保持 `string[]`）|
| 检索/排序/rerank 逻辑下沉到 Java | not doing | scope ceiling（逻辑属 sidecar，step-1）|
| 真实端到端（起 sidecar）验收 | deferred | step-3 advisory 验收 / step-4 评测 |

无新增 `docs/TODO.md` 条目：deferred 均由 step-3/4 承接，not-doing 均被 overview non-goal 覆盖。

---

## 11. 偏离记录

- **2026-06-10 / `doc-code-inconsistency`**：§4.2 已将 `ownShipType` / `targetShipType` 定义为辅助 rerank 字段，但原 §5 映射表遗漏对应 wire 字段。实现按既定字段职责透传为 `situation.own_ship_type` / `situation.target_ship_type`，并同步补全 §5。该修正不改变 step objective、模块边界、输出契约或后续步骤假设，因此不追加 overview `Active Deviations`。
- 除上述映射表补正外，本步在 overview step-2 的 objective / deliverables / DoD / scope ceiling 内完成。§3.3 的 `RestTemplate` 注入式构造是 step-2 自有的实现选择（可测性），不改变 overview 任何条目，非偏离。

---

## 12. 风险与假设

- **假设**：step-1 §6.2 的 HTTP 契约稳定；若 step-1 实现期契约微调，以 step-1 为准并同步本步 wire DTO（契约单一真值在 step-1）。
- **假设**：sidecar 默认端口 8100 与 step-1 compose 映射一致（`llm.case-graph.url` 可覆写）。
- **风险**：sidecar 返回的 `outcome`/`own_ship_role` 等描述值可能超出本系统枚举集合 → 领域 `HistoricalCase` 以 `String` 承载这些字段（§4.2），不强转枚举，规避反序列化失败。
- **风险**：`timeout-ms=10000` 对首次/冷查询可能偏紧 → 由属性可调；sidecar 热加载（step-1 §3.2）使常态查询远低于该阈值。
