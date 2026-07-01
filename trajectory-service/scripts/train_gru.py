#!/usr/bin/env python3
"""step-5 GRU 轨迹预测训练 — learned vs CV.

输入 step-5 窗口（runtime/windows/*.npz）：10 点序列 → 预测未来第 1/2/3 点位移。
GRU encoder → MLP 输出 3×2 相对位移（km）。在测试集上以 ADE/FDE（米）对比 CV 基线。

用法:
    python scripts/train_gru.py --windows runtime/windows --out runtime/gru
        [--hidden 96 --layers 1 --epochs 15 --batch 1024 --max-train 400000]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn

H = 3


def load_split(path: Path):
    d = np.load(path)
    cog = np.deg2rad(d["in_cog"])
    X = np.concatenate([
        d["in_xy"] / 1000.0,                  # (N,k,2) km
        (d["in_dt"] / 100.0)[..., None],
        (d["in_sog"] / 10.0)[..., None],
        np.sin(cog)[..., None], np.cos(cog)[..., None],
    ], axis=-1).astype(np.float32)            # (N,k,6)
    Y = (d["tgt_xy"] / 1000.0).astype(np.float32)   # (N,H,2) km
    cv_err = np.linalg.norm(
        d["in_vel"][:, None, :] * d["tgt_dt"][:, :, None] - d["tgt_xy"], axis=2)  # (N,H) m
    return torch.from_numpy(X), torch.from_numpy(Y), cv_err, d["turn"]


class GRUPredictor(nn.Module):
    def __init__(self, in_dim=6, hidden=96, layers=1):
        super().__init__()
        self.gru = nn.GRU(in_dim, hidden, layers, batch_first=True)
        self.head = nn.Sequential(nn.Linear(hidden, hidden), nn.ReLU(), nn.Linear(hidden, H * 2))

    def forward(self, x):
        _, h = self.gru(x)
        return self.head(h[-1]).view(-1, H, 2)


def ade_per_h(pred_km, Y_km):
    """每 horizon 位移误差（米）。"""
    d = torch.linalg.norm(pred_km - Y_km, dim=2) * 1000.0  # (N,H)
    return d.mean(0)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--windows", default="runtime/windows")
    ap.add_argument("--out", default="runtime/gru")
    ap.add_argument("--hidden", type=int, default=96)
    ap.add_argument("--layers", type=int, default=1)
    ap.add_argument("--epochs", type=int, default=15)
    ap.add_argument("--batch", type=int, default=1024)
    ap.add_argument("--max-train", type=int, default=400_000)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()
    torch.manual_seed(args.seed)
    win = Path(args.windows); out = Path(args.out); out.mkdir(parents=True, exist_ok=True)

    Xtr, Ytr, _, _ = load_split(win / "train.npz")
    Xva, Yva, cv_va, _ = load_split(win / "val.npz")
    Xte, Yte, cv_te, _ = load_split(win / "test.npz")
    if len(Xtr) > args.max_train:  # 控 CPU 训练时长
        idx = torch.randperm(len(Xtr))[: args.max_train]
        Xtr, Ytr = Xtr[idx], Ytr[idx]
    print(f"[gru] train={len(Xtr):,} val={len(Xva):,} test={len(Xte):,}")

    model = GRUPredictor(6, args.hidden, args.layers)
    opt = torch.optim.Adam(model.parameters(), lr=1e-3)
    lossf = nn.MSELoss()
    best_val, best_state = float("inf"), None
    for ep in range(args.epochs):
        model.train()
        perm = torch.randperm(len(Xtr))
        for i in range(0, len(Xtr), args.batch):
            b = perm[i: i + args.batch]
            opt.zero_grad()
            loss = lossf(model(Xtr[b]), Ytr[b])
            loss.backward(); opt.step()
        model.eval()
        with torch.no_grad():
            va = ade_per_h(model(Xva), Yva).mean().item()
        print(f"[gru] epoch {ep+1}/{args.epochs} val_ADE={va:.1f}m")
        if va < best_val:
            best_val, best_state = va, {k: v.clone() for k, v in model.state_dict().items()}
    model.load_state_dict(best_state)

    model.eval()
    with torch.no_grad():
        gru_te = ade_per_h(model(Xte), Yte).cpu().numpy()
    cv_te_h = cv_te.mean(0)
    report = {
        "params": vars(args),
        "test_windows": int(len(Xte)),
        "cv_ade_per_horizon_m": [round(float(v), 1) for v in cv_te_h],
        "gru_ade_per_horizon_m": [round(float(v), 1) for v in gru_te],
        "cv_fde_m": round(float(cv_te_h[-1]), 1),
        "gru_fde_m": round(float(gru_te[-1]), 1),
        "improvement_pct_per_horizon": [round(float((c - g) / c * 100), 1)
                                        for c, g in zip(cv_te_h, gru_te)],
    }
    (out / "metrics.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    torch.save(model.state_dict(), out / "gru.pt")
    print(f"[gru] CV  ADE/h(m)={report['cv_ade_per_horizon_m']}  FDE={report['cv_fde_m']}")
    print(f"[gru] GRU ADE/h(m)={report['gru_ade_per_horizon_m']}  FDE={report['gru_fde_m']}")
    print(f"[gru] 提升/h={report['improvement_pct_per_horizon']}%  -> {out}/metrics.json")


if __name__ == "__main__":
    main()
