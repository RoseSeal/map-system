#!/usr/bin/env python3
"""生成数据背景图：真实武汉长江单船航迹 + 多船交通密度叠加。

读 runtime/dataset/segments.parquet（step-2 产出），输出到 runtime/figures/。
用于参赛文档"数据"节与视频开场/数据段。不训练、不改其他文件。
"""
from __future__ import annotations

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import pandas as pd  # noqa: E402

plt.rcParams.update({
    "font.sans-serif": ["PingFang SC", "Heiti TC", "SimHei", "Arial Unicode MS"],
    "axes.unicode_minus": False,
    "font.size": 13,
})

OUT = Path("runtime/figures")
SAMPLE_SEG = 7272  # 长航程、跨度大、弯多的代表性单船航迹


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    df = pd.read_parquet("runtime/dataset/segments.parquet",
                         columns=["seg_id", "lon", "lat", "sog", "split"])

    # 图 A：单船真实航迹（SOG 上色）
    s = df[df["seg_id"] == SAMPLE_SEG]
    plt.figure(figsize=(7, 6))
    sc = plt.scatter(s["lon"], s["lat"], c=s["sog"], s=7, cmap="viridis")
    plt.colorbar(sc, label="对地航速 SOG（节）")
    plt.title(f"真实武汉长江 AIS 航迹示例（{len(s)} 点，约 100km 航程）")
    plt.xlabel("经度"); plt.ylabel("纬度")
    plt.axis("equal"); plt.grid(alpha=0.25); plt.tight_layout()
    plt.savefig(OUT / "fig_real_trajectory.png", dpi=120)
    plt.close()

    # 图 B：多船交通密度（测试集全部航迹叠加）
    test = df[df["split"] == "test"]
    plt.figure(figsize=(7, 6))
    for _, seg in test.groupby("seg_id", sort=False):
        plt.plot(seg["lon"], seg["lat"], color="#1f6feb", lw=0.4, alpha=0.06)
    plt.title(f"武汉长江航段真实 AIS 交通（测试集 {test['seg_id'].nunique():,} 段航迹叠加）")
    plt.xlabel("经度"); plt.ylabel("纬度")
    plt.axis("equal"); plt.grid(alpha=0.25); plt.tight_layout()
    plt.savefig(OUT / "fig_traffic_density.png", dpi=120)
    plt.close()
    print("wrote fig_real_trajectory.png, fig_traffic_density.png")


if __name__ == "__main__":
    main()
