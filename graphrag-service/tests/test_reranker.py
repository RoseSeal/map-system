from __future__ import annotations

import asyncio
import sys
from pathlib import Path
from types import ModuleType

import pytest

from graphrag_service.config import GraphRagConfig
from graphrag_service.reranker import create_rerank_model_func


def test_flagembedding_reranker_returns_lightrag_index_format(monkeypatch) -> None:
    class FakeFlagReranker:
        def __init__(self, model: str, use_fp16: bool):
            self.model = model
            self.use_fp16 = use_fp16

        def compute_score(self, pairs, normalize: bool):
            assert pairs == [["query", "doc-a"], ["query", "doc-b"]]
            assert normalize is True
            return [0.2, 0.9]

    module = ModuleType("FlagEmbedding")
    module.FlagReranker = FakeFlagReranker
    monkeypatch.setitem(sys.modules, "FlagEmbedding", module)

    rerank = create_rerank_model_func(_config(Path("runtime/index")))
    result = asyncio.run(rerank(query="query", documents=["doc-a", "doc-b"], top_n=1))

    assert result == [{"index": 1, "relevance_score": 0.9}]


def test_hf_sequence_classifier_reranker_returns_lightrag_index_format(monkeypatch) -> None:
    class FakeLogits:
        def detach(self):
            return self

        def cpu(self):
            return self

        def tolist(self):
            return [[-1.0, 1.0], [2.0, 0.0]]

    class FakeOutput:
        logits = FakeLogits()

    class FakeTokenizer:
        def __call__(self, queries, documents, padding: bool, truncation: bool, return_tensors: str):
            assert queries == ["query", "query"]
            assert documents == ["doc-a", "doc-b"]
            assert padding is True
            assert truncation is True
            assert return_tensors == "pt"
            return {"input_ids": [1, 2]}

    class FakeModel:
        def eval(self):
            return None

        def __call__(self, **encoded):
            assert encoded == {"input_ids": [1, 2]}
            return FakeOutput()

    class FakeNoGrad:
        def __enter__(self):
            return None

        def __exit__(self, exc_type, exc, traceback):
            return False

    class FakeAutoTokenizer:
        @staticmethod
        def from_pretrained(model: str):
            assert model == "cross-encoder/test"
            return FakeTokenizer()

    class FakeAutoModelForSequenceClassification:
        @staticmethod
        def from_pretrained(model: str):
            assert model == "cross-encoder/test"
            return FakeModel()

    torch_module = ModuleType("torch")
    torch_module.no_grad = FakeNoGrad
    transformers_module = ModuleType("transformers")
    transformers_module.AutoTokenizer = FakeAutoTokenizer
    transformers_module.AutoModelForSequenceClassification = FakeAutoModelForSequenceClassification
    monkeypatch.setitem(sys.modules, "torch", torch_module)
    monkeypatch.setitem(sys.modules, "transformers", transformers_module)

    config = _config(
        Path("runtime/index"),
        backend="hf_sequence_classifier",
        model="cross-encoder/test",
    )
    rerank = create_rerank_model_func(config)
    result = asyncio.run(rerank(query="query", documents=["doc-a", "doc-b"], top_n=1))

    assert result == [{"index": 0, "relevance_score": pytest.approx(0.8807970779778823)}]


def _config(
    index_dir: Path,
    *,
    backend: str = "flagembedding",
    model: str = "BAAI/bge-reranker-v2-m3",
) -> GraphRagConfig:
    return GraphRagConfig(
        llm_base_url="https://example.invalid/v1",
        llm_api_key="test",
        llm_model="glm-4-flash",
        embed_model="embedding-3",
        working_dir=index_dir,
        default_top_k=5,
        default_mode="local",
        embedding_dim=4,
        reranker_enabled=True,
        reranker_model=model,
        reranker_backend=backend,
    )
