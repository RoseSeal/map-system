#!/usr/bin/env python3
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


ALLOWED_CRLF_SUFFIXES = {".bat", ".cmd"}


def candidate_files(repo_root: Path) -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=repo_root,
        check=True,
        capture_output=True,
    )
    entries = [entry for entry in result.stdout.decode("utf-8").split("\0") if entry]
    return [repo_root / entry for entry in entries]


def is_binary(data: bytes) -> bool:
    return b"\0" in data


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check or normalize LF line endings.")
    parser.add_argument(
        "--fix",
        action="store_true",
        help="Rewrite offending files to LF in place.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo_root = Path(__file__).resolve().parent.parent
    offenders: list[Path] = []

    for path in candidate_files(repo_root):
        if not path.is_file():
            continue
        if path.suffix.lower() in ALLOWED_CRLF_SUFFIXES:
            continue
        try:
            data = path.read_bytes()
        except OSError as exc:
            print(f"failed to read {path.relative_to(repo_root)}: {exc}", file=sys.stderr)
            return 2
        if is_binary(data):
            continue
        if b"\r" in data:
            if args.fix:
                path.write_bytes(data.replace(b"\r\n", b"\n").replace(b"\r", b"\n"))
            offenders.append(path.relative_to(repo_root))

    if offenders:
        if args.fix:
            print("Normalized these files to LF:")
            for offender in offenders:
                print(f"- {offender}")
            return 0
        print("CRLF check failed. These tracked text files contain carriage returns:")
        for offender in offenders:
            print(f"- {offender}")
        return 1

    print("LF check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
