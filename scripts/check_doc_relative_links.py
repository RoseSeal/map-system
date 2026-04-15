#!/usr/bin/env python3
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


INLINE_LINK_RE = re.compile(r"(?<!\!)\[[^\]]+\]\(([^)]+)\)")
WINDOWS_ABS_RE = re.compile(r"^[A-Za-z]:[\\/]")
SCHEME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")


def candidate_docs(repo_root: Path) -> list[Path]:
    result = subprocess.run(
        [
            "git",
            "ls-files",
            "-z",
            "--cached",
            "--others",
            "--exclude-standard",
            "--",
            "docs/**/*.md",
            "docs/*.md",
        ],
        cwd=repo_root,
        check=True,
        capture_output=True,
    )
    entries = [entry for entry in result.stdout.decode("utf-8").split("\0") if entry]
    return [repo_root / entry for entry in entries]


def normalize_target(raw_target: str) -> str:
    target = raw_target.strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1].strip()
    target = target.split("#", 1)[0].strip()
    target = target.split("?", 1)[0].strip()
    return target


def is_absolute_local_path(target: str) -> bool:
    if not target:
        return False
    if target.startswith("/"):
        return True
    if target.startswith("file://"):
        return True
    if WINDOWS_ABS_RE.match(target):
        return True
    return False


def should_skip(target: str) -> bool:
    if not target or target.startswith("#"):
        return True
    if SCHEME_RE.match(target) and not target.startswith("file://"):
        return True
    return False


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    violations: list[tuple[Path, int, str]] = []

    for doc_path in candidate_docs(repo_root):
        if not doc_path.is_file():
            continue
        content = doc_path.read_text(encoding="utf-8")
        for line_no, line in enumerate(content.splitlines(), start=1):
            for match in INLINE_LINK_RE.finditer(line):
                target = normalize_target(match.group(1))
                if should_skip(target):
                    continue
                if is_absolute_local_path(target):
                    violations.append((doc_path.relative_to(repo_root), line_no, target))

    if violations:
        print("Docs relative-link check failed. Absolute local paths are not allowed in docs/*.md:")
        for path, line_no, target in violations:
            print(f"- {path}:{line_no} -> {target}")
        return 1

    print("Docs relative-link check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
