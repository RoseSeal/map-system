"""将 FVessel gt_tracking 转成按帧排列的 JSONL 视觉观测，并支持离线回放。"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator


@dataclass(frozen=True)
class TrackingRow:
    frame: int
    track_id: int
    left: int
    top: int
    width: int
    height: int


@dataclass(frozen=True)
class FusionRow:
    frame: int
    mmsi: int
    left: int
    top: int
    width: int
    height: int


@dataclass(frozen=True)
class ConversionStats:
    input_rows: int
    output_rows: int
    unique_tracks: int
    fusion_matched_tracks: int
    fusion_match_rows: int
    fusion_unmatched_rows: int
    fusion_ambiguous_rows: int


def _fields(path: Path) -> Iterator[list[str]]:
    with path.open("r", encoding="utf-8-sig") as source:
        for line_number, line in enumerate(source, 1):
            text = line.strip()
            if not text:
                continue
            values = [value.strip() for value in text.split(",")]
            if len(values) < 6:
                raise ValueError(f"{path}:{line_number}: 至少需要 6 个逗号字段")
            try:
                numbers = [int(value) for value in values[:6]]
            except ValueError as error:
                raise ValueError(f"{path}:{line_number}: 前六列必须是整数") from error
            if any(value < 0 for value in numbers[:4]) or numbers[4] <= 0 or numbers[5] <= 0:
                raise ValueError(f"{path}:{line_number}: 帧号、标识和位置不能为负数，宽高必须大于零")
            yield values


def load_tracking(path: Path) -> list[TrackingRow]:
    rows = []
    for values in _fields(path):
        rows.append(TrackingRow(*(int(values[index]) for index in range(6))))
    return rows


def load_fusion(path: Path | None) -> dict[tuple[int, int, int], list[FusionRow]]:
    matches: dict[tuple[int, int, int], list[FusionRow]] = defaultdict(list)
    if path is None:
        return matches
    for values in _fields(path):
        frame, mmsi, left, top, width, height = (int(values[index]) for index in range(6))
        matches[(frame, left, top)].append(FusionRow(frame, mmsi, left, top, width, height))
    return matches


def convert_tracking(
    tracking_path: Path,
    output_path: Path,
    video: str,
    fusion_path: Path | None = None,
) -> ConversionStats:
    for source in (tracking_path, fusion_path):
        if source is not None and (output_path.resolve() == source.resolve()
                                   or (output_path.exists() and output_path.samefile(source))):
            raise ValueError("输出文件不能与输入文件相同")
    tracking_rows = sorted(load_tracking(tracking_path), key=lambda row: (row.frame, row.track_id))
    fusion = load_fusion(fusion_path)
    matched_tracks: set[int] = set()
    match_rows = unmatched_rows = ambiguous_rows = 0
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="\n") as output:
        for row in tracking_rows:
            candidates = fusion.get((row.frame, row.left, row.top), [])
            if len(candidates) == 1:
                match_rows += 1
                matched_tracks.add(row.track_id)
            elif len(candidates) == 0:
                unmatched_rows += 1
            else:
                ambiguous_rows += 1
            observation = {
                "source": "fvessel_gt",
                "video": video,
                "frame": row.frame,
                "track_id": row.track_id,
                "bbox_xywh_px": [row.left, row.top, row.width, row.height],
            }
            output.write(json.dumps(observation, ensure_ascii=False, separators=(",", ":")) + "\n")
    return ConversionStats(
        input_rows=len(tracking_rows),
        output_rows=len(tracking_rows),
        unique_tracks=len({row.track_id for row in tracking_rows}),
        fusion_matched_tracks=len(matched_tracks),
        fusion_match_rows=match_rows,
        fusion_unmatched_rows=unmatched_rows,
        fusion_ambiguous_rows=ambiguous_rows,
    )


def replay_jsonl(path: Path) -> Iterator[tuple[int, list[dict]]]:
    current_frame: int | None = None
    current_rows: list[dict] = []
    with path.open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except ValueError as error:
                raise ValueError(f"{path}:{line_number}: 无效 JSON 行") from error
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{line_number}: 每行必须是 JSON 对象")
            if "frame" not in row:
                raise ValueError(f"{path}:{line_number}: 缺少 frame 字段")
            frame = row["frame"]
            # 布尔值是 int 的子类，必须显式排除；小数和字符串一律拒绝
            if isinstance(frame, bool) or not isinstance(frame, int) or frame < 0:
                raise ValueError(f"{path}:{line_number}: frame 必须是非负整数")
            if current_frame is not None and frame < current_frame:
                raise ValueError(f"{path}:{line_number}: 回放帧号必须按非递减顺序排列")
            if current_frame is None:
                current_frame = frame
            if frame != current_frame:
                yield current_frame, current_rows
                current_frame = frame
                current_rows = []
            current_rows.append(row)
    if current_frame is not None:
        yield current_frame, current_rows


def _convert_command(args: argparse.Namespace) -> None:
    if args.input_dir is not None:
        if args.tracking is not None or args.fusion is not None or args.video is not None:
            raise ValueError("--input-dir 不能与 --tracking、--fusion、--video 同时使用")
        args.video = args.input_dir.name
        gt = args.input_dir / "gt"
        args.tracking = gt / f"{args.video}_gt_tracking.txt"
        args.fusion = gt / f"{args.video}_gt_fusion.txt"
    elif args.tracking is None or args.video is None:
        raise ValueError("需要 --input-dir，或同时提供 --tracking 和 --video")
    stats = convert_tracking(args.tracking, args.output, args.video, args.fusion)
    print(json.dumps(stats.__dict__, ensure_ascii=False, sort_keys=True))


def _replay_command(args: argparse.Namespace) -> None:
    frame_count = 0
    row_count = 0
    for frame, rows in replay_jsonl(args.input):
        frame_count += 1
        row_count += len(rows)
        print(f"frame={frame} boxes={len(rows)}")
    print(f"replay_frames={frame_count} replay_rows={row_count}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    convert = subparsers.add_parser("convert", help="转换 gt_tracking 为 JSONL")
    convert.add_argument("--input-dir", type=Path, help="包含 gt 子目录的视频目录")
    convert.add_argument("--tracking", type=Path)
    convert.add_argument("--fusion", type=Path)
    convert.add_argument("--video")
    convert.add_argument("--output", type=Path, required=True)
    convert.set_defaults(function=_convert_command)
    replay = subparsers.add_parser("replay", help="按帧回放 JSONL")
    replay.add_argument("--input", type=Path, required=True)
    replay.set_defaults(function=_replay_command)
    return parser


if __name__ == "__main__":
    arguments = build_parser().parse_args()
    try:
        arguments.function(arguments)
    except (ValueError, OSError, KeyError) as error:
        raise SystemExit(str(error)) from error
