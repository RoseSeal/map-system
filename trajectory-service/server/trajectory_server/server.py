from __future__ import annotations

import math
import os
import sys
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from inland_traj.geometry import EARTH_R, to_local_xy
from scripts import train_gru


DEFAULT_OFFSETS = (153, 306, 459)


class RawPoint(BaseModel):
    msg_time: datetime
    latitude: float
    longitude: float
    sog: float
    cog: float


class TargetWindow(BaseModel):
    target_id: str
    points: list[RawPoint] = Field(min_length=1)


class PredictRequest(BaseModel):
    targets: list[TargetWindow] = Field(default_factory=list)


class PredictedPoint(BaseModel):
    latitude: float
    longitude: float
    offset_seconds: int


class TargetPrediction(BaseModel):
    target_id: str
    points: list[PredictedPoint]


class PredictResponse(BaseModel):
    predictions: list[TargetPrediction]


class PredictorConfig(BaseModel):
    model_path: str
    offsets_seconds: list[int]


class OnnxTrajectoryPredictor:
    def __init__(self, model_path: Path, offsets_seconds: list[int]) -> None:
        self.model_path = model_path
        self.offsets_seconds = offsets_seconds
        self.session: Any | None = None
        self.input_name: str | None = None
        self.load_error: str | None = None

    def load(self) -> None:
        if not self.model_path.exists():
            self.load_error = "model_missing"
            return
        try:
            import onnxruntime as ort
        except ImportError:
            self.load_error = "onnxruntime_missing"
            return
        self.session = ort.InferenceSession(str(self.model_path), providers=["CPUExecutionProvider"])
        self.input_name = self.session.get_inputs()[0].name
        self.load_error = None

    @property
    def available(self) -> bool:
        return self.session is not None and self.input_name is not None

    def predict(self, targets: list[TargetWindow]) -> list[TargetPrediction]:
        if not self.available:
            raise HTTPException(status_code=503, detail={"error": self.load_error or "model_missing"})
        if not targets:
            return []

        features = np.stack([feature_matrix(target.points) for target in targets], axis=0)
        assert self.session is not None
        assert self.input_name is not None
        pred_km = self.session.run(None, {self.input_name: features})[0]

        predictions: list[TargetPrediction] = []
        for target, pred in zip(targets, pred_km, strict=True):
            last = target.points[-1]
            points = [
                to_predicted_point(last.latitude, last.longitude, float(x_km), float(y_km), offset)
                for (x_km, y_km), offset in zip(pred, self.offsets_seconds, strict=True)
            ]
            predictions.append(TargetPrediction(target_id=target.target_id, points=points))
        return predictions


def load_config() -> PredictorConfig:
    offsets = os.getenv("TRAJECTORY_PREDICTION_OFFSETS_SECONDS", ",".join(map(str, DEFAULT_OFFSETS)))
    return PredictorConfig(
        model_path=os.getenv("TRAJECTORY_PREDICTION_MODEL_PATH", "runtime/gru_tuned/gru.onnx"),
        offsets_seconds=[int(item.strip()) for item in offsets.split(",") if item.strip()],
    )


def feature_matrix(points: list[RawPoint]) -> np.ndarray:
    ordered = sorted(points, key=lambda point: point.msg_time)
    last = ordered[-1]
    lons = np.array([point.longitude for point in ordered], dtype=float)
    lats = np.array([point.latitude for point in ordered], dtype=float)
    x, y = to_local_xy(lons, lats, last.longitude, last.latitude)
    in_xy = np.stack([x, y], axis=-1)
    timestamps = np.array([point.msg_time.timestamp() for point in ordered], dtype=float)
    in_dt = np.diff(timestamps, prepend=timestamps[:1])
    in_sog = np.nan_to_num(np.array([point.sog for point in ordered], dtype=float), nan=0.0)
    in_cog = np.nan_to_num(np.array([point.cog for point in ordered], dtype=float), nan=0.0)
    return train_gru.build_features(
        in_xy[None, :, :],
        in_dt[None, :],
        in_sog[None, :],
        in_cog[None, :],
    )[0]


def to_predicted_point(lat0: float, lon0: float, x_km: float, y_km: float, offset_seconds: int) -> PredictedPoint:
    x_m = x_km * 1000.0
    y_m = y_km * 1000.0
    lat = lat0 + math.degrees(y_m / EARTH_R)
    cos_lat = math.cos(math.radians(lat0))
    lon = lon0 if abs(cos_lat) < 1e-12 else lon0 + math.degrees(x_m / (EARTH_R * cos_lat))
    return PredictedPoint(latitude=lat, longitude=lon, offset_seconds=offset_seconds)


config = load_config()
predictor = OnnxTrajectoryPredictor(Path(config.model_path), config.offsets_seconds)


@asynccontextmanager
async def lifespan(_: FastAPI):
    predictor.load()
    yield


app = FastAPI(title="map-system trajectory prediction service", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, Any]:
    if not predictor.available:
        raise HTTPException(
            status_code=503,
            detail={"error": predictor.load_error or "model_missing", "model_path": str(predictor.model_path)},
        )
    return {
        "status": "ok",
        "model_path": str(predictor.model_path),
        "offsets_seconds": predictor.offsets_seconds,
    }


@app.post("/predict", response_model=PredictResponse)
async def predict(body: PredictRequest) -> PredictResponse:
    return PredictResponse(predictions=predictor.predict(body.targets))
