"""Shared feature construction for offline training and online serving."""
from __future__ import annotations

import numpy as np


def build_features(in_xy: np.ndarray, in_dt: np.ndarray, in_sog: np.ndarray, in_cog: np.ndarray) -> np.ndarray:
    cog = np.deg2rad(in_cog)
    return np.concatenate([
        in_xy / 1000.0,
        (in_dt / 100.0)[..., None],
        (in_sog / 10.0)[..., None],
        np.sin(cog)[..., None],
        np.cos(cog)[..., None],
    ], axis=-1).astype(np.float32)
