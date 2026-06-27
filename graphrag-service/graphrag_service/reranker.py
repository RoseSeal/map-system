from __future__ import annotations

import math
from typing import Any, Callable

from .config import GraphRagConfig


class RerankerUnavailable(RuntimeError):
    pass


def create_rerank_model_func(config: GraphRagConfig) -> Callable[..., Any]:
    backend = config.reranker_backend.strip().lower()
    if backend == "flagembedding":
        return _create_flagembedding_reranker(config)
    if backend in {"hf_sequence_classifier", "transformers"}:
        return _create_hf_sequence_classifier_reranker(config)
    raise RerankerUnavailable(f"unsupported reranker backend: {config.reranker_backend}")


def _create_flagembedding_reranker(config: GraphRagConfig) -> Callable[..., Any]:
    try:
        from FlagEmbedding import FlagReranker
    except ImportError as exc:
        raise RerankerUnavailable(
            "FlagEmbedding is required when GRAPHRAG_RERANKER_ENABLED=true"
        ) from exc

    reranker = FlagReranker(config.reranker_model, use_fp16=True)

    async def rerank_model_func(
        *,
        query: str,
        documents: list[str],
        top_n: int | None = None,
        **_: Any,
    ) -> list[dict[str, float | int]]:
        if not documents:
            return []

        pairs = [[query, document] for document in documents]
        raw_scores = reranker.compute_score(pairs, normalize=True)
        if isinstance(raw_scores, (int, float)):
            scores = [float(raw_scores)]
        else:
            scores = [float(score) for score in raw_scores]

        ranked = sorted(enumerate(scores), key=lambda item: item[1], reverse=True)
        if top_n is not None:
            ranked = ranked[:top_n]
        return [
            {"index": index, "relevance_score": score}
            for index, score in ranked
        ]

    return rerank_model_func


def _create_hf_sequence_classifier_reranker(config: GraphRagConfig) -> Callable[..., Any]:
    try:
        import torch
        from transformers import AutoModelForSequenceClassification, AutoTokenizer
    except ImportError as exc:
        raise RerankerUnavailable(
            "transformers and torch are required for the hf_sequence_classifier reranker backend"
        ) from exc

    tokenizer = AutoTokenizer.from_pretrained(config.reranker_model)
    model = AutoModelForSequenceClassification.from_pretrained(config.reranker_model)
    model.eval()

    async def rerank_model_func(
        *,
        query: str,
        documents: list[str],
        top_n: int | None = None,
        **_: Any,
    ) -> list[dict[str, float | int]]:
        if not documents:
            return []

        scores: list[float] = []
        batch_size = 16
        for start in range(0, len(documents), batch_size):
            batch_docs = documents[start:start + batch_size]
            encoded = tokenizer(
                [query] * len(batch_docs),
                batch_docs,
                padding=True,
                truncation=True,
                return_tensors="pt",
            )
            with torch.no_grad():
                output = model(**encoded)
            logits = output.logits.detach().cpu().tolist()
            scores.extend(_logits_to_scores(logits))

        ranked = sorted(enumerate(scores), key=lambda item: item[1], reverse=True)
        if top_n is not None:
            ranked = ranked[:top_n]
        return [
            {"index": index, "relevance_score": score}
            for index, score in ranked
        ]

    return rerank_model_func


def _logits_to_scores(logits: Any) -> list[float]:
    rows = logits if isinstance(logits, list) else [logits]
    scores: list[float] = []
    for row in rows:
        values = row if isinstance(row, list) else [row]
        if len(values) == 1:
            scores.append(1.0 / (1.0 + math.exp(-float(values[0]))))
        else:
            exps = [math.exp(float(value)) for value in values]
            total = sum(exps)
            scores.append(exps[-1] / total if total else 0.0)
    return scores
