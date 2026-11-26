# 武汉模拟器雷达、视觉数据源

依据 `SIMULATOR_MESSAGE_FORMAT.md` 第 8 节。两路每条消息严格六字段，不含 MMSI 或跨源身份。真值仅写离线文件。

## 环境

Python 3.11 或更高，已有依赖 `paho-mqtt==2.1.0`，本机 MQTT broker。以下命令均从仓库根目录运行；Windows PowerShell 可以整行复制。输出目录必须不存在，订阅输出文件也拒绝覆盖。

## 离线复现

```powershell
python simulator/wuhan_yangtze_ais_simulator.py --offline --ticks 60 --interval 1 --sensors ais,radar,vision --epoch 2026-11-26T04:00:00Z --seed 42 --ais-off 900002222 --radar-noise-m 8 --radar-drop-rate 0.1 --vision-noise-m 15 --vision-drop-rate 0.2 --output-dir runtime/demo-offline
python simulator/verify_sensor_observations.py --truth runtime/demo-offline/truth.jsonl --messages runtime/demo-offline/messages.jsonl --max-distance-m 100 --report runtime/demo-offline/report.json
```

## 真实 MQTT 发布订阅

终端一先启动订阅，看到 `READY subscribed` 后再执行终端二。订阅时长应覆盖整段发布，期间避免其他程序向相同 topic 发布。

```powershell
python simulator/capture_sensor_messages.py --host 127.0.0.1 --port 1883 --seconds 75 --output runtime/demo-received.jsonl
```

终端二：

```powershell
python simulator/wuhan_yangtze_ais_simulator.py --host 127.0.0.1 --port 1883 --ticks 60 --interval 1 --sensors ais,radar,vision --epoch 2026-11-26T04:00:00Z --seed 42 --ais-off 900002222 --radar-noise-m 8 --radar-drop-rate 0.1 --vision-noise-m 15 --vision-drop-rate 0.2 --output-dir runtime/demo-mqtt
```

订阅完成后核对实际接收到的消息：

```powershell
python simulator/verify_sensor_observations.py --truth runtime/demo-mqtt/truth.jsonl --messages runtime/demo-received.jsonl --max-distance-m 100 --report runtime/demo-mqtt/received-report.json
```

## 参数与产物

- `--sensors` 为逗号分隔的 ais/radar/vision，默认 ais，不能重复或为空。传感器 topic 固定；`--topic` 仅自定义 AIS topic，核对器和订阅器对应使用 `--ais-topic`。
- 两路噪声标准差以米计，每轴独立高斯；丢帧为每目标每 tick 的伯努利试验，概率在 0..1。丢帧与噪声各用独立随机序列；改变另一路参数不影响本路。
- `--seed`、`--epoch`、`--interval`、`--start-at` 和路线共同确定结果。epoch 需要明确时区；固定 epoch 时用 tick 序号推进，不依赖实际运行耗时。AIS 的旧 MSGTIME 字段保持本地无时区格式，UTC 时间同时用于真值与传感器。跨时区比较 AIS 字节时需要相同主机时区。
- `--ais-off` 可重复指定已存在船舶。`--dropout MMSI:START:END` 和 `--jump MMSI:TIME:LAT` 仅影响 AIS，jump 只影响首次达到时间的一条报文。
- `truth.jsonl` 保存原始采样精度位置、timestampUtc、targetId、aisEnabled。`messages.jsonl` 保存 `{topic,payload}`。`manifest.json` 保存 mode、场景参数、路线哈希与数据文件 SHA256。mode=mqtt 是发布端成功发送记录，独立订阅证据在订阅输出及 `.meta.json`。
- `sourceConfidence` 是演示假设常量，RADAR=0.85、VISION=0.70；不从真实误差反推。噪声非零填 `POSITION_NOISE`，零噪声填空数组。
- 核对器按规范化 UTC 时间和位置做最大匹配数优先、最小总距离的最优一对一匹配，报告中保存每条离线匹配，可追溯到关闭 AIS 目标。missing 是真值缺少门限内匹配的数量；unmatched 是多余或超门限观测数量，并非直接等同配置丢帧概率。
- 验收要求非空真值、至少一个全程关闭 AIS 的目标、其他开机目标确实有 AIS 报文、每艘关闭目标两路均有观测且 AIS=0、无未匹配观测。失败返回非零退出码，无匹配时误差为 null。

## 实测结果（2026-11-26）

60 tick、3 目标，真值 180 条。实际 MQTT 收到 AIS 120 条、雷达 159 条、视觉 150 条，共 429 条；与发布日志、离线输出逐条一致，manifest 哈希校验通过。

| 来源 | 期望 | 观测/匹配 | 缺失 | 未匹配 | 平均误差 m | RMSE m | 最大误差 m |
|---|---:|---:|---:|---:|---:|---:|---:|
| RADAR | 180 | 159 | 21 | 0 | 9.789230 | 11.241374 | 33.250793 |
| VISION | 180 | 150 | 30 | 0 | 19.023912 | 20.994499 | 44.597799 |

关闭目标 `900002222`：AIS=0，RADAR=54，VISION=48，自动核对 pass=true。此结果是本机测试，不代表欧阳鑫已人工验收。

## 限制与测试

这是三条合成航迹的传感器观测模拟，不模拟真实传感器视场、遮挡、检测模型或速度误差；不修改后端融合接口。位置接近、交叉或噪声过大时，仅凭位置的身份归属会有歧义。QoS 0 的发送日志不能替代订阅日志，当前实测已同时保存两者。运行产物在内存中累计，适合本轮有限场景。

```powershell
python -B -m unittest discover -s simulator -p 'test_*.py' -v
```

新增代码先由码道智能体生成，审查修复通过码道编辑器完成，Codex 独立运行测试和 MQTT 核验；会话与截图随竞赛材料交付。
