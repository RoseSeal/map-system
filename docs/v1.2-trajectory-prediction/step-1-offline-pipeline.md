# step-1 — 离线管线与验证

> 版本：v1.2-trajectory-prediction
> 上游：[`OVERVIEW.md`](OVERVIEW.md)
> 下游：step-2 集成的依据与输入（保留会遇子集、训练权重、特征口径）
> status: completed
> last_updated: 2026-07-01

---

## 一、目标

在真实内河 AIS 上验证「学习型航迹预测能否改善 CPA/TCPA 风险预警」这一假设，产出可复现的离线实验链与结论，作为 step-2 实时集成的依据。本 step 为回溯记录：工作已完成，代码与产物归档于顶层 [`../../trajectory-service/`](../../trajectory-service/)。

结论（正）：学习型预测（GRU seq2seq）在测试集上 FDE 优于 CV 基线 28.3%，并在真实会遇的下游风险评估中把真危险漏报率由 12.2% 降至 8.6%、弯道桶由 16.5% 降至 11.4%；代价是误报率微升（诚实权衡，见 §五）。

## 二、代码与产物索引

| 位置 | 内容 |
|---|---|
| `trajectory-service/inland_traj/geometry.py` | 几何与风险核心：`cv_predict`（末 3 点最小二乘速度外推）、`cpa_tcpa_numerical`（分段闭式 CPA/TCPA）、`ade_fde`、`risk_level`、`downstream_risk_error`、`RISK_THRESHOLDS` |
| `trajectory-service/scripts/probe.py` | 数据探查（go/no-go、字段/间隔/bbox/缺失率、抽样轨迹图） |
| `trajectory-service/scripts/clean_segment.py` | 清洗与轨迹切分（坐标框、哨兵值、静止段剔除、去冗、按 gap 切段） |
| `trajectory-service/scripts/build_windows.py` | 窗口化（10 观测 → 未来第 1/2/3 观测），CV 目标用 `_lsq_velocity` 与几何核心同口径 |
| `trajectory-service/scripts/train_gru.py` | GRU seq2seq 训练与评估（`GRUPredictor`，特征见 §四） |
| `trajectory-service/scripts/mine_encounters.py` | 会遇挖掘 + 下游 CPA/TCPA 风险评估（CV vs GRU） |
| `trajectory-service/scripts/make_figures.py` / `make_data_figs.py` | 结果图与数据图 |
| `trajectory-service/tests/test_geometry.py` | 几何核心单元测试 |
| `trajectory-service/results/metrics/` | `prediction.json`、`downstream_risk.json`、`dataset_manifest.json`、`windows_manifest.json`、`probe_report.json` |
| `trajectory-service/results/figures/` | 预测/弯道/会遇/指标/概览图 |
| `trajectory-service/results/probe_report.md` | 数据探查报告 |

原始 AIS、清洗后 parquet、窗口 npz、训练权重（约 470 MB）不入 Git，可由脚本从原始数据重建；`trajectory-service/runtime/` 为本地产物目录，被根 `.gitignore` 忽略。

## 三、数据契约与来源

来源：National Basic Science Data Center（NBSDC）「水上交通安全风险协同分析和管控系统船舶 AIS 数据集」，关联国家重点研发计划课题 **2019YFB1600604-013**，数据说明文件明示完全共享。武汉长江段，WGS84，时间精度到秒。

探查（`probe.py`）确认的关键契约（详见 `results/probe_report.md`）：

- **体量/跨度**：13,732,797 行 / 6,232 唯一 MMSI，2022-05-20 15:27:50 ~ 2022-05-31 23:59:17（约 11.36 天）。
- **坐标垃圾值**：raw bbox 含 lon=0、lat=761 等异常，须显式按武汉框（lon 113.5–115.1，lat 29.8–31.0）过滤，不能信任 raw bbox。
- **时序字段**：`TimeStamp` 列 100% 空；`MSGTIME` 为唯一可排序报文时间，月份不补零，须容错解析。
- **MMSI 质量**：9 位合法 MMSI 占 99.4%。过滤以「合法 9 位 MMSI + 武汉框 + 航行段」为准，不绑 412/413 前缀（前缀仅为 probe 抽样便利）。
- **采样节奏**：raw 每船间隔中位数被 1s 突发点拉低；去冗后真实节奏紧密落在 ~153s。故预测时域**对齐真实节奏**，取未来第 1/2/3 个真实观测点、目标不插值（线性插值会把弯道拉直、抹掉待验证信号）。
- **弯道判据**：`RateOfTurn` 被 655.35 哨兵值污染、不作判据；`COG` 亦有 360/655.4 无效值。弯道用过滤后的 `|ΔCOG|/Δt` 与轨迹几何曲率标注。

## 四、管线与方法

1. **清洗切段**（`clean_segment.py`）：坐标框过滤 → 剔除 COG/ROT 哨兵值 → 段级静止/低速剔除（`move_thresh_kn=0.5`、`stat_run_s=300`）→ 突发去冗（`thin_spacing_s=30`）→ 按 gap（`gap_max_s=600`）切连续航行段。产出 12,265 段 / 4,382 船 / 1.8e6 点，弯道点占比 10.07%；段级切分 train 9722 / val 1336 / test 1207。
2. **窗口化**（`build_windows.py`）：每段滑窗取 10 个历史观测预测未来第 1/2/3 观测。CV 目标速度用**末 3 点最小二乘**（`_lsq_velocity`），与几何核心 `cv_predict(window=3)` 一致——这是诚实基线的关键：早期用末 2 点速度会高估学习型增益（~40%），改为 LSQ 后回落到 ~20–28%。
3. **几何核心**（`geometry.py`）：闭式分段 CPA/TCPA，ADE/FDE，风险等级映射（`RISK_THRESHOLDS`：ALARM 0.3 nm/300 s、WARNING 0.5 nm/900 s、CAUTION 1.0 nm/1800 s）。13 个单元测试通过。数据无关，可独立起步。
4. **GRU 训练**（`train_gru.py`）：`GRUPredictor(in_dim=6, hidden=128, layers=2)`，特征 `[x_rel/1000, y_rel/1000, Δt/100, sog/10, sin(cog), cos(cog)]`，seq2seq 预测未来相对位移。
5. **会遇挖掘 + 下游评估**（`mine_encounters.py`）：从轨迹按时空邻近（半径 2 nm、时间窗）挖 pairwise 会遇，以真值轨迹算真值 DCPA/TCPA/风险标签；再分别用 CV 与 GRU 预测轨迹算预测风险，比较误报/漏报。CV 与 GRU 走**同一**几何评估路径，特征对齐校验 `max_abs_diff≈4.8e-7`，保证对比公平。

## 五、结果

### 预测精度（测试集 168,554 窗口，`results/metrics/prediction.json`）

| 时域（真实观测点） | CV ADE (m) | GRU ADE (m) | 改善 |
|---|---|---|---|
| 第 1（≈+2.5 min） | 109.1 | 98.1 | 10.1% |
| 第 2（≈+5 min） | 202.7 | 161.4 | 20.4% |
| 第 3（≈+7.5 min，FDE） | 313.8 | 224.9 | **28.3%** |

改善随时域递增——与"CV 直线外推在更长时域、更弯航段偏差累积更大"的假设一致。

### 下游风险（105,095 会遇，真危险率 31.3%，`results/metrics/downstream_risk.json`）

| 指标 | CV | GRU |
|---|---|---|
| 真危险中漏报率（全局） | 12.2% | **8.6%** |
| 真危险中漏报率（弯道桶） | 16.5% | **11.4%** |
| 真危险中漏报率（直线桶） | 9.5% | 6.9% |
| 风险等级匹配率 | 86.0% | 86.9% |
| DCPA 平均误差 (m) | 178.9 | 155.1（−13.3%） |
| 真安全中误报率 | 2.8% | 3.2%（微升） |

**诚实权衡**：学习型预测降低漏报的同时误报率微升——预测更"敢报警"。在碰撞风险场景，漏报（该报未报）代价远高于误报，方向可接受；但误报升幅须在 step-2 集成时随实时数据继续观察。

## 六、复现

```bash
cd trajectory-service
pip install -r requirements.txt
# CSV_PATH 为原始 NBSDC AIS CSV；probe.py 与 clean_segment.py 必填 --csv。
CSV_PATH=path/to/nbsdc_ais.csv
python scripts/probe.py         --csv "$CSV_PATH"   # 数据闸门与契约 → runtime/probe
python scripts/clean_segment.py --csv "$CSV_PATH"   # 清洗切段 → runtime/dataset
python scripts/build_windows.py                     # 窗口化 → runtime/windows
# 训练 tuned 配置（对应 results/metrics/prediction.json：hidden 128 / layers 2 / max-train 130 万
# → runtime/gru_tuned/gru.pt）。裸默认会在 runtime/gru/ 训出较小模型，下游 mine_encounters/make_figures 加载不到。
python scripts/train_gru.py --out runtime/gru_tuned --hidden 128 --layers 2 --max-train 1300000
python scripts/mine_encounters.py                   # 会遇挖掘 + 下游评估 → runtime/encounters
python scripts/make_figures.py                      # 结果图 → runtime/figures
pytest tests/                                       # 几何核心
```

## 七、来源快照

本 step 的全部离线工作源自一次独立的参赛冲刺项目（competition 仓库），该仓库已冻结为提交快照，含更细的分步文档（step-1..7）与参赛材料。map-system 侧以本 step 为该工作的维护记录；full offline 细节以快照为参考、以本目录与 `trajectory-service/` 为当前真值。
