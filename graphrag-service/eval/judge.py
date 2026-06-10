from __future__ import annotations

import hashlib
import json
import os
import random
import re
import shutil
import subprocess
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable


GROUPS = ("naive", "local", "global", "hybrid")
METRICS = ("comprehensiveness", "diversity", "empowerment")


class JudgeError(RuntimeError):
    pass


@dataclass(frozen=True)
class JudgeBackend:
    name: str
    executable: str
    model: str
    version: str

    def command(self) -> list[str]:
        if self.name == "gemini":
            command = [self.executable, "--prompt", "", "--output-format", "text"]
            if self.model != "cli-default":
                command.extend(["--model", self.model])
            return command
        if self.name == "codex":
            command = [
                self.executable,
                "exec",
                "--ephemeral",
                "--sandbox",
                "read-only",
                "--skip-git-repo-check",
                "--color",
                "never",
            ]
            if self.model != "cli-default":
                command.extend(["--model", self.model])
            command.append("-")
            return command
        if self.name == "copilot":
            command = [
                self.executable,
                "--prompt",
                "{prompt}",
                "--allow-all-tools",
                "--available-tools=",
                "--silent",
                "--no-custom-instructions",
                "--no-ask-user",
            ]
            if self.model != "cli-default":
                command.extend(["--model", self.model])
            return command
        if self.name == "claude":
            command = [
                self.executable,
                "--print",
                "--tools",
                "",
                "--no-session-persistence",
            ]
            if self.model != "cli-default":
                command.extend(["--model", self.model])
            return command
        raise JudgeError(f"unsupported judge backend: {self.name}")


def resolve_backend(name: str, model: str | None = None) -> JudgeBackend:
    if name not in {"gemini", "codex", "copilot", "claude"}:
        raise JudgeError(f"unsupported judge backend: {name}")
    selected_model = model or os.getenv("EVAL_JUDGE_MODEL")
    if not selected_model:
        raise JudgeError(
            "judge model must be explicit: set EVAL_JUDGE_MODEL or pass --judge-model "
            "so evaluation fingerprints cannot reuse an unknown CLI default"
        )
    executable = shutil.which(name)
    if executable is None:
        raise JudgeError(f"judge backend executable not found: {name}")
    version_result = subprocess.run(
        [executable, "--version"],
        text=True,
        capture_output=True,
        timeout=15,
        check=False,
    )
    if version_result.returncode != 0:
        raise JudgeError(f"failed to read {name} CLI version: {version_result.stderr.strip()}")
    version = (version_result.stdout or version_result.stderr).strip().splitlines()[-1]
    return JudgeBackend(name=name, executable=executable, model=selected_model, version=version)


def preflight(
    backend: JudgeBackend,
    runner: Callable[[JudgeBackend, str], str] | None = None,
) -> None:
    invoke = runner or run_backend
    raw = invoke(
        backend,
        '只输出 JSON：{"status":"ok"}。不要调用工具，不要输出其他内容。',
    )
    payload = _extract_json(raw)
    if payload.get("status") != "ok":
        raise JudgeError(
            f"{backend.name} judge preflight returned an unexpected response; "
            "switch EVAL_JUDGE_BACKEND or verify CLI authentication"
        )


def run_backend(backend: JudgeBackend, prompt: str) -> str:
    command = backend.command()
    stdin = prompt
    if "{prompt}" in command:
        prompt_index = command.index("{prompt}")
        command[prompt_index] = prompt
        stdin = None
    result = subprocess.run(
        command,
        input=stdin,
        text=True,
        capture_output=True,
        timeout=300,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise JudgeError(f"{backend.name} judge failed: {detail}")
    return result.stdout.strip()


def build_judge_prompt(
    rubric: str,
    query: dict[str, Any],
    answers: dict[str, str],
) -> tuple[str, dict[str, str]]:
    missing = sorted(set(GROUPS) - answers.keys())
    if missing:
        raise JudgeError(f"missing answer group(s): {', '.join(missing)}")
    groups = list(GROUPS)
    seed = int(hashlib.sha256(query["query_id"].encode("utf-8")).hexdigest(), 16)
    random.Random(seed).shuffle(groups)
    label_to_group = {f"Answer-{index + 1}": group for index, group in enumerate(groups)}
    answer_text = "\n\n".join(
        f"## {label}\n{answers[group]}" for label, group in label_to_group.items()
    )
    prompt = (
        f"{rubric.strip()}\n\n"
        f"# 待评查询\n"
        f"query_id: {query['query_id']}\n"
        f"category: {query['category']}\n"
        f"situation: {json.dumps(query['situation'], ensure_ascii=False, sort_keys=True)}\n"
        f"query: {query['query']}\n\n"
        f"# 匿名答案\n{answer_text}\n"
    )
    return prompt, label_to_group


def parse_judge_output(raw: str, label_to_group: dict[str, str]) -> dict[str, dict[str, Any]]:
    payload = _extract_json(raw)
    answers = payload.get("answers")
    if not isinstance(answers, dict):
        raise JudgeError("judge output must contain an answers object")
    if set(answers) != set(label_to_group):
        raise JudgeError("judge output answer labels do not match the prompt")
    parsed: dict[str, dict[str, Any]] = {}
    for label, group in label_to_group.items():
        item = answers[label]
        if not isinstance(item, dict):
            raise JudgeError(f"{label} score must be an object")
        result: dict[str, Any] = {}
        for metric in METRICS:
            value = item.get(metric)
            if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 10:
                raise JudgeError(f"{label}.{metric} must be an integer from 1 to 10")
            result[metric] = value
        rationale = item.get("rationale")
        if not isinstance(rationale, str) or not rationale.strip():
            raise JudgeError(f"{label}.rationale must be a non-empty string")
        result["rationale"] = rationale.strip()
        parsed[group] = result
    return parsed


def judge_fingerprint(
    query: dict[str, Any],
    answers: dict[str, str],
    rubric: str,
    backend: JudgeBackend,
) -> str:
    return stable_hash(
        {
            "query": query,
            "answers": answers,
            "rubric": rubric,
            "backend": backend.name,
            "model": backend.model,
            "cli_version": backend.version,
            "judge_schema_version": 1,
        }
    )


def judge_answers(
    query: dict[str, Any],
    answers: dict[str, str],
    rubric: str,
    backend: JudgeBackend,
    cache_dir: Path,
    force: bool = False,
    runner: Callable[[JudgeBackend, str], str] | None = None,
) -> dict[str, Any]:
    fingerprint = judge_fingerprint(query, answers, rubric, backend)
    cache_path = cache_dir / fingerprint / "result.json"
    if cache_path.exists() and not force:
        return json.loads(cache_path.read_text(encoding="utf-8"))
    if force and cache_path.exists():
        cache_path.unlink()
    prompt, label_to_group = build_judge_prompt(rubric, query, answers)
    invoke = runner or run_backend
    last_error: Exception | None = None
    raw_outputs: list[str] = []
    for _ in range(2):
        try:
            raw = invoke(backend, prompt)
            raw_outputs.append(raw)
            parsed = parse_judge_output(raw, label_to_group)
            result = {
                "fingerprint": fingerprint,
                "label_to_group": label_to_group,
                "raw_output": raw,
                "scores": parsed,
                "judged_at": datetime.now(timezone.utc).isoformat(),
            }
            _write_json_atomic(cache_path, result)
            return result
        except (JudgeError, subprocess.SubprocessError) as exc:
            last_error = exc
    failure = {
        "fingerprint": fingerprint,
        "label_to_group": label_to_group,
        "raw_outputs": raw_outputs,
        "error": str(last_error),
    }
    _write_json_atomic(cache_path.with_name("failure.json"), failure)
    raise JudgeError(f"judge output failed after one retry: {last_error}")


def stable_hash(payload: Any) -> str:
    canonical = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _extract_json(raw: str) -> dict[str, Any]:
    text = raw.strip()
    fenced = re.search(r"```(?:json)?\s*([\s\S]*?)\s*```", text, re.IGNORECASE)
    if fenced:
        text = fenced.group(1).strip()
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        start = text.find("{")
        end = text.rfind("}")
        if start < 0 or end <= start:
            raise JudgeError("judge output is not JSON")
        try:
            payload = json.loads(text[start : end + 1])
        except json.JSONDecodeError as exc:
            raise JudgeError("judge output is not valid JSON") from exc
    if not isinstance(payload, dict):
        raise JudgeError("judge output root must be an object")
    return payload


def _write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)
