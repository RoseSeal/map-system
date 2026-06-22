from __future__ import annotations

import inspect
from pathlib import Path
from typing import Any

from .config import GraphRagConfig


class LightRagUnavailable(RuntimeError):
    pass


class LightRagClient:
    def __init__(self, working_dir: Path, config: GraphRagConfig):
        self.working_dir = working_dir
        self.config = config
        self._rag = self._create_instance()
        self._query_param_cls = self._load_query_param()
        self._initialized = False

    def _create_instance(self) -> Any:
        try:
            from lightrag import LightRAG
            from lightrag.llm.openai import openai_complete_if_cache, openai_embed
            from lightrag.utils import Tokenizer, wrap_embedding_func_with_attrs
        except ImportError as exc:
            raise LightRagUnavailable("LightRAG package is not installed") from exc

        async def llm_model_func(
            prompt: str,
            system_prompt: str | None = None,
            history_messages: list[dict[str, str]] | None = None,
            **kwargs: Any,
        ) -> str:
            return await openai_complete_if_cache(
                self.config.llm_model,
                prompt,
                system_prompt=system_prompt,
                history_messages=history_messages or [],
                api_key=self.config.llm_api_key,
                base_url=self.config.llm_base_url,
                **kwargs,
            )

        @wrap_embedding_func_with_attrs(
            embedding_dim=self.config.embedding_dim,
            max_token_size=8192,
        )
        async def embedding_func(texts: list[str]):
            # .func accesses the unwrapped helper exposed by lightrag-hku==1.4.9.
            return await openai_embed.func(
                texts,
                model=self.config.embed_model,
                api_key=self.config.llm_api_key,
                base_url=self.config.llm_base_url,
            )

        kwargs = {"working_dir": str(self.working_dir)}
        signature = inspect.signature(LightRAG)
        if "llm_model_func" in signature.parameters:
            kwargs["llm_model_func"] = llm_model_func
        if "llm_model_name" in signature.parameters:
            kwargs["llm_model_name"] = self.config.llm_model
        if "embedding_func" in signature.parameters:
            kwargs["embedding_func"] = embedding_func
        if "tokenizer" in signature.parameters:
            kwargs["tokenizer"] = Tokenizer("unicode-char", _UnicodeCharTokenizer())
        return LightRAG(**kwargs)

    def _load_query_param(self) -> Any:
        try:
            from lightrag import QueryParam
        except ImportError as exc:
            raise LightRagUnavailable("LightRAG QueryParam is not available") from exc
        return QueryParam

    async def insert(self, text: str) -> None:
        await self._initialize()
        if hasattr(self._rag, "ainsert"):
            await self._rag.ainsert(text)
            return
        result = self._rag.insert(text)
        if inspect.isawaitable(result):
            await result

    async def query(self, query: str, mode: str, only_need_context: bool = False) -> str:
        await self._initialize()
        param = self._create_query_param(mode, only_need_context)
        if hasattr(self._rag, "aquery"):
            result = await self._rag.aquery(query, param=param)
        else:
            result = self._rag.query(query, param=param)
            if inspect.isawaitable(result):
                result = await result
        return str(result or "")

    async def _initialize(self) -> None:
        if self._initialized:
            return
        if hasattr(self._rag, "initialize_storages"):
            result = self._rag.initialize_storages()
            if inspect.isawaitable(result):
                await result
        from lightrag.kg.shared_storage import initialize_pipeline_status

        await initialize_pipeline_status()
        self._initialized = True

    def _create_query_param(self, mode: str, only_need_context: bool) -> Any:
        signature = inspect.signature(self._query_param_cls)
        kwargs = {"mode": mode}
        if "only_need_context" in signature.parameters:
            kwargs["only_need_context"] = only_need_context
        return self._query_param_cls(**kwargs)


class _UnicodeCharTokenizer:
    def encode(self, content: str) -> list[int]:
        return [ord(char) for char in content]

    def decode(self, tokens: list[int]) -> str:
        return "".join(chr(token) for token in tokens)
