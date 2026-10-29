# v1.3 step-2 — AIS 航道规划与航线复核工具

> 文档状态：draft
> 执行状态：not started
> 最后更新：2026-10-29
> 上游：[`OVERVIEW.md`](./OVERVIEW.md) § Step Breakdown / step-2
> 前置：[`step-1.md`](./step-1.md)（completed）
> 下游：`/implement-step-plan`

---

## 0. 上游锚点（引自 overview，不在本文重定义）

- **目标**：基于真实内河 AIS 历史通航构建航道网格与确定性规划器，并以 agent 工具暴露“规划”与“复核”，使用户可在对话中得到并采纳一条附复核结论的计划航线。
- **Step-level DoD**：版本 DoD 第 5、6 项成立；规划器与复核工具有针对性测试；现有测试全部通过。
  - 版本 DoD 第 5 项：航道网格由入仓脚本从 `segments.parquet` 可复现构建，网格产物入仓；评测网格只用 `train`，`val` 调参，`test` 只用于最终评测；报告平均横向偏差与航线落在历史航道内的比例（不设门槛）；演示网格可用全量数据，须与评测网格分别标识，评测结论只引用评测网格。
  - 版本 DoD 第 6 项：chat agent 模式下 agent 调用 `plan_route` 与 `check_route` 得到附复核结论（含未复核条件）的候选航线；用户显式采纳后成为当前计划航线并驱动 `NOTICE_ALERT`；工具调用入参与结果摘要在前端可见。
- **约束**：LLM 只向工具传递起终点、意图参数与待复核航线标识，不传递或改写航点坐标；采纳必须由用户显式触发；航道网格只由历史 AIS 统计得出；`split` 沿用 v1.2 按船划分，报告注明不衡量跨时段泛化。
- **范围上限**：v1.2 数据集武汉包围盒；单条航线，不做多候选排序；复核条件限于航标通告与历史航道两类。

---

## 1. 现状

- **数据**：`trajectory-service/runtime/dataset/segments.parquet`（78 MB，不入仓）。列为 `mmsi, seg_id, t, dt, lon, lat, x, y, sog, cog, turn_rate_deg_s, is_bend, split`。只含运动点（SOG ≥ 0.5 kn），按 30 s 抽稀。`split` 由 `clean_segment.py` 按 MMSI 取模划分：train 9722 段 / val 1336 段 / test 1207 段。包围盒为经度 113.5～115.1、纬度 29.8～31.0，参考点 (114.3, 30.5)（`manifest.json`）。
- **Python 环境**：本机只有 Homebrew Python 3.12，没有 pandas 和 pyarrow；`trajectory-service/requirements.txt` 含 pandas、pyarrow，也含 torch。`notice-pipeline/` 只用标准库。根 `.gitignore` 已排除 `venv/`。
- **航线**：`PlannedRouteRepository` 以 `AtomicReference` 持有启动时加载的航线，只有 `current()`。`NoticeAlertMonitor` 按引用比较发现航线变化后发布 `CLEARED / ROUTE_CHANGED` 并重建 `RouteAtonIndex`，替换航线时无需改动提醒逻辑。
- **航标与航线求交**：`RouteAtonIndex.build` 把航标附近的航线近似为直线求进入点和离开点（step-1 §2.5、§8）。step-1 的演示航线只有 4 个航点，近似误差可以忽略。
- **agent 工具**：`AgentToolRegistry` 收集全部 `AgentTool` bean。chat agent（`LlmChatService.runAgentChat`）和 advisory（`AdvisoryService`）都调用 `AgentLoopOrchestrator.run(LlmTaskType.AGENT, …)`，拿到的是同一份工具列表。
- **`AGENT_STEP`**：`AgentStepEvent(stepId, toolName, status, message)`，载荷只有工具名、状态和文案（`EVENT_SCHEMA.md` §6.11），不带入参和结果。前端 `ChatMessageList.AgentStepTrace` 按 `reply_to_event_id` 展示步骤列表。
- **模拟器**：`--route` 读取 classpath 中的航线文件（step-1 §6.2）。step-1 §2.1 约定由 step-2 增加 `--route-url`。
- **deck.gl**：`3919c63` 已把 4 个 `@deck.gl/*` 包锁定为 9.2.6，`node_modules` 里的实际版本一致。`PathStyleExtension` 虚线是否已恢复，还没有在浏览器里验证（step-1 §11.3）。

---

## 2. 设计决策

### 2.1 overview Open Question：规划器运行形态

**决定：规划器用 Java 实现，在 `map-service` 进程内运行，读取离线构建的网格产物。是否最终保持进程内，由 §6 阶段 2 的实测按下表判定，结果写入实施记录。**

| 指标（演示网格，JVM 默认参数） | 进程内的条件 |
|---|---|
| 网格资源文件（gzip） | ≤ 20 MB |
| 加载后网格占用堆内存 | ≤ 200 MB |
| 单次规划耗时（val 起终点，预热后） | p95 ≤ 500 ms |

- 任一条不满足时，先在 overview 已限定的范围内调整：加大网格尺寸，或把演示网格裁剪到演示航段所在的局部包围盒。仍不满足才考虑 sidecar，此时按偏离流程追加到 overview Active Deviations。
- **按量级估算**：长江武汉段长约 150 km、宽 1～1.5 km，按 100 m 网格约有 2 万个有通航的格子，按 50 m 约 8 万个。每格存约 12 个数值，A* 在 10⁵ 节点上的耗时为毫秒到百毫秒量级。预期满足进程内条件。
- **未采用：Python sidecar。** 会新增部署依赖和网络往返，对照 overview 的倾向没有收益。

### 2.2 规划算法只实现一份（Java），评测由 Java 驱动

- 规划器核心（网格加载、搜索、简化、偏航限推导、航道内检查）是不依赖 Spring 的纯 Java 类。`map-service` 运行时和评测运行器调用的是同一份代码。
- Python 只负责三件事：构建网格、从 val/test 抽取起终点、用真实航迹计算偏差指标并生成报告。
- **未采用：Python 实现规划器用于评测、Java 另实现用于运行时。** 两份实现会出现结果不一致，评测结论也就不再对应系统里实际运行的规划器。

### 2.3 流水线环境：独立目录 `waterway-pipeline/` + 本地 venv

- 新增顶层目录 `waterway-pipeline/`，结构仿照 `notice-pipeline/`。`requirements.txt` 只列 `numpy`、`pandas`、`pyarrow`，在本目录建 `venv/`（已被 `.gitignore` 覆盖），README 写明创建命令。
- **未采用：放进 `trajectory-service/scripts/`、复用其依赖。** 为读 parquet 要装 torch，且航道网格不属于轨迹预测服务的职责。
- **未采用：给 `notice-pipeline` 加依赖。** 它刻意只用标准库，两条流水线的数据源和产物彼此无关。

### 2.4 网格：按航段计数、带方向直方图的稀疏栅格

- **投影**：以 manifest 参考点 (114.3, 30.5) 做等距圆柱投影，`x = R·cos(φ0)·(λ−λ0)`、`y = R·(φ−φ0)`，取 R = 6 371 008.8 m。流水线不使用 parquet 中现成的 `x, y`，而是按此公式从 `lon, lat` 重新计算，使 Java 端用同一公式即可复现。投影参数写入构建清单。包围盒南北跨度 1.2°，等距投影的尺度误差在 ±1% 以内；规划和检查两端用同一投影，误差不影响一致性。
- **栅格化**：每个航段相邻两点之间的线段用格子遍历（Amanatides–Woo，经过格角时两侧格子都计入）求出实际穿过的全部格子。按 30 s 抽稀、10 kn 航速时相邻点约相距 150 m，只落原始点会留下空洞。与 §2.5 简化约束使用同一遍历规则（review 修订）。
- **每格统计**：
  - `n_seg`：经过的不同航段数，不按点计数，避免慢船和多点航段主导统计；
  - `dir[8]`：经过该格的航段按 8 个 45° 方向桶统计航段数（同一航段同一方向桶只计一次）。方向取所在线段两端点的航迹方位，不取 `cog` 字段：清洗脚本允许 `cog` 为 NaN，而航迹方位总是有定义；
  - `sog_med`：落在该格的**原始点**中有效 SOG 样本的中位数。有效样本为非 NaN 且在 [0.5, 30] kn 内：下限与清洗时的运动阈值一致，上限剔除内河不可能出现的异常值。只被线段遍历经过、没有原始点落入的格子，以及只有无效样本的格子，没有 SOG 统计，在 csv 中写空串，Java 读为 `NaN`，表示缺失（review 修订）。
- **可通航格**：`n_seg ≥ min_seg` 的格子。`min_seg` 默认 3，由 val 调参确定。
- **方向直方图的作用**：长江航道内上行船和下行船分道航行，规划时据此偏好与本船航向一致的车道，同时抑制渡船横越航迹造成的穿河捷径。

### 2.5 规划器：方向感知 A* → 约束简化 → 航段属性

```java
record PlanRequest(double originLon, double originLat, double destLon, double destLat, Double speedKn) {}

sealed interface PlanOutcome {
    record Planned(PlannedRoute route, PlanDiagnostics diagnostics) implements PlanOutcome {}
    record Failed(PlanFailure reason, String detail) implements PlanOutcome {}
}
enum PlanFailure { ORIGIN_OFF_CHANNEL, DESTINATION_OFF_CHANNEL, NO_PATH, TOO_SHORT }

final class RoutePlanner {
    RoutePlanner(WaterwayGrid grid, PlannerParams params);
    PlanOutcome plan(PlanRequest request);
}
```

1. **端点吸附**：起点和终点分别吸附到 `snap_radius_m`（默认 300 m）内最近的可通航格中心，找不到时返回 `*_OFF_CHANNEL`。吸附后两端直线距离小于 500 m 时返回 `TOO_SHORT`。
2. **搜索**：在可通航格上做 8 邻接 A*，启发式取欧氏距离（可采纳）。从格 u 走到相邻格 v、方向为 θ 时的代价：

   ```
   cost(u→v) = len(u,v) × (1 + w_density / (1 + n_seg(v)))
                         × (1 + w_direction × (1 − compat(v, θ)))
   compat(v, θ) = (dir 桶中与 θ 相差 ≤ 45° 的三个桶之和) / max(1, Σ dir)
   ```

   `w_density`、`w_direction` 由 val 调参确定（§5.3），默认值取调参结果。
3. **简化**：对格子中心路径做 Douglas–Peucker 简化，容差 `simplify_tolerance_m`（默认 30 m）。对简化后的每个航段，用与 §2.4 相同的格子遍历求出它实际穿过的全部格子（含擦过格角的两侧格子）。只要其中有不可通航格，就在原路径对应区间中偏离该航段最远的点处保留航点，然后重新检查，直到每个航段穿过的格子全部可通航。原路径的相邻格子中心之间也满足这一点，因此迭代必然终止。
   - **不用固定步长采样**：采样会漏掉航段短暂穿过的格子。review 给出的反例中，50 m 网格、30 m 容差下，捷径穿过不可通航格约 10.4 m，所有 25 m 采样点都没有落进该格。§2.10 的复核指标仍用采样，只用于统计比例，不承担“整段可通航”的保证（review 修订）。
4. **航段属性**：
   - **偏航限**：沿航段每 50 m 取样，在样点处向左舷、右舷分别沿法向以 ½ 格步长外扩，直到遇到不可通航格或达到 1500 m，得到两侧半宽。`xtd_port_m` / `xtd_starboard_m` 取各样点同侧半宽的第 10 百分位，再限制在 [30, 500] m。这样偏航限直接来自历史航道横向分布（overview 交付物），取低分位是为了在航道收窄处保持保守。
   - **航速**：用户指定的 `speed_kn` 优先。未指定时，按以下顺序确定，结果按 0.5 kn 取整并限制在 [3, 15] kn：
     1. 取航段穿过的格子中 `sog_med` 非缺失者的中位数；
     2. 该航段没有任何有效值时，取整条航线全部穿过格中有效 `sog_med` 的中位数；
     3. 整条航线都没有有效值时，取 `navigation.planner.default-speed-kn`（默认 8）。
     
     用了第 2、3 级回退的航段记入 `PlanDiagnostics.speed_fallback_legs`，`plan_route` 结果中给出该航段计数。产出的 `speed_kn` 不会是 NaN，因此不会违反 `PlannedRouteRepository` 的非负校验（review 修订）。
   - **转向半径**：首末航点为 0；中间航点取 `min(300, 0.4 × 相邻两航段中较短者)`，只写入数据，不参与几何计算（§2.9）。
5. **输出**：沿用 step-1 的 `PlannedRoute`。`route_id = "cand-" + UUID 前 8 位`，`synthetic = true`，`name` 由工具层按起终点名称生成。`PlanDiagnostics` 包括 A* 展开节点数、耗时、吸附距离和简化前后航点数，用于评测与实施记录，不下发给前端。

### 2.6 起终点只用标识，不用坐标

- 为满足“LLM 不传递航点坐标”，`plan_route` 的起终点只接受两种形式：`own_ship`（取 `AgentSnapshot` 冻结的本船位置），或地名表中的 `place_id`。
- 地名表 `resources/navigation/wuhan-places.json` 人工整理 8～12 个演示用地点（长江大桥、二桥、天兴洲、阳逻港、白沙洲大桥、沌口等）。每条记录含 `place_id`、`name`、`aliases`、`lon`、`lat`，文件头写明“坐标为实施方对照卫星图读取的演示用端点，非官方地名坐标”。
- 加载校验：每个地点在演示网格中必须能在 `snap_radius_m` 内吸附到可通航格，否则启动失败，由测试覆盖。
- 工具定义的 `parameters` 中，`place_id` 用 `enum` 列出全部标识，`description` 附上名称与别名，供 LLM 把用户说的地名映射到标识。
- **未采用：让前端在地图上点选终点并随 `CHAT` 上传坐标。** 需要扩展 `CHAT` 载荷和地图交互，超出“对话规划”的最小闭环；放在 §8 not doing。

### 2.7 候选航线与采纳：内存候选池 + REST 显式采纳

- **候选池**：新增 `CandidateRouteStore`，保存 `plan_route` 产出的候选航线和最近一次 `check_route` 结果。最多 20 条，按插入顺序淘汰；TTL 30 分钟。进程重启后清空。
- **采纳**：`POST /api/v2/navigation/route/active`，请求体为 `{"route_id": "cand-…"}`，只在前端用户点击“采纳”时调用。
  - 候选不存在或已过期 → 404 `CANDIDATE_NOT_FOUND`；
  - 候选还没有复核结果 → 409 `CANDIDATE_NOT_CHECKED`，保证被采纳的航线一定附带复核结论；
  - 成功时调用 `PlannedRouteRepository.replace(route)`（先执行既有校验），返回新航线。之后 `NoticeAlertMonitor` 在下一次本船更新时自行发布 `ROUTE_CHANGED` 并重建索引。
- **agent 不能替换航线，由结构保证**：没有任何 agent 工具持有 `PlannedRouteRepository` 的写入口，替换只能经过上面的 REST 接口。测试断言执行 `plan_route` / `check_route` 后 `current()` 的引用不变。
- **未采用：WebSocket 上行 `ADOPT_ROUTE` 消息。** 采纳是一次幂等的状态写入，与对话会话无关；REST 能直接复用 `NavigationController`，不需要改动 chat 协议。
- **未采用：采纳时自动重新复核。** 复核是 agent 可见的工具结果，前端采纳卡片上展示的就是这次复核的结论；采纳时再跑一次会产生用户没有看到的结论。复核结果的时效由候选 TTL 约束。

### 2.8 工具按调用方分组，advisory 看不到规划工具

- 新增 `enum AgentToolScope { CHAT_AGENT, ADVISORY }`。`AgentTool` 增加 `default Set<AgentToolScope> scopes()`，默认返回两者都有，现有工具行为不变。`plan_route`、`check_route` 只返回 `CHAT_AGENT`。
- `AgentToolRegistry` 增加 `getToolDefinitions(scope)`；`execute(call, snapshot, scope)` 对不在该 scope 内的工具返回 `UNKNOWN_TOOL`，与现有的未知工具处理一致。
- `AgentLoopOrchestrator.run` 增加 `AgentToolScope` 参数：`LlmChatService` 传 `CHAT_AGENT`，`AdvisoryService` 传 `ADVISORY`。不带 scope 的旧重载默认用 `ADVISORY`，保证 advisory 拿到的工具列表与改动前完全相同。`AdvisoryPromptBuilder` 判断 case graph 是否可用的逻辑改为按 `ADVISORY` 取工具列表。
- **理由**：advisory 由风险事件自动触发，规划航线不属于会遇避让建议；两个工具出现在 advisory 的工具列表里，只会扩大模型的误调用面。

### 2.9 航标求交改为逐段精确计算，每次接近单独成项；转向半径不参与几何

- 规划出的航线在弯道处航点密集、航段较短，step-1 的直线近似在转向点附近误差会变大（step-1 §8 中约定由 step-2 评估）。弯曲航线还可能两次经过同一航标的缓冲圈；现有索引只取最近投影点，只能表示一次接近。
- **改法**：
  - `RouteAtonIndex.build` 对每个航段求“线段 ∩ 以航标为圆心、buffer 为半径的圆”，得到沿线区间。
  - 按沿线位置排序后，只合并首尾相接或重叠的区间（相邻航段在转向点处衔接的情况），**不跨间隙合并**。
  - 合并后的每个连续区间是一次**接近**，生成一个 `Entry`。`Entry` 增加 `approachIndex`（同一航标按沿线顺序从 0 编号），`s_a` 与 `r` 取该区间内到航标最近的点。
  - 例：区间 `[100, 200]` 和 `[1000, 1100]` 生成两个 `Entry`，中间 800 m 不处于任何接近状态（review 修订）。
- **提醒语义**：
  - `NoticeAlertMonitor` 的接近状态键从 `atonId` 改为 `atonId#approachIndex`。每次接近独立走 step-1 §2.6 的 `IDLE → ACTIVE → PASSED`，各自生成 `alert_id`。
  - `PASSED` 回到 `IDLE` 的条件改为 `s_own < 本次接近的 s_in − buffer`。
  - 这样“同一航标在同一次接近中只提醒一次”仍然成立；第二次接近是新的一次接近，会再提醒一次。
  - 两次接近同时落在前瞻窗口内时，各自发布提醒，ETA 按各自的 `s_in` 计算。
  - `NOTICE_ALERT` 载荷字段不变：`distance_along_route_m`、`lateral_offset_m` 取本次接近的 `s_a` 与 `r`。前端按 `alert_id` upsert，不需要改动。
- **未采用：只支持单次接近、检测到多次接近时报错或只取第一次。** 拆分后的状态机与单次接近的状态机完全相同，只是键变了，代价很小；而只取第一次会漏掉第二次经过同一航标的提醒。
- `turn_radius_m` 只写入数据，不参与几何计算（step-1 §7 中留给 step-2 决定）。规划航线的偏航限已经覆盖切弯偏差，再引入圆弧几何对提醒和复核都没有可见收益。归类为 not doing（§8）。

### 2.10 `check_route` 的复核项与输出

只复核 overview 限定的两类，结果是确定性计算得到的事实列表，不给出“通过 / 不通过”的结论，由 LLM 解释、用户判断。

- **航标通告**：复用 §2.9 的求交，buffer 取 `navigation.notice-alert.buffer-m`。
  - `VALID` 航标落在缓冲范围内的，列入 `hits`；每次接近一项，同一航标两次接近时出现两项，以 `approach_index` 区分；
  - 非 `VALID` 航标的最后已知位置落在缓冲范围内的，列入 `non_valid_nearby`，只说明状态，不作为命中；
  - 附上数据截止时间 `data_cutoff`。
- **历史航道**：沿航线每 25 m 采样，检查样点所在格是否可通航。输出：
  - `in_channel_ratio`；
  - 连续不在航道内的区间 `off_channel_stretches`（沿线起止距离）；
  - 方向冲突区间 `direction_conflict_stretches`：样点所在格 `compat < 0.2` 的连续区间，表示与历史主流方向相反。
- **未复核条件**：固定列出以下几项（代码常量，由测试断言其存在）：
  - `DEPTH`：武汉段无水深数据；
  - `BRIDGE_CLEARANCE`：无桥梁净空数据；
  - `AREA_RESTRICTIONS`：施工、管制、限速等区域型约束不在数据范围内；
  - `NOTICES_AFTER_CUTOFF`：快照截止后的通告变化未知；
  - `TRAFFIC_TEMPORAL`：历史航道来自 2022-05 约 11 天数据，未覆盖季节与水位变化；
  - `REAL_TIME_TRAFFIC`：未结合实时交通，由风险链路另行评估。

---

## 3. 数据契约

### 3.1 网格产物与构建清单

| 产物 | 位置 | 用途 |
|---|---|---|
| 评测网格 | `waterway-pipeline/data/grid-eval/{grid.csv.gz, grid_manifest.json}` | 只供评测运行器使用 |
| 演示网格 | `backend/map-service/src/main/resources/waterway/{grid-demo.csv.gz, grid-demo.manifest.json}` | 供 `map-service` 运行时使用 |

`grid.csv.gz` 列：`ix, iy, n_seg, d0, d1, d2, d3, d4, d5, d6, d7, sog_med`。`ix = floor(x / cell_m)`，`iy` 同理；方向桶 `d0` 以正北为中心（337.5°～22.5°），按顺时针排列。`sog_med` 缺失时为空串（§2.4），其余列不允许为空；加载时遇到非空且无法解析为有限数的值，判为数据错误并拒绝加载。

```json
{
  "schema_version": 1,
  "variant": "eval",
  "splits": ["train"],
  "source": {
    "path": "trajectory-service/runtime/dataset/segments.parquet",
    "sha256": "…",
    "dataset_manifest_params": { "…": "原 manifest.json params 原样拷贝" }
  },
  "projection": { "type": "equirectangular", "ref_lon": 114.3, "ref_lat": 30.5, "earth_radius_m": 6371008.8 },
  "params": { "cell_m": 100, "rasterize": "grid_traversal", "sog_valid_kn": [0.5, 30] },
  "stats": { "segments_used": 9722, "vessels_used": 0, "occupied_cells": 0, "cells_by_n_seg": { "1": 0, "2": 0, "3+": 0 } },
  "built_at": "<ISO 8601>",
  "builder": "waterway-pipeline build"
}
```

- `variant` 只能是 `eval`（必须 `splits == ["train"]`）或 `demo`（`splits == ["train", "val", "test"]`）。`build` 命令按 `variant` 固定 `splits`，不接受自由组合。
- `min_seg` 不写进网格，由使用方配置；网格保存原始计数，调参时不需要重建网格。

### 3.2 评测起终点与运行结果

- `waterway-pipeline/eval/od_{val,test}.csv`：`od_id, seg_id, mmsi, origin_lon, origin_lat, dest_lon, dest_lat, track_length_m`。
  - 只取对应 split 中首尾直线距离 ≥ 2 km 的航段，按 `seg_id` 排序后确定性抽样，每个 split 最多 300 条，种子写入文件头注释。
  - 真实航迹不入 csv，计算指标时从 parquet 按 `seg_id` 读取。
- `waterway-pipeline/eval/planner_{val,test}.csv`：由 Java 评测运行器写出，列为 `od_id, status, failure, waypoints`（`lon lat;lon lat;…`）`, length_m, in_channel_ratio, plan_ms, params_tag`。

### 3.3 工具 `plan_route`

入参（JSON Schema 要点）：

```json
{
  "origin": { "type": "string", "description": "own_ship 或 place_id" },
  "destination": { "type": "string", "enum": ["<place_id>", "…"] },
  "speed_kn": { "type": "number", "minimum": 3, "maximum": 15, "description": "可选；用户明确给出航速时填写" }
}
```

`origin` 的 `enum` 为 `own_ship` 加上全部 `place_id`。

成功结果：

```json
{
  "status": "OK",
  "route_id": "cand-1a2b3c4d",
  "name": "规划航线：本船当前位置 → 阳逻港",
  "origin": { "ref": "own_ship", "snap_distance_m": 42.0 },
  "destination": { "ref": "yangluo_port", "name": "阳逻港", "snap_distance_m": 85.0 },
  "length_m": 28640.0,
  "waypoint_count": 14,
  "legs_summary": { "speed_kn_min": 8.0, "speed_kn_max": 10.5, "xtd_min_m": 60.0, "speed_fallback_legs": 0 },
  "grid_variant": "demo",
  "note": "航点由确定性规划器基于历史 AIS 航道生成；需调用 check_route 复核后由用户在界面中采纳"
}
```

- 结果不含航点坐标。LLM 不需要坐标，也就没有机会在回复里复述或改写坐标；几何由前端通过 REST 获取。
- 失败结果：`status = "ERROR"`，`error_code` 取 `UNKNOWN_PLACE`、`OWN_SHIP_UNAVAILABLE`、`ORIGIN_OFF_CHANNEL`、`DESTINATION_OFF_CHANNEL`、`NO_PATH`、`TOO_SHORT`，附 `message`。

### 3.4 工具 `check_route`

入参：`{ "route_id": "cand-… | active" }`。`active` 表示当前计划航线。

```json
{
  "status": "OK",
  "route_id": "cand-1a2b3c4d",
  "route_kind": "CANDIDATE",
  "checked_at": "2026-10-29T10:00:00Z",
  "notice_check": {
    "buffer_m": 200,
    "data_cutoff": "2026-10-23",
    "hits": [
      { "aton_id": "…", "name": "武汉二桥#1红浮", "waterway": "汉口水道", "is_virtual": true,
        "last_action": "SET", "approach_index": 0, "along_route_m": 4250.0, "distance_to_route_m": 120.0,
        "notice_id": "t20260924_490370", "item_seq": 5 }
    ],
    "non_valid_nearby": []
  },
  "channel_check": {
    "grid_variant": "demo",
    "min_seg": 3,
    "sample_spacing_m": 25,
    "in_channel_ratio": 0.987,
    "off_channel_stretches": [ { "from_m": 120.0, "to_m": 170.0 } ],
    "direction_conflict_stretches": []
  },
  "unverified": [
    { "code": "DEPTH", "reason": "武汉段无水深数据" },
    { "code": "BRIDGE_CLEARANCE", "reason": "无桥梁净空数据" }
  ]
}
```

- 示例中 `unverified` 只列出两项，实际固定为 §2.10 的六项。
- 复核候选航线时，结果写回 `CandidateRouteStore`。
- 失败结果：`error_code` 取 `ROUTE_NOT_FOUND`（未知或已过期）。

### 3.5 REST

| 方法与路径 | 响应 |
|---|---|
| `GET /api/v2/navigation/routes/candidates/{route_id}` | `{ "route": <step-1 §3.1 结构>, "check": <§3.4 结果或 null>, "expires_at": "…" }`；不存在时 404 |
| `POST /api/v2/navigation/route/active` | 请求体 `{ "route_id": "…" }`；200 返回新的当前航线；404 `CANDIDATE_NOT_FOUND`；409 `CANDIDATE_NOT_CHECKED`。错误体为 `{ "error_code", "message" }` |
| `GET /api/v2/navigation/route/active` | 不变（step-1），返回最新采纳的航线 |

### 3.6 `AGENT_STEP` 扩展（`EVENT_SCHEMA.md` §6.11）

新增两个可选字段，旧客户端忽略即可：

| 字段 | 类型 | 出现时机 | 说明 |
|---|---|---|---|
| `arguments` | `object\|null` | `RUNNING`、`SUCCEEDED`、`FAILED` | 工具调用入参原样下发；序列化后超过 2048 字符时替换为 `{"_truncated": true}` |
| `result_summary` | `object\|null` | `SUCCEEDED` | 由 `AgentTool.summarize(payload)` 生成 |

- `summarize` 的默认实现：载荷序列化后不超过 2048 字符时原样返回；超过时返回 `{"_truncated": true, "status": …, "keys": [顶层键名]}`。
- `plan_route` 的摘要是 §3.3 结果去掉 `note` 的部分，必须包含 `route_id`。`check_route` 的摘要包含 `route_id`、`hits` 数量与名称、`in_channel_ratio`、不在航道内的区间数、`unverified` 的 code 列表。
- 前端依据 `tool_name == "plan_route"` 且 `status == "SUCCEEDED"` 时 `result_summary.route_id` 发现候选航线（§4.3）。
- `FINALIZING` 步骤两个字段都为 null。

---

## 4. 实现

### 4.1 流水线 `waterway-pipeline/`

| 命令 | 行为 |
|---|---|
| `build --variant eval\|demo [--cell-m 100]` | 读 parquet，按 variant 过滤 split，执行 §2.4 栅格化；`eval` 写入 `data/grid-eval/`；`demo` 写入后端资源目录。同时写构建清单，打印格子数和文件大小 |
| `od --split val\|test` | 按 §3.2 抽样起终点；拒绝 `--split train` |
| `report --split val\|test` | 读 `planner_{split}.csv` 与 parquet 中对应航段的真实航迹，计算 §5.2 指标，生成 `eval/report_{split}.md`。`test` 报告只读取 `params_tag` 与 `eval/tuning.md` 中定稿参数一致的运行结果 |

运行方式：在本目录建 venv 后执行 `python -m waterway_pipeline <cmd>`。parquet 路径默认取 `../trajectory-service/runtime/dataset/segments.parquet`，可用 `--parquet` 覆盖。

### 4.2 后端

| 类 | 职责 |
|---|---|
| `navigation/planner/WaterwayGrid`、`WaterwayGridLoader` | 纯 Java：读取 `grid.csv.gz` 与清单，校验 `schema_version`、投影参数，提供按 `(ix, iy)` 查格、投影和反投影。`loadForEvaluation` 要求 `variant == eval && splits == [train]`，否则抛异常 |
| `navigation/planner/RoutePlanner`、`PlannerParams`、`PlanRequest`、`PlanOutcome` | §2.5 |
| `navigation/planner/ChannelProfile` | 纯函数：沿线采样、计算是否可通航与方向兼容度（供 `check_route`、评测和偏航限推导共用） |
| `navigation/place/PlaceRepository` | 加载 `wuhan-places.json`，启动时做吸附校验 |
| `navigation/route/CandidateRouteStore` | §2.7 候选池；线程安全（工具在 LLM 线程上写入，REST 在 Web 线程上读取），用 `synchronized` 保护 `LinkedHashMap` |
| `navigation/route/PlannedRouteRepository` | 新增 `replace(PlannedRoute)`：执行既有 `validate`，然后 `current.set` |
| `navigation/check/RouteCheckService` | §2.10：组合 `RouteAtonIndex` 与 `ChannelProfile`，输出 §3.4 结构 |
| `navigation/notice/RouteAtonIndex`、`NoticeAlertMonitor` | §2.9：逐段求交、按接近拆分 `Entry`；接近状态键改为 `atonId#approachIndex` |
| `navigation/api/NavigationController` | 新增 §3.5 的两个接口 |
| `navigation/config/NavigationProperties` | 新增 `planner.*`、`candidate.*`、`places-resource` |
| `llm/agent/tool/builtin/PlanRouteTool`、`CheckRouteTool` | §3.3 / §3.4；`scopes()` 只返回 `CHAT_AGENT`；重写 `summarize` |
| `llm/agent/tool/AgentTool`、`AgentToolRegistry`、`AgentToolScope`、`AgentToolNames` | §2.8；新增 `PLAN_ROUTE`、`CHECK_ROUTE` |
| `llm/agent/AgentLoopOrchestrator`、`AgentStepEvent` | 增加 scope 参数；事件增加 `arguments`、`resultSummary` |
| `llm/transport/ws/AgentStepPayload` 与发送处 | 新增 §3.6 字段 |
| `llm/agent/chat/ChatAgentPromptBuilder` | 增加航线规划说明：规划请求先调 `plan_route` 再调 `check_route`；回复必须列出未复核条件；不得输出坐标；提示用户在界面中点击采纳 |
| `llm/service/LlmChatService`、`llm/agent/advisory/AdvisoryService`、`AdvisoryPromptBuilder` | 按 §2.8 传入 scope |

配置：

```properties
navigation.planner.enabled=true
navigation.planner.grid-resource=classpath:waterway/grid-demo.csv.gz
navigation.planner.grid-manifest-resource=classpath:waterway/grid-demo.manifest.json
navigation.planner.min-seg=3
navigation.planner.w-density=<调参结果>
navigation.planner.w-direction=<调参结果>
navigation.planner.snap-radius-m=300
navigation.planner.simplify-tolerance-m=30
navigation.planner.default-speed-kn=8
navigation.places-resource=classpath:navigation/wuhan-places.json
navigation.candidate.max-entries=20
navigation.candidate.ttl-minutes=30
```

`planner.enabled=false` 时不加载网格，两个工具与候选、采纳接口都不注册，step-1 的功能不受影响。后端测试使用 `src/test/resources/navigation/` 下的小网格夹具，不依赖演示网格。

### 4.3 前端

- **类型**：`types/schema.d.ts` 中 `AgentStepPayload` 增加 `arguments?`、`result_summary?`；新增 `CandidateRouteResponse`、`RouteCheckResult`。
- **步骤可展开**：`ChatMessageList.AgentStepTrace` 中，带 `arguments` 或 `result_summary` 的步骤可以展开，以等宽字体显示格式化 JSON，默认折叠（承接原 TODO §4 的审计视图）。
- **候选航线卡**：新增 `components/Dashboard/CandidateRouteCard.tsx`，挂在对应 AI 回复下方。
  - 出现条件：该回复的步骤中有成功的 `plan_route`。卡片按 `route_id` 调用 `GET …/candidates/{id}` 拉取航线和复核结果。
  - **刷新约定**：agent 循环在 `plan_route` 成功后立即发送 `SUCCEEDED`，随后才执行 `check_route`，所以首次拉取通常拿到 `check: null`。收到 `tool_name == "check_route"`、`status == "SUCCEEDED"` 且 `result_summary.route_id` 等于该候选的 `AGENT_STEP` 时，前端重新拉取该候选，用返回的 `check` 覆盖本地状态。复核结果以 REST 返回为准，不用摘要直接拼装，因为摘要可能被截断。同一 `route_id` 的多次复核按最后一次刷新。被复核的是 `active` 时不触发候选刷新（review 修订）。
  - 内容：长度、航点数、航速范围、复核摘要（命中航标、航道内比例、未复核条件列表）。
  - 操作：“在地图上显示 / 隐藏”，以及“采纳为计划航线”（没有复核结果时禁用，并提示“尚未复核”）。
  - 采纳成功后更新 `useNavigationStore.route`，卡片显示“已采纳”；采纳失败时显示错误码对应的文案。
- **地图**：`navigationLayers.ts` 新增候选航线 `PathLayer`，用 `PathStyleExtension` 画虚线，与实线的当前航线区分；只显示用户选中显示的那一条候选。先在浏览器确认虚线已恢复；若仍不可用，改用半透明加细线区分，并写入实施记录。
- **状态**：`useNavigationStore` 增加 `candidates: Record<route_id, CandidateRouteResponse>`、`visibleCandidateId`、`adoptCandidate(routeId)`。

### 4.4 模拟器

- `wuhan_yangtze_ais_simulator.py` 增加 `--route-url`，与 `--route` 互斥，启动时 GET 一次当前航线，本船从第 1 个航点出发。
- 两艘目标船的路径不变。采纳的航线不经过原对遇 / 追越水域时，本次运行没有会遇场景，只演示航标提醒；这是预期行为，演示脚本中写明。
- 默认仍为 `--route` 读取文件，step-1 的演示不受影响。

---

## 5. 评测

### 5.1 数据隔离

| 环节 | 使用的数据 | 强制方式 |
|---|---|---|
| 评测网格 | 只用 `train` | `build --variant eval` 固定 splits；清单记录 splits 与 parquet SHA-256 |
| 调参 | `val` 起终点 + 评测网格 | `od` 拒绝 train；运行器只接受 `variant=eval` 的网格 |
| 最终评测 | `test` 起终点 + 评测网格 + 定稿参数 | `test` 只运行一次；`report --split test` 校验 `params_tag` 与 `tuning.md` 定稿一致 |
| 演示网格 | train + val + test | 只出现在后端资源中；评测报告不引用演示网格 |

### 5.2 指标

对每个起终点：

- **横向偏差**：真实航迹每个点到规划折线的最短距离，取平均值。全体起终点汇总报告平均值、中位数和 p90。
- **航道内比例**：用评测网格和定稿 `min_seg`，按 `ChannelProfile` 每 25 m 采样，统计落在可通航格的比例，由 Java 运行器计算。
- **规划失败率**：按失败原因分别统计。

基线：起终点直线航线的同一组指标，用来说明网格规划带来的增益。

报告须写明以下局限：

- `split` 按船划分，三个划分来自同一约 11 天时段（2022-05），评测不衡量跨时段泛化；
- 横向偏差以真实航迹为参照，但真实船舶航迹本身不是“正确航线”；
- 不设分数门槛。

### 5.3 调参

在 val 上做小规模网格搜索：`cell_m ∈ {50, 100}`（每个取值需要重建评测网格）、`min_seg ∈ {2, 3, 5}`、`w_density ∈ {0.5, 2, 8}`、`w_direction ∈ {0, 1, 4}`。以平均横向偏差为主指标，航道内比例和失败率作为约束：失败率不高于最低组合 + 2 个百分点。每个组合的结果记入 `eval/tuning.md`，最后一节为定稿参数及理由。演示网格和后端默认配置使用定稿参数。

### 5.4 评测运行器

`src/test/java/.../navigation/planner/PlannerEvaluationRunner`：JUnit 测试类，带 `@EnabledIfSystemProperty(named = "waterway.eval.dir", matches = ".+")`，`mvn test` 默认跳过。

```bash
mvn test -Dtest=PlannerEvaluationRunner -Dwaterway.eval.dir=../../waterway-pipeline -Dwaterway.eval.split=val -Dwaterway.eval.params=<tag>
```

它从 `data/grid-eval/` 加载评测网格（经过 `loadForEvaluation` 校验），读取 `eval/od_{split}.csv`，写出 `eval/planner_{split}.csv`。参数组合通过系统属性传入。

---

## 6. 阶段计划

| 阶段 | 内容 | 产出 / 判定 |
|---|---|---|
| 0 | 建 venv；读取 parquet 确认列、split 计数与 manifest 一致；确认等距投影与 parquet 原有 `x, y` 在包围盒内的差异量级（只用于记录） | 实施记录 |
| 1 | 实现流水线 `build`、`od`；构建 `cell_m = 100` 的评测网格与演示网格 | 格子数、文件大小 |
| 2 | Java 网格加载与 `RoutePlanner`、`ChannelProfile`；在演示网格上测量 §2.1 三项指标 | 按 §2.1 判定运行形态，写入实施记录 |
| 3 | 评测运行器、`report`；val 调参（§5.3）→ 定稿 → 用定稿参数重建网格 → test 评测一次 | `tuning.md`、`report_test.md` |
| 4 | `RouteAtonIndex` 逐段求交；`RouteCheckService`；工具分组；两个工具；候选池；采纳接口；`AGENT_STEP` 扩展；提示词 | 后端测试 |
| 5 | 前端（步骤展开、候选卡、虚线候选航线）；模拟器 `--route-url` | 前端测试 |
| 6 | 文档同步；端到端演示 | §9 |

阶段 1 和阶段 2 可以和阶段 4 中不依赖网格的部分（工具分组、`AGENT_STEP` 扩展、逐段求交）并行。

---

## 7. 文件影响

| 区域 | 变更 |
|---|---|
| `waterway-pipeline/`（新增） | `README.md`、`requirements.txt`、`waterway_pipeline/{__main__,cli,projection,grid,od,report}.py`、`data/grid-eval/`、`eval/{od_val.csv,od_test.csv,planner_val.csv,planner_test.csv,tuning.md,report_val.md,report_test.md}`、`tests/` |
| `backend/map-service` main | 新增 `navigation/{planner,place,check}/**`、`navigation/route/CandidateRouteStore`、`llm/agent/tool/AgentToolScope`、`llm/agent/tool/builtin/{PlanRouteTool,CheckRouteTool}`、`resources/waterway/grid-demo.*`、`resources/navigation/wuhan-places.json`；修改 `PlannedRouteRepository`、`RouteAtonIndex`、`NoticeAlertMonitor`、`NavigationController`、`NavigationProperties`、`AgentTool`、`AgentToolRegistry`、`AgentToolNames`、`AgentLoopOrchestrator`、`AgentStepEvent`、`AgentStepPayload` 及其发送处、`ChatAgentPromptBuilder`、`LlmChatService`、`AdvisoryService`、`AdvisoryPromptBuilder`、`application.properties` |
| `backend/map-service` test | 新增规划器、通道检查、候选池、工具、采纳接口、工具分组的测试与小网格夹具；新增 `PlannerEvaluationRunner` |
| `frontend/src` | 新增 `components/Dashboard/CandidateRouteCard.tsx`；修改 `types/schema.d.ts`、`components/Dashboard/ChatMessageList.tsx`、`components/Map/navigationLayers.ts`、`store/useNavigationStore.ts`、`services/navigationService.ts` |
| `simulator/` | `wuhan_yangtze_ais_simulator.py` 增加 `--route-url` 及测试 |
| 文档 | `EVENT_SCHEMA.md` §6.11 与变更记录；`ARCHITECTURE.md`（`navigation` 模块新增规划与复核、§十 `PlannedRoute` 采纳方式、数据链路）；`ADR_AND_REVIEW_FINDINGS.md` 新增 ADR-018（规划器为确定性 Java 实现、进程内运行、LLM 只传标识、采纳只经 REST、工具按调用方分组）；`CURRENT_SYSTEM_OVERVIEW.md` 导航；`CLAUDE.md` 常用命令增加 `waterway-pipeline`；`docs/demo/MAP_SYSTEM_DEMO_SCRIPT.md` 增加对话规划环节；`docs/TODO.md` §2“航道约束建模”补充指向本 step 网格 |

---

## 8. 不在本 step

| 事项 | 分类 | 说明 / 再议时机 |
|---|---|---|
| 地图点选起终点、自由坐标起终点 | not doing | 会让坐标经 `CHAT` 流入 agent，与“LLM 只传标识”的约束相冲突；演示用地名表已能覆盖需要 |
| 转向半径参与航线几何 | not doing | §2.9 |
| 采纳航线跨重启持久化、多客户端同步航线 | not doing | 单操作员演示，重启后回到配置文件中的航线 |
| 模拟器运行中途切换航线 | not doing | 采纳后重启模拟器即可演示；中途切换需要设计本船从当前位置汇入新航线的过渡过程 |
| 多候选航线、按时间或油耗优化 | not doing（overview 非目标） | — |
| 网格接入风险主链路、过滤上岸预测轨迹 | deferred（overview 非目标） | 已在 `docs/TODO.md` §2“航道约束建模”跟踪，本 step 只补充数据基础的指向 |
| 叠加几何协议、`evidence_items` 结构化 | deferred | step-3；本 step 的 `route_id` 与 `check_route` 结构即 step-3 的几何产出来源 |
| advisory 调用规划工具 | not doing | §2.8 |

---

## 9. 测试与验证

| 层 | 用例 | 预期依据 |
|---|---|---|
| 流水线 | 投影与反投影往返误差 < 1 cm；栅格化：两点相距 3 格时遍历覆盖中间格、线段擦过格角时两侧格都计入；同一航段多次经过同一格只计 1 次；方向分桶边界（22.5°、337.5°）；`cog` 为 NaN 时方向仍按航迹方位计入 | 手算 |
| 流水线 | `sog_med`：只被遍历经过、无原始点落入的格写空串；SOG 为 NaN 或超出 [0.5, 30] kn 的样本不参与中位数 | §2.4 |
| 流水线 | `build --variant eval` 的清单 `splits == ["train"]`；`od --split train` 被拒；起终点抽样在相同输入下可复现 | §3.1 / §5.1 |
| 后端 | `WaterwayGridLoader`：投影参数不符时拒绝；`loadForEvaluation` 拒绝 `demo` 网格 | §3.1 |
| 后端 | `RoutePlanner`（夹具网格）：L 形通道中绕行而不穿越不可通航格；两条反向车道时按方向选择车道；端点吸附失败；不连通时返回 `NO_PATH`；偏航限等于夹具通道半宽的预期值；`speed_kn` 指定与不指定 | 夹具几何 |
| 后端 | 简化约束回归：按 review 反例构造夹具（50 m 网格、30 m 容差、捷径短暂穿过不可通航格约 10 m，25 m 采样点都不落入该格），简化结果必须在此处保留航点；捷径只擦过不可通航格的格角时同样拒绝；遍历给出的格子集合与手算一致 | §2.5 第 3 步 |
| 后端 | 航速回退：航段全部为缺失 `sog_med` 时回退到整线中位数；整线都缺失时回退到 `default-speed-kn`；`speed_fallback_legs` 计数正确；网格加载时空串读为缺失、非法数值拒绝 | §2.5 第 4 步 / §3.1 |
| 后端 | `ChannelProfile` / `RouteCheckService`：穿出通道的区间起止；方向冲突区间；航标命中与 `non_valid_nearby`；`unverified` 六项齐全 | §2.10 |
| 后端 | `RouteAtonIndex` 逐段求交：航标位于转向点内侧时的进入点和离开点，与手算值对比（修改前直线近似的结果不符）；相邻航段在转向点处衔接的区间合并为一次接近；U 形航线两次经过同一航标时生成两个 `Entry`，区间互不包含中间段 | 手算几何；回归 step-1 现有用例 |
| 后端 | `NoticeAlertMonitor` 两次接近：第一次 `ACTIVE → CLEARED/PASSED`，在中间段不处于提醒状态，第二次以新的 `alert_id` 再次 `ACTIVE`；ETA 按各自的 `s_in` 计算 | §2.9 |
| 后端 | 工具：`plan_route` 未知地名、本船不可用、结果不含坐标；`check_route` 对 `active` 与候选的处理、过期候选；两个工具执行后 `PlannedRouteRepository.current()` 引用不变 | §2.7 / §3.3 / §3.4 |
| 后端 | 工具分组：`ADVISORY` 的工具列表与改动前完全相同；`ADVISORY` 下调用 `plan_route` 返回 `UNKNOWN_TOOL` | §2.8 |
| 后端 | 候选池：TTL 过期、容量淘汰、并发写读 | §2.7 |
| 后端 | 采纳接口：200 / 404 / 409；采纳后 `NoticeAlertMonitor` 发布 `ROUTE_CHANGED` 并按新航线命中 | §3.5 |
| 后端 | `AGENT_STEP` 序列化：`arguments` 和 `result_summary` 字段名；超长截断；`FINALIZING` 时为 null | §3.6 |
| 前端 | `AgentStepTrace` 展开显示 JSON；`CandidateRouteCard` 的出现条件、未复核时禁用采纳、采纳成功后状态；`useNavigationStore.adoptCandidate` | §4.3 |
| 前端 | 候选刷新：首次拉取 `check: null` 时采纳按钮禁用 → 收到同一 `route_id` 的 `check_route` 成功事件后重新拉取 → 复核结果显示、采纳按钮可用；其他 `route_id` 或 `active` 的复核事件不触发刷新 | §4.3 刷新约定 |
| 模拟器 | `--route-url` 与 `--route` 互斥；从接口读取的航线与文件读取的航线插值结果一致 | §4.4 |
| 评测 | val 调参记录完整；test 只运行一次；报告包含指标、直线基线与局限声明 | 版本 DoD 第 5 项 |
| 回归 | `mvn test`、`npm run test`、模拟器与两条流水线单测全部通过；`npm run lint` 不新增问题 | 现有基线（后端 491、前端 78） |
| 端到端 | 后端 + 前端 + chat agent：输入“从当前位置去阳逻港” → 步骤展开可见入参和结果 → 地图出现虚线候选 → 卡片显示复核结论与未复核条件 → 采纳 → 实线航线更新；用 `--route-url` 重启模拟器，经过通告航标时出现 `NOTICE_ALERT`。截图和日志写入实施记录 | 版本 DoD 第 6 项 |

---

## 10. 风险与假设

- **LLM 地名映射**：`glm-4.5-air` 可能选错 `place_id`，或在用户给出不在表中的地点时强行映射。缓解措施：工具结果回显地点名称，提示词要求不在表中时告知用户；候选卡显示起终点名称，供用户在采纳前核对。
- **渡船与锚地**：渡船横越航迹会让横向的格子也变成可通航格。方向代价可以抑制但不能消除；`check_route` 的方向冲突区间把这种情况暴露出来。
- **航道变化**：2022-05 的航迹不代表当前航道和水位，已列入未复核条件 `TRAFFIC_TEMPORAL`。
- **网格规模**：§2.1 的估算未经实测；超出阈值的处理见 §2.1。
- **评测偏差**：同一时段、按船划分会高估泛化能力，报告中如实声明。
- **候选航线长度**：长航线会生成大量航点，`NoticeAlertMonitor` 的 60 min 前瞻沿线计算不受影响；前端只渲染一条候选，渲染开销可以忽略。

---

## 11. review 修订（2026-10-29）

外部 review 提出 4 项 P2，均为方案缺口，已在实施前修订：

| 问题 | 修订位置 |
|---|---|
| 固定步长采样不能保证简化后的航段整段可通航（反例：捷径穿过不可通航格约 10.4 m，25 m 采样全部漏检） | §2.5 第 3 步改为格子遍历；§2.4 栅格化改用同一遍历规则；§9 增加反例和擦过格角的回归用例 |
| 候选卡首次拉取时还没有复核结果，之后没有刷新约定 | §4.3 增加刷新约定；§9 增加“先 null 后更新”的前端用例 |
| 多个求交区间取包络，会把两次接近之间的航段算成仍在接近 | §2.9 改为按连续区间拆分接近，提醒状态按次接近记录；§3.4 增加 `approach_index`；§9 增加 U 形航线用例 |
| 遍历补出的格子没有 SOG 样本，空值契约缺失 | §2.4 定义有效样本与空值编码；§2.5 定义三级航速回退；§3.1 定义加载规则；§9 增加夹具 |

## Deviations

无。overview Open Questions 中归属本 step 的“规划器运行形态”在 §2.1 按实测判定规则决定，判定结果写入实施记录，不构成偏离。
