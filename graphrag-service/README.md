# GraphRAG Historical Case Service

This sidecar builds and serves a LightRAG index over synthetic maritime cases for map-system v1.1.

## Prerequisites

`GRAPHRAG_LLM_API_KEY` must be set (see `.env.example`). Both the index build and the service fail fast without it; there is no keyless fallback mode.

## Build the Index

From this directory:

```bash
python -m graphrag_service.build_index --cases-dir data/cases --index-dir runtime/index --reset
```

The command writes `runtime/index/catalog.json` and `runtime/index/manifest.json`. The `runtime/` directory is ignored by git and can be rebuilt from `data/cases/*.md`.

## Run the Service

From the repository root:

```bash
docker compose up graphrag
```

The service exposes:

- `GET /health`
- `POST /retrieve`

Example request:

```bash
curl -sS http://localhost:8100/retrieve \
  -H 'Content-Type: application/json' \
  -d '{"situation":{"own_ship_role":"GIVE_WAY","encounter_type":"CROSSING","water_area":"限制水域","visibility":"RESTRICTED_VISIBILITY","risk_level":"WARNING"},"mode":"local","top_k":3}'
```

## Run the Evaluation

The evaluation compares a naive vector RAG baseline with LightRAG `local`, `global`, and `hybrid` modes. Retrieval and answer generation continue to use the configured Zhipu API. The judge uses a local CLI subscription and defaults to Gemini CLI.

Prerequisites:

1. Build the index and start the sidecar.
2. Set `GRAPHRAG_LLM_API_KEY`.
3. Authenticate at least one supported judge CLI: `agy`, `gemini`, `codex`, `copilot`, or `claude`.
4. Set `EVAL_JUDGE_MODEL` to the exact CLI model identifier used for the run.
5. Run the pilot first; a CLI that prints `--version` can still fail the actual judge preflight.

Run the four-query pilot before the full evaluation:

```bash
EVAL_JUDGE_MODEL=<model-id> python -m eval.run_eval --pilot
EVAL_JUDGE_MODEL=<model-id> python -m eval.run_eval
python -m eval.report
```

Select another judge backend or pin its model when needed:

```bash
EVAL_JUDGE_BACKEND=agy EVAL_JUDGE_MODEL=gemini-2.5-pro python -m eval.run_eval --pilot
EVAL_JUDGE_BACKEND=claude EVAL_JUDGE_MODEL=sonnet python -m eval.run_eval --pilot
```

`eval/runtime/` contains ignored retrieval caches, judge outputs, and resumable progress. A complete full run atomically publishes `eval/results/scores.csv` and `eval/results/run_manifest.json`; `python -m eval.report` then writes the committed plots and `eval/results/case_study.md`. Use `--force-retrieval` or `--force-judge` to invalidate one cache layer explicitly.

## Run the Rerank Ablation

The rerank ablation is a controlled follow-up to the published evaluation. It only enables the LightRAG cross-encoder reranker for graph modes; the runner reuses the original manifest defaults for `judge_model`, `sidecar_url`, and `top_k`, and it reuses the original naive retrievals. If the original manifest used the legacy Gemini CLI and `agy` is available, the rerank runner uses `agy` as the migrated judge entrypoint and records that change in the rerank manifest.

Start the sidecar with rerank enabled:

```bash
GRAPHRAG_RERANKER_ENABLED=true \
GRAPHRAG_LLM_API_KEY=<zhipu-key> \
docker compose up graphrag
```

For a smaller HuggingFace cross-encoder model, set both model and backend explicitly:

```bash
GRAPHRAG_RERANKER_ENABLED=true \
GRAPHRAG_RERANKER_MODEL=cross-encoder/ms-marco-MiniLM-L-6-v2 \
GRAPHRAG_RERANKER_BACKEND=hf_sequence_classifier \
GRAPHRAG_LLM_API_KEY=<zhipu-key> \
docker compose up graphrag
```

From `graphrag-service/`, run the ablation:

```bash
GRAPHRAG_RERANKER_ENABLED=true \
GRAPHRAG_RERANKER_MODEL=cross-encoder/ms-marco-MiniLM-L-6-v2 \
GRAPHRAG_RERANKER_BACKEND=hf_sequence_classifier \
GRAPHRAG_LLM_API_KEY=<zhipu-key> \
python -m eval.run_eval --rerank-ablation --force-retrieval --force-judge
```

A complete run publishes `eval/results/scores_rerank.csv` and `eval/results/run_manifest_rerank.json`. The post-processing script then folds those rows into the improvement report:

```bash
python eval/scripts/improvement_sim.py --check-judge-preflight
```

If `--check-judge-preflight` reports a judge authentication failure, fix the CLI account or switch to a supported judge backend before running the ablation. Switching to a non-Gemini judge backend changes the experiment and must be reported separately from the original Gemini-scored run.
