# v1.3 step-1 — 航线模型与航标通告提醒

> 文档状态：planning
> 最后更新：2026-10-26
> 上游：[`OVERVIEW.md`](./OVERVIEW.md) § Step Breakdown / step-1
> 下游：`/implement-step-plan`

---

## 0. 上游锚点（引自 overview，不在本文重定义）

- **目标**：建立本船计划航线对象，并把武汉航道局航标通告转为可上图、可命中的结构化数据，完成“航标上图 → 本船驶近 → 提醒卡片”闭环。
- **Step-level DoD**：版本 DoD 第 1～4 项成立；新增测试覆盖度分秒换算、校验规则与航线命中；现有测试全部通过。
- **范围上限**：只处理武汉航道局航标通告（航标优化调整与航标失常），覆盖一个固定发布日期区间，须含 2026-09-24 通告中汉口水道 / 武汉二桥附近虚拟航标；只有一条当前生效航线；不做航线编辑；不接入 advisory agent。
- **约束**：LLM 只抽取坐标原文串与各字段依据片段；航标状态按覆盖区间内全部条目推演；缓冲 200 m、前瞻 60 min 可配置且沿航线计算；同一航标同一次接近只提醒一次，状态独立于 `SceneRiskStateTracker`；模拟器现有对遇 / 追越航段与目标船不变。

---

## 1. 现状

- **本船航线**：只存在于 `simulator/wuhan_yangtze_ais_simulator.py` 的 `build_routes()`，本船 `123456789` 为单段直线 `offset(START,-280) → offset(END,-280)`（约 (114.296169, 30.574175) → (114.312164, 30.598430)，3101 m，方位 29.6°）。`Vessel` 只支持起点到终点的单段插值。后端没有航线对象，`ARCHITECTURE.md` §十 的 `plannedRoute` 为 null。
- **风险帧事件**：`ShipDispatcher.publishRiskSnapshot()` 在 `RiskStreamPublisher` 的单线程发布执行器上，通过 `beforePublish` 回调同步发布 `RiskAssessmentCompletedEvent(snapshotVersion, ownShip, allShips, ...)`。本船或目标船的每次更新都会触发该事件。`LlmRiskEventListener` 以同步 `@EventListener` 消费它。
- **SSE**：`SseEventType` 现有 `RISK_UPDATE / ENVIRONMENT_UPDATE / EXPLANATION / ERROR / ADVISORY`。`ADVISORY` 与 `EXPLANATION` 都不进入新连接的 replay 缓存。
- **配置与资源**：`@ConfigurationProperties` + `@Component` + Lombok `@Data`；进程内静态数据以 classpath 资源加载（如 `llm.graph.resource-path=classpath:colregs/rules.json`）。智谱密钥由环境变量 `ZHIPU_API_KEY` 注入。
- **前端**：`riskSseService` 按事件名注册监听；`useRiskStore` 持有 advisory；`AdvisoryCard` 挂在 `RiskExplanationPanel`；deck.gl 图层在 `MapContainer.tsx`（779 行）的单个构建函数中组装。REST 基址来自 `config/constants.ts` 的 `BACKEND_CONFIG.HTTP_ORIGIN`。
- **通告数据**（2026-10-26 抓取核对）：2026-09-18 至 2026-10-23 武汉航道局共 18 条通告，约 65 个条目，其中 18 个无坐标（按里程调整、撤除）；4 条失常通告是整段叙述，没有编号条目。页面正文存在字间空格；度分秒格式为 `30°36′21″.3N`，小数位可为 1～2 位（如 `114°10′53″.82E`）。`cjhdj.com.cn` 拒绝 WebFetch，带浏览器 UA 的普通 HTTP GET 可以取到。
- **演示航标**：2026-09-24 通告（`t20260924_490370`）第 5、6 条：汉口水道武汉二桥 #1 红浮（虚拟）(114.322833, 30.605917)、#2 红浮（虚拟）(114.320000, 30.602389)，分别位于本船现终点方位 50.8° 处 1318 m、59.6° 处 870 m。

---

## 2. 设计决策

### 2.1 overview Open Questions 在本 step 的决定

| 问题 | 决定 | 理由 |
|---|---|---|
| 航线下发给模拟器的方式 | 航线文件作为唯一来源，放在后端 classpath 资源目录；后端启动加载，模拟器通过 `--route` 读同一文件（默认路径相对脚本解析） | step-1 只有一条静态航线，文件是最简单的单一来源。step-2 的采纳流程需要模拟器从后端读取，届时增加 `--route-url`；文件格式与 REST 响应体保持同构，切换时无需改格式 |
| 辖区校验包围盒 | 经度 113.0～116.5，纬度 29.3～31.0 | 覆盖已见通告点（最东到蕲春、富池一带），用于拦截度分秒误读、经纬度互换等明显错误，不作为精确辖区边界。抽取说明须注明为近似值 |
| 提醒是否限定近期变动 | 不额外限定；对快照中全部“有效”航标提醒 | 覆盖区间本身约 5～6 周，已经界定了“近期”；再加天数过滤会隐藏仍然有效的信息 |
| 失常航标是否单独提醒 | 本 step 不做（deferred，见 §7） | 区间内 4 条失常通告都不在演示航段，相关航标在区间内也没有坐标记录，做了在演示中不可见；原文写明“恢复后不再另行通告”，失常状态的结束时间无法确定，提醒语义需要单独设计 |

### 2.2 抽取与状态推演放在离线流水线，后端只加载结果

- 后端只读取一份已推演好的航标状态文件，运行时不调用 LLM，也不做状态推演。
- 采集、抽取、校验、状态推演、评测都放在新增的顶层目录 `notice-pipeline/`（Python 3.12，依赖只用标准库，与 `simulator/` 一致用 `unittest`）。
- **未采用：后端在运行时推演状态。** 推演规则在 Python 里可以和抽取一起测试；后端重复实现一遍收益为零。快照固定之后，状态也是固定的。

### 2.3 坐标换算由程序完成，原文依据逐字校验

LLM 对每个字段只返回 `{value, evidence}`，其中 `position_raw` 是原文坐标串。程序负责三件事：解析度分秒、核对 `evidence` 能在快照正文里逐字找到（两边都先去掉全部空白再比较）、拿正则从条目原文独立解析出的坐标作为基准来对比。

### 2.4 航标身份键包含“是否虚拟”

键为 `normalize(waterway) | normalize(name) | is_virtual`。`normalize` 统一全半角 `#`、去掉空白和“电子航道图虚拟航标”后缀；“（虚拟）”这个标记不留在名称里，改由 `is_virtual` 字段表达。虚拟航标与同名实体航标可能同时存在，不能合并。

### 2.5 命中判断沿航线进行，用局部平面近似

- 以航线首个航点为参考点做等距投影，把航线、本船、航标都换算成米制平面坐标。武汉演示航段在 10 km 以内，误差可以忽略。
- 每个航标预先投影到航线上，得到沿线位置 `s_a` 和到航线的最近距离 `r`。`r ≤ buffer` 时，进入点 `s_in = s_a − √(buffer² − r²)`，离开点 `s_out = s_a + √(buffer² − r²)`。这里把航标附近的航段近似为直线；航标靠近转向点时，结果会有偏差，见 §8。
- 本船每次更新时，投影得到 `s_own` 和带符号的横向偏移 `d`：
  - `|d|` 超过所在航段对应一侧的偏航限 → 视为偏离航线，暂停判断，并清除当前的提醒。
  - 前瞻窗口为 `[s_own, s_own + sog × lookahead]`。当 `s_in ≤ s_own + L` 且 `s_out ≥ s_own` 时判为命中，`eta = max(0, s_in − s_own) / sog`。
- **未采用：本船当前 COG 直线外推。** 内河弯道上直线外推会画到岸上；overview 已经约定沿航线计算。

### 2.6 提醒生命周期：一次接近一张卡片，可刷新但不重复提醒

每个航标维护一个接近状态：`IDLE → ACTIVE → PASSED`。

- 进入 `ACTIVE` 时发布一条 `NOTICE_ALERT`（`status=ACTIVE`），生成新的 `alert_id`。
- `ACTIVE` 期间每隔 `refresh-seconds`（默认 30 s）用同一个 `alert_id` 重新发布，更新 ETA 和距离。前端按 `alert_id` 更新已有卡片，不算新的提醒，不播报。
- `s_own > s_out` 时发布 `status=CLEARED`、`clear_reason=PASSED`，转为 `PASSED`。偏离航线或航线更换时发布 `CLEARED`，原因分别为 `OFF_ROUTE`、`ROUTE_CHANGED`，状态回到 `IDLE`。
- 处于 `PASSED` 的航标，只有在 `s_own < s_in − buffer` 时才回到 `IDLE`，用来兼容模拟器重跑时本船回到起点的情况。
- 航速低于 `min-sog-kn`（默认 0.5 kn）时不进入新的 `ACTIVE`，已有提醒保持不变、不刷新。
- 只有本船消息时间变化时才重新计算，目标船更新引起的事件直接跳过。
- 事件监听运行在发布执行器这一个线程上，所以接近状态不需要加锁。

**未采用：只发一次、不刷新 ETA。** 前瞻 60 min 时，提醒会在本船出发后不久就出现；ETA 不刷新的话，卡片会长时间显示过时的数值。

### 2.7 新连接不补发提醒

与 `ADVISORY` 保持一致，`NOTICE_ALERT` 不进入 replay 缓存。航标图层和航线通过 REST 加载，页面刷新后可以恢复显示；未结束的提醒会在下一次刷新周期（不超过 30 s）重新出现在前端。

---

## 3. 数据契约

### 3.1 计划航线文件 / REST 响应

存放位置为 `backend/map-service/src/main/resources/routes/wuhan-demo-own.route.json`。字段语义参照 ECDIS 航线的常见属性，不是 RTZ 序列化格式。

```json
{
  "route_id": "wuhan-demo-own",
  "name": "武汉长江段演示本船航线",
  "synthetic": true,
  "waypoints": [
    { "seq": 1, "lon": 114.296169, "lat": 30.574175, "turn_radius_m": 0 },
    { "seq": 2, "lon": 114.312164, "lat": 30.598430, "turn_radius_m": 300 }
  ],
  "legs": [
    { "from_seq": 1, "to_seq": 2, "speed_kn": 11.5, "xtd_port_m": 150, "xtd_starboard_m": 150 }
  ]
}
```

- `legs.length == waypoints.length − 1`，`legs[i]` 连接 `waypoints[i]` 与 `waypoints[i+1]`。
- step-1 的计算只用到航点和偏航限；`turn_radius_m` 与 `speed_kn` 只做加载校验（非负），不参与命中计算，供 step-2 规划器填写。
- 加载校验：至少 2 个航点、`seq` 连续、坐标在辖区包围盒内、偏航限为正。不通过时启动失败。

### 3.2 航标状态文件 `aton_state.json`

由流水线的 `publish` 命令写入 `backend/map-service/src/main/resources/notices/aton_state.json`，作为入仓的生成产物。

```json
{
  "schema_version": 1,
  "publisher": "长江武汉航道局",
  "coverage": { "published_from": "2026-09-15", "published_to": "<快照中最晚发布日期>", "fetched_at": "<ISO 8601>" },
  "assumptions": ["坐标为 2000 国家大地坐标系，按通告原文“航海用途等同于 WGS-84 坐标系”直接作为 WGS84 使用"],
  "atons": [
    {
      "aton_id": "<身份键 SHA-1 前 12 位>",
      "waterway": "汉口水道",
      "name": "武汉二桥#1红浮",
      "is_virtual": true,
      "state": "VALID",
      "position": { "lon": 114.322833, "lat": 30.605917 },
      "last_action": "SET",
      "effective_at": "2026-09-24T15:30:00+08:00",
      "history": [
        {
          "notice_id": "t20260924_490370",
          "item_seq": 5,
          "action": "SET",
          "effective_at": "2026-09-24T15:30:00+08:00",
          "position_raw": "30°36′21″.3N，114°19′22″.2E",
          "position_text": null,
          "evidence": { "action": "设置", "aton_name": "汉口水道武汉二桥#1红浮（虚拟）", "position_raw": "30°36′21″.3N，114°19′22″.2E", "effective_time": "09月24日15:30" }
        }
      ]
    }
  ],
  "notices": [
    { "notice_id": "t20260924_490370", "notice_no": null, "title": "长江武汉航道局关于航标优化调整的航道通告", "published_at": "2026-09-24", "url": "https://www.cjhdj.com.cn/hdfw/hdtg/xy/202609/t20260924_490370.shtml" }
  ],
  "review": [
    { "notice_id": "t20260918_489986", "item_seq": 1, "reason": "UNMATCHED_NO_COORD", "detail": "沌口水道其春石白浮按里程调整，覆盖区间内无该航标坐标记录" }
  ]
}
```

- `state` 枚举：`VALID` / `POSITION_UNVERIFIED` / `MALFUNCTION` / `REMOVED`。非 `VALID` 的航标保留最后已知的 `position` 用于展示；从未有过坐标的航标不进入 `atons`，只进入 `review`。
- `review.reason` 枚举：`COORD_PARSE_FAILED`、`OUT_OF_BBOX`、`TIME_PARSE_FAILED`、`EVIDENCE_NOT_FOUND`、`COORD_MISMATCH`（LLM 坐标串与正则基准不一致）、`UNMATCHED_NO_COORD`、`EXTRACTION_FAILED`。
- 后端加载校验：`VALID` 必须有 `position`；`state` 不认识的条目记日志后跳过；`coverage.published_to` 作为数据截止时间暴露给前端。

### 3.3 REST

| 方法与路径 | 响应 |
|---|---|
| `GET /api/v2/navigation/route/active` | §3.1 的结构 |
| `GET /api/v2/navigation/notices/aton` | GeoJSON `FeatureCollection`：每个航标是一个 `Point` Feature，`properties` 包含 §3.2 中除 `position` 之外的航标字段，并附上关联通告元数据；集合级扩展成员 `coverage` 与 `assumptions` |

### 3.4 SSE `NOTICE_ALERT`（risk 连接，`EVENT_SCHEMA.md` §4.8）

```json
{
  "event_id": "server-event-xxx",
  "alert_id": "notice-alert-uuid",
  "status": "ACTIVE",
  "clear_reason": null,
  "timestamp": "2026-10-26T10:00:00Z",
  "route_id": "wuhan-demo-own",
  "aton": {
    "aton_id": "…", "name": "武汉二桥#1红浮", "waterway": "汉口水道", "is_virtual": true,
    "state": "VALID", "last_action": "SET", "effective_at": "2026-09-24T15:30:00+08:00",
    "lon": 114.322833, "lat": 30.605917
  },
  "distance_along_route_m": 1240.0,
  "lateral_offset_m": 120.0,
  "eta_seconds": 210,
  "buffer_m": 200,
  "lookahead_minutes": 60,
  "notice": { "notice_id": "t20260924_490370", "notice_no": null, "title": "…", "published_at": "2026-09-24", "url": "…", "item_seq": 5 },
  "evidence": { "action": "设置", "position_raw": "30°36′21″.3N，114°19′22″.2E" },
  "data_cutoff": "2026-10-23"
}
```

- `status`：`ACTIVE` / `CLEARED`；`clear_reason`：`PASSED` / `OFF_ROUTE` / `ROUTE_CHANGED`，`ACTIVE` 时为 null。
- 同一个 `alert_id` 可以多次以 `ACTIVE` 出现（刷新），前端按 `alert_id` upsert。
- 不进入 replay 缓存。

---

## 4. 离线流水线 `notice-pipeline/`

### 4.1 命令

| 命令 | 行为 |
|---|---|
| `fetch` | 从武汉航道局所在的通告列表栏目分页抓取，直到早于 `published_from`（2026-09-15）；筛选标题含“长江武汉航道局”的航标通告；把原始 HTML 存入 `data/snapshots/`，并写 `manifest.json`（URL、标题、发布日期、`fetched_at`、SHA-256）。请求串行，间隔 1 s，使用浏览器 UA |
| `extract` | 逐条通告调用智谱 `glm-4.5-air`（关闭 thinking，temperature 0）。输入为去标签、去字间空格后的正文。输出原样存入 `data/extracted/{notice_id}.json`，附模型名和 prompt 版本。JSON 解析失败时重试 1 次，仍失败记 `EXTRACTION_FAILED` |
| `validate` | 执行 §4.3 的规则，产出 `data/validated/{notice_id}.json` |
| `derive` | 执行 §4.4 的状态推演，产出 `data/aton_state.json` |
| `publish` | 把 `aton_state.json` 复制到后端 classpath 资源目录 |
| `eval` | 读取 `eval/annotations.csv`，生成 `eval/report.md` |

`extract` 是唯一需要网络和密钥的步骤；后面的步骤都可以只基于入仓文件重跑。

### 4.2 抽取 prompt 与输出 schema

prompt 要点（版本号 `aton-extract-v1`）：
- 只输出 JSON。
- 每个字段的 `value` 和 `evidence` 都必须逐字复制原文，不得改写或补全；原文没有的字段填 null。
- 不做坐标换算、不推断年份。
- 失常通告整段作为一个条目输出，`item_seq` 为 1。
- 一个编号条目输出一个 item，不合并、不拆分。

```json
{
  "notice_no": { "value": "汉道通〔2026〕227号 | null", "evidence": "…" },
  "items": [
    {
      "item_seq": 1,
      "action":         { "value": "SET | ADJUST | REMOVE | MALFUNCTION | RESTORE", "evidence": "设置" },
      "effective_time": { "value": "09月24日15:30", "evidence": "…" },
      "waterway":       { "value": "汉口水道", "evidence": "…" },
      "aton_name":      { "value": "武汉二桥#1红浮", "evidence": "…" },
      "is_virtual":     { "value": true, "evidence": "（虚拟）" },
      "position_raw":   { "value": "30°36′21″.3N，114°19′22″.2E | null", "evidence": "…" },
      "position_text":  { "value": "长江中游航道里程18.3千米，左岸 | null", "evidence": "…" },
      "malfunction_kind": { "value": "漂失 | null", "evidence": "…" }
    }
  ]
}
```

动作映射写进 prompt：设置 → `SET`；位置调整 → `ADJUST`；撤除 → `REMOVE`；漂失 / 熄灭 / 移位等失常 → `MALFUNCTION`；恢复 → `RESTORE`。

### 4.3 校验规则

| 规则 | 失败时的 reason |
|---|---|
| 每个非 null 字段的 `evidence` 在去空白后的正文中逐字存在 | `EVIDENCE_NOT_FOUND` |
| `position_raw` 能按 `D°M′S″.fN` / `D°M′S″.fE` 解析（也接受 `S.f″`），十进制 = D + M/60 + S/3600 | `COORD_PARSE_FAILED` |
| 换算结果在 §2.1 的包围盒内 | `OUT_OF_BBOX` |
| 正则从该条目原文独立解析出的坐标与 LLM 坐标串的换算结果相差不超过 1 m | `COORD_MISMATCH` |
| `effective_time` 能解析为“MM月DD日HH:MM”，年份取发布日期的年份；月份大于发布月份时取上一年；解析结果不得晚于发布日期当天结束 | `TIME_PARSE_FAILED` |

任何一条失败，该条目都不进入状态推演，只记入 `review`。

### 4.4 状态推演

同一身份键的条目按 `effective_at` 排序，时间相同时按通告发布顺序、`item_seq` 排序，然后依次应用：

| 当前状态 | 条目 | 结果 |
|---|---|---|
| 未知 | `SET` / `ADJUST`，有坐标 | `VALID`（新位置） |
| 未知 | 任意动作，无坐标 | 不建航标，`review: UNMATCHED_NO_COORD` |
| 已知 | `SET` / `ADJUST`，有坐标 | `VALID`（新位置） |
| 已知 | `ADJUST`，无坐标 | `POSITION_UNVERIFIED`（保留最后位置用于展示） |
| 已知 | `REMOVE` | `REMOVED` |
| 已知 | `MALFUNCTION` | `MALFUNCTION` |
| `MALFUNCTION` | `RESTORE`，无坐标 | `VALID`（最后位置；假设恢复到原位，写入 `assumptions`） |

所有应用过的条目都追加到 `history`。

### 4.5 评测

- `eval/annotations.csv` 由 `extract` + `validate` 预填：每个条目每个字段一行，包含 LLM 值、正则基准、校验结果。核对人逐行填写 `correct`（Y/N）和备注。样本至少覆盖 5～10 条通告，须包含 `t20260924_490370`、至少一条含按里程调整的通告（`t20260918_489986` 或 `t20260928_490570`）、至少一条含撤除的通告（`t20261022_492297`）、至少一条失常通告。
- 核对由实施方逐行完成，用户抽检；`report.md` 注明核对人和抽检范围。
- `report.md` 报告以下指标：字段级与总体正确率、条目切分正确率（LLM 条目数与编号条目数是否一致）、坐标偏差（米；按 §4.3 应全部 ≤ 1 m，超出的逐条列出）、航标匹配正确率（无坐标条目匹配到的航标是否正确）、`review` 条目及原因分布。

---

## 5. 后端

新增包 `com.whut.map.map_service.navigation`：

| 类 | 职责 |
|---|---|
| `navigation/route/PlannedRoute`、`RouteWaypoint`、`RouteLeg`（record） | §3.1 的数据模型 |
| `navigation/route/PlannedRouteRepository` | 启动时从 `navigation.route.active-resource` 加载并校验；以 `AtomicReference` 持有当前航线；`current()` 读取。step-2 在这里增加替换入口 |
| `navigation/route/RouteGeometry` | 纯函数：局部投影、`project(point) → (legIndex, s, signedOffset)`、航线总长 |
| `navigation/notice/AtonRecord`、`AtonState`、`AtonNoticeDataset` | §3.2 的数据模型 |
| `navigation/notice/AtonNoticeRepository` | 从 `navigation.notice.aton-state-resource` 加载并校验 |
| `navigation/notice/RouteAtonIndex` | 由（航线，有效航标，buffer）预计算每个航标的 `s_in / s_a / s_out / r`；航线变化时重建 |
| `navigation/notice/NoticeAlertMonitor` | `@EventListener` 消费 `RiskAssessmentCompletedEvent`，执行 §2.5 / §2.6，调用 `RiskStreamPublisher.publishNoticeAlert` |
| `navigation/config/NavigationProperties` | 前缀 `navigation`，见下 |
| `navigation/api/NavigationController` | §3.3 的两个 GET 接口，`@CrossOrigin` 与 `S57Controller` 一致 |
| `shared/dto/sse/NoticeAlertPayload` 及枚举 | §3.4，snake_case 序列化与现有 payload 一致 |

关键签名：

```java
record RouteProjection(int legIndex, double alongMeters, double signedOffsetMeters) {}

final class RouteGeometry {
    static RouteGeometry of(PlannedRoute route);
    RouteProjection project(double lon, double lat);
    double totalLengthMeters();
}

@EventListener
void NoticeAlertMonitor.onRiskAssessmentCompleted(RiskAssessmentCompletedEvent event);
```

`signedOffsetMeters` 以航段方向为准，左舷为正；与 `xtd_port_m` / `xtd_starboard_m` 比较。

配置（`application.properties`）：

```properties
navigation.route.active-resource=classpath:routes/wuhan-demo-own.route.json
navigation.notice.aton-state-resource=classpath:notices/aton_state.json
navigation.notice-alert.enabled=true
navigation.notice-alert.buffer-m=200
navigation.notice-alert.lookahead-minutes=60
navigation.notice-alert.min-sog-kn=0.5
navigation.notice-alert.refresh-seconds=30
```

`enabled=false` 时 `NoticeAlertMonitor` 不加载，两个 GET 接口照常可用。

`RiskStreamPublisher` 新增 `publishNoticeAlert(NoticeAlertPayload)`，写法与 `publishAdvisory` 相同；`SseEventType` 新增 `NOTICE_ALERT`。风险主链路、`LlmRiskEventListener`、`SceneRiskStateTracker` 都不改。

---

## 6. 前端与模拟器

### 6.1 前端

- **类型**：`types/schema.d.ts` 新增 `PlannedRoute`、`AtonFeatureCollection`、`NoticeAlertPayload`，与 §3 同步。
- **服务**：新增 `services/navigationService.ts`，提供 `fetchActiveRoute()` 与 `fetchAtonNotices()`；`riskSseService` 增加 `NOTICE_ALERT` 监听与 `onNoticeAlert` 订阅。
- **状态**：新增 `store/useNavigationStore.ts`，持有航线、航标集合、选中航标，以及按 `alert_id` 索引的提醒。`ACTIVE` 时 upsert；`CLEARED` 时移出活动列表，并在最近列表中保留最后 5 条。应用挂载时加载 REST 数据。
- **地图**：新增 `components/Map/navigationLayers.ts`，导出 `buildNavigationLayers(state, isDarkMode)`，由 `MapContainer` 合并进现有图层数组。包含：
  - 计划航线 `PathLayer`；
  - 航标 `ScatterplotLayer`：`VALID` 按 `last_action` 着色，其余状态为灰色空心；
  - `VALID` 航标的 200 m 缓冲圈（米制半径，只描边）；
  - 航标可拾取，点击后写入选中航标。
- **航标详情卡**：新增 `components/Map/AtonDetailCard.tsx`，选中航标时固定显示在地图右下角，内容为名称、水道、状态、虚拟标识、生效时间、原文依据（按字段）、通告标题与原文链接、数据截止时间、坐标系假设；可以关闭。不做跟随地图坐标移动的弹窗。
- **提醒卡**：新增 `components/Dashboard/NoticeAlertCard.tsx`，挂在 `RiskExplanationPanel` 中 `AdvisoryCard` 的上方，作为独立分区“航标通告提醒”。使用与风险等级色不同的强调色，内容为航标名称与动作、预计接近时间（取自 `eta_seconds`，并标出计算时刻）、横向距离、通告标题与条目序号、原文依据、数据截止时间。不触发 TTS。

### 6.2 模拟器

- `Vessel` 改为折线：`Vessel(mmsi, path, cruise_kn, initial_kn=3.0)`，`path` 至少 2 个点。速度曲线保持不变（前 30 s 为 3 kn，随后 90 s 加速到巡航速度）；沿折线累计距离插值；COG 取当前航段方位。两艘目标船使用两点路径，行为不变。
- 本船路径从航线文件读取（`--route`，默认 `../backend/map-service/src/main/resources/routes/wuhan-demo-own.route.json`，相对脚本解析）；巡航速度沿用 11.5 kn。
- 新增航段从现终点 (114.312164, 30.598430) 向东北延伸：让 #2、#1 红浮依次位于本船右舷 80～180 m，航线在 #1 之后再延伸约 500 m 结束。具体航点在实施时对照 Esri 卫星图确认位于水域内，并用 `segments.parquet` 核对每个新航点 150 m 内存在历史 AIS 点（只在本地核对，结果写入实施记录）。首段坐标与现有 `offset(START,-280)` / `offset(END,-280)` 保持一致。

---

## 7. 不在本 step

| 事项 | 分类 | 再议时机 |
|---|---|---|
| 失常航标的单独提醒 | deferred | 覆盖区间内出现与计划航线相关、且能匹配到坐标的失常航标时；登记在 `docs/TODO.md` §2 |
| 航线采纳、替换与模拟器从后端拉取航线 | deferred | step-2 |
| `NOTICE_ALERT` 进入 replay 缓存、提醒 TTS 播报 | not doing | — |
| 转向半径参与几何计算 | deferred | step-2 规划器填写 `turn_radius_m` 后，由 step-2 决定是否用于航线几何 |
| 航道里程换算坐标 | not doing（overview 非目标） | — |
| 定时重新抓取通告 | not doing（overview 非目标） | — |

---

## 8. 风险与假设

- **前瞻 60 min 与演示节奏**：演示航线全长约 4.5 km，60 min 前瞻在出发后很快就覆盖全程，提醒会在演示一开始出现，之后 ETA 逐步刷新。如果演示需要呈现“驶近才出现”，可以在 `application-local.properties` 中把 `lookahead-minutes` 调小，默认值不变。
- **直线近似**：航标靠近转向点时，`s_in / s_out` 的近似会有偏差。演示航标离转向点超过 300 m，影响可以忽略；step-2 产生多航点航线后，再评估是否需要改为逐段求交。
- **数据时效**：快照截止之后的航标变动系统并不知道；前端和提醒卡都显示截止时间。
- **网站结构变化**：`fetch` 依赖列表页和正文页的 HTML 结构。入仓快照保证后续步骤可复现，`fetch` 本身不保证长期可用。
- **核对人偏差**：评测由实施方核对、用户抽检，报告如实标注。

---

## 9. 文件影响

| 区域 | 变更 |
|---|---|
| `notice-pipeline/`（新增） | `README.md`、`notice_pipeline/{fetch,extract,dms,validate,derive,evalreport,cli}.py`、`data/{snapshots,extracted,validated}/`、`data/aton_state.json`、`eval/{annotations.csv,report.md}`、`tests/` |
| `backend/map-service` | 新增 `navigation/**`、`shared/dto/sse/NoticeAlertPayload` 及枚举、`resources/routes/wuhan-demo-own.route.json`、`resources/notices/aton_state.json`；修改 `SseEventType`、`RiskStreamPublisher`、`application.properties` |
| `frontend/src` | 新增 `services/navigationService.ts`、`store/useNavigationStore.ts`、`components/Map/{navigationLayers.ts,AtonDetailCard.tsx}`、`components/Dashboard/NoticeAlertCard.tsx`；修改 `types/schema.d.ts`、`services/riskSseService.ts`、`components/Map/MapContainer.tsx`、`components/Dashboard/RiskExplanationPanel.tsx` |
| `simulator/` | 修改 `wuhan_yangtze_ais_simulator.py`、`test_wuhan_yangtze_ais_simulator.py` |
| 文档 | `EVENT_SCHEMA.md`（§4.2 表格、新增 §4.8）；`ARCHITECTURE.md`（模块地图新增 `navigation`、链路说明、§十 `PlannedRoute` 接入方式）；`ADR_AND_REVIEW_FINDINGS.md` 新增 ADR-017（LLM 只抽取原文串、航标状态离线推演、航线命中确定性计算）；`CURRENT_SYSTEM_OVERVIEW.md` 导航条目；`CLAUDE.md` 后端模块表新增 `navigation/`；`docs/demo/MAP_SYSTEM_DEMO_SCRIPT.md` §0 增加航标提醒环节 |

---

## 10. 测试与验证

| 层 | 用例 | 预期依据 |
|---|---|---|
| 流水线 | 度分秒解析：`30°36′21″.3N`、`114°10′53″.82E`、`30°34′4″.0N`、字间空格文本；非法格式 | 手算十进制值 |
| 流水线 | 时间解析：常规、跨年回退、晚于发布日期 | §4.3 规则 |
| 流水线 | 依据逐字校验：命中、改写一个字后失败 | §4.3 规则 |
| 流水线 | 状态推演：设置 → 有坐标调整 → 无坐标调整；设置 → 撤除；失常 → 恢复；区间内首次出现即为无坐标调整；同名虚拟与实体航标并存 | §4.4 表格 |
| 流水线 | 以入仓的 `extracted/` 为输入跑完 `validate → derive`，产物与入仓 `aton_state.json` 一致 | 可复现性 |
| 后端 | `RouteGeometry`：直线和折线航线上已知点的 `s` 与带符号偏移；航线外端点的投影 | 手算几何 |
| 后端 | `NoticeAlertMonitor`：驶近只发一次 ACTIVE；刷新节流；驶过后 CLEARED/PASSED；偏航 CLEARED/OFF_ROUTE；低航速不进入 ACTIVE；本船消息时间不变时跳过；重跑回到起点后再次提醒；非 VALID 航标不命中 | §2.5 / §2.6 |
| 后端 | 两个 Repository：加载入仓资源成功；缺少 `position` 的 VALID 被拒绝；航线校验失败时启动失败 | §3.1 / §3.2 |
| 后端 | `NavigationController`：GeoJSON 结构与集合级 `coverage`（直接实例化 + mock，与 `S57ControllerTest` 一致） | §3.3 |
| 后端 | `NoticeAlertPayload` 序列化字段名 | §3.4 |
| 前端 | `useNavigationStore` 按 `alert_id` upsert、CLEARED 移出；`NoticeAlertCard` 渲染关键字段；`riskSseService` 分发 `NOTICE_ALERT` | §3.4 |
| 模拟器 | 折线插值与 COG 切换；首段与现有 offset 航线的误差 < 1 m；两个演示航标在本船右舷 80～180 m；现有对遇 / 追越测试不变 | §6.2 |
| 回归 | `mvn test`、`npm run test`、`simulator` 单测全部通过；`npm run lint` 不新增问题 | 现有基线（后端 469、前端 71） |
| 端到端 | 启动 infra + 后端 + 前端 + 模拟器：航标与航线上图 → 提醒卡出现 → ETA 刷新 → 驶过后消失；点击航标能看到原文依据。截图和日志作为实施记录 | 版本 DoD 第 3、4 项 |

---

## Deviations

无。overview 的 Open Questions 中归属 step-1 的四项已在 §2.1 决定，属于 overview 预留给本 step 的决定，不构成偏离。
