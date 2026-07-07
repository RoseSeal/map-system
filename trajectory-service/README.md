# Trajectory Prediction Service

Offline pipeline and learned target-ship trajectory predictor for map-system v1.2. It
validates, on real inland AIS, whether a learned predictor (GRU seq2seq) reduces
CPA/TCPA risk-warning error versus the current constant-velocity (CV) extrapolation,
especially on inland bends. The same trained model can be exported to ONNX and served
through the real-time sidecar used by the Java risk engine.

This directory holds the offline track (data pipeline, baseline, model, evaluation)
and the serving track (`server/`) for the feature-flagged live integration. Runtime
datasets, windows, checkpoints and exported ONNX files remain under ignored `runtime/`.

## Layout

- `inland_traj/geometry.py` — geometry/risk core: CV extrapolation (`cv_predict`, LSQ velocity),
  closed-form CPA/TCPA (`cpa_tcpa_numerical`), ADE/FDE, risk-level mapping, downstream risk error.
- `scripts/` — pipeline: `probe` (data gate), `clean_segment` (clean + segment), `build_windows`
  (windowing), `train_gru` (training/eval), `mine_encounters` (encounter mining + downstream risk),
  `make_figures` / `make_data_figs` (plots), `export_gru_onnx` (serving export).
- `server/` — FastAPI + onnxruntime sidecar. It accepts raw AIS point windows and constructs
  features with the same training-time formula before running the ONNX model.
- `tests/test_geometry.py` — geometry-core unit tests.
- `results/` — tracked small artifacts: `metrics/*.json` (prediction, downstream risk, manifests,
  probe report) and `figures/*`. These are the evidence referenced by the v1.2 docs.

Raw AIS, cleaned parquet, windowed npz and trained weights (~470 MB) are **not** tracked; they
regenerate from the raw dataset via the pipeline. Local run artifacts land under `runtime/`,
which is git-ignored.

## Reproduce

```bash
pip install -r requirements.txt
# Replace CSV_PATH with the raw NBSDC AIS CSV. probe.py and clean_segment.py require --csv.
CSV_PATH=path/to/nbsdc_ais.csv
python scripts/probe.py         --csv "$CSV_PATH"   # data gate + contract  -> runtime/probe
python scripts/clean_segment.py --csv "$CSV_PATH"   # clean + segment       -> runtime/dataset
python scripts/build_windows.py                     # windowing             -> runtime/windows
# Train the tuned config that produced results/metrics/prediction.json (hidden 128 / layers 2 /
# max-train 1.3M -> runtime/gru_tuned/gru.pt). The bare defaults train a smaller model at
# runtime/gru/, which mine_encounters.py / make_figures.py will not load.
python scripts/train_gru.py --out runtime/gru_tuned --hidden 128 --layers 2 --max-train 1300000
python scripts/export_gru_onnx.py --checkpoint runtime/gru_tuned/gru.pt --out runtime/gru_tuned/gru.onnx
python scripts/mine_encounters.py                   # encounters + downstream risk -> runtime/encounters
python scripts/make_figures.py                      # figures               -> runtime/figures
pytest tests/
```

## Serve

```bash
python scripts/export_gru_onnx.py --checkpoint runtime/gru_tuned/gru.pt --out runtime/gru_tuned/gru.onnx
uvicorn server.trajectory_server.server:app --host 0.0.0.0 --port 8200
```

The compose service `trajectory-predictor` mounts `trajectory-service/runtime/gru_tuned`
at `/app/model` and expects `/app/model/gru.onnx`. The Java feature flag is
`engine.trajectory-prediction.learned-enabled=true`; it falls back to CV when the sidecar
is unavailable or a target window is not warm.

## Headline results

- Prediction (168,554 test windows, predicting the next 1/2/3 real observations): FDE
  313.8 m (CV) -> 224.9 m (GRU), -28.3%; improvement grows with horizon (10.1 / 20.4 / 28.3%).
- Downstream risk (105,095 real encounters): miss rate among true dangers 12.2% (CV) -> 8.6% (GRU);
  bend bucket 16.5% -> 11.4%. False-alarm rate rises slightly (2.8% -> 3.2%) — an honest trade-off.

Data source: NBSDC inland AIS dataset (Wuhan Yangtze, 2022-05), fully shared. Full data contract,
methods and results: `docs/v1.2-trajectory-prediction/step-1-offline-pipeline.md`.
