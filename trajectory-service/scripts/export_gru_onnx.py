#!/usr/bin/env python3
"""Export the tuned GRU trajectory predictor checkpoint to ONNX for serving."""
from __future__ import annotations

import argparse
import inspect
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

import train_gru


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", default="runtime/gru_tuned/gru.pt")
    parser.add_argument("--out", default="runtime/gru_tuned/gru.onnx")
    parser.add_argument("--hidden", type=int, default=128)
    parser.add_argument("--layers", type=int, default=2)
    parser.add_argument("--window-size", type=int, default=10)
    parser.add_argument("--check-batch", type=int, default=2)
    parser.add_argument("--check-tolerance", type=float, default=1e-5)
    args = parser.parse_args()

    checkpoint = Path(args.checkpoint)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)

    model = train_gru.GRUPredictor(in_dim=6, hidden=args.hidden, layers=args.layers)
    model.load_state_dict(torch.load(checkpoint, map_location="cpu"))
    model.eval()

    dummy = torch.zeros(1, args.window_size, 6, dtype=torch.float32)
    export_kwargs = {
        "input_names": ["features"],
        "output_names": ["pred_xy_km"],
        "dynamic_axes": {"features": {0: "batch"}, "pred_xy_km": {0: "batch"}},
        "opset_version": 17,
    }
    if "dynamo" in inspect.signature(torch.onnx.export).parameters:
        export_kwargs["dynamo"] = False
    torch.onnx.export(model, dummy, out, **export_kwargs)
    check_export(model, out, args.check_batch, args.window_size, args.check_tolerance)
    print(f"[onnx] exported {checkpoint} -> {out}")


def check_export(model: train_gru.GRUPredictor, onnx_path: Path, batch: int, window_size: int, tolerance: float) -> None:
    torch.manual_seed(0)
    sample = torch.randn(batch, window_size, 6, dtype=torch.float32)
    with torch.no_grad():
        torch_out = model(sample).detach().cpu().numpy()

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    onnx_out = session.run(None, {input_name: sample.numpy()})[0]
    max_abs_diff = float(np.max(np.abs(torch_out - onnx_out)))
    print(f"[onnx] parity max_abs_diff={max_abs_diff:.8g} tolerance={tolerance:.8g}")
    if max_abs_diff > tolerance:
        raise SystemExit(
            f"ONNX export parity failed: max_abs_diff={max_abs_diff:.8g} > tolerance={tolerance:.8g}"
        )


if __name__ == "__main__":
    main()
