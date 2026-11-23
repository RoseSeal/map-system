import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from fvessel_tracking_converter import convert_tracking, replay_jsonl


class FvesselTrackingConverterTest(unittest.TestCase):
    def test_converts_xywh_and_matches_fusion_by_frame_left_top(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tracking = root / "tracking.txt"
            fusion = root / "fusion.txt"
            output = root / "observations.jsonl"
            tracking.write_text(
                "2,0,101,712,439,57,1,1,1,1\n"
                "2,1,995,658,144,45,1,1,1,1\n"
                "3,0,101,712,439,57,1,1,1,1\n",
                encoding="utf-8",
            )
            fusion.write_text(
                "2,310000000,101,712,439,57,1,1,1,1\n"
                "3,310000001,999,658,144,45,1,1,1,1\n",
                encoding="utf-8",
            )

            stats = convert_tracking(tracking, output, "Video-10", fusion)
            rows = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]

            self.assertEqual(rows[0]["bbox_xywh_px"], [101, 712, 439, 57])
            self.assertEqual(rows[0]["track_id"], 0)
            self.assertEqual(stats.input_rows, 3)
            self.assertEqual(stats.output_rows, 3)
            self.assertEqual(stats.unique_tracks, 2)
            self.assertEqual(stats.fusion_matched_tracks, 1)
            self.assertEqual(stats.fusion_match_rows, 1)
            self.assertEqual(stats.fusion_unmatched_rows, 2)

    def test_sorts_frames_and_keeps_the_five_field_observation_schema(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tracking = root / "tracking.txt"
            fusion = root / "fusion.txt"
            output = root / "out.jsonl"
            tracking.write_text("4,0,10,20,30,40\n2,1,11,21,31,41\n", encoding="utf-8")
            fusion.write_text("2,310000000,11,21,31,41\n", encoding="utf-8")
            convert_tracking(tracking, output, "Video-test", fusion)
            rows = [json.loads(line) for line in output.read_text().splitlines()]
            self.assertEqual([r["frame"] for r in rows], [2, 4])
            self.assertEqual(set(rows[0]), {"source", "video", "frame", "track_id", "bbox_xywh_px"})

    def test_fusion_ambiguity_does_not_claim_a_matched_track(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tracking = root / "tracking.txt"
            fusion = root / "fusion.txt"
            tracking.write_text("2,0,10,20,30,40\n", encoding="utf-8")
            fusion.write_text("2,310000000,10,20,30,40\n2,310000001,10,20,30,40\n", encoding="utf-8")
            stats = convert_tracking(tracking, root / "out.jsonl", "Video-test", fusion)
            self.assertEqual(stats.fusion_ambiguous_rows, 1)
            self.assertEqual(stats.fusion_matched_tracks, 0)

    def test_rejects_negative_size_before_replacing_existing_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tracking = root / "tracking.txt"
            output = root / "out.jsonl"
            tracking.write_text("2,0,10,20,-30,40\n", encoding="utf-8")
            output.write_text("keep", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "tracking.txt:1"):
                convert_tracking(tracking, output, "Video-test")
            self.assertEqual(output.read_text(), "keep")

    def test_rejects_output_aliasing_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            tracking = Path(tmp) / "tracking.txt"
            tracking.write_text("2,0,10,20,30,40\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                convert_tracking(tracking, tracking, "Video-test")
            self.assertEqual(tracking.read_text(), "2,0,10,20,30,40\n")

    def test_replay_rejects_decreasing_frames(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "out.jsonl"
            path.write_text('{"frame":4}\n{"frame":2}\n', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "out.jsonl:2"):
                list(replay_jsonl(path))

    def test_replay_rejects_malformed_lines_with_path_and_line_number(self):
        cases = [
            ('{"frame": 1}', '{"frame": 2'),      # 无效 JSON
            ('{"frame": 1}', '[{"frame": 2}]'),   # 非对象 JSON：数组
            ('{"frame": 1}', '42'),               # 非对象 JSON：数字
            ('{"frame": 1}', '{"track_id": 1}'),  # 缺失 frame
            ('', '{"frame": -1}'),                # 负数 frame
            ('{"frame": 1}', '{"frame": 1.5}'),   # 小数 frame
            ('{"frame": 1}', '{"frame": true}'),  # 布尔 frame
            ('{"frame": 1}', '{"frame": "2"}'),   # 字符串 frame
        ]
        for first_line, bad_line in cases:
            with self.subTest(bad_line=bad_line):
                with tempfile.TemporaryDirectory() as tmp:
                    path = Path(tmp) / "out.jsonl"
                    path.write_text(f"{first_line}\n{bad_line}\n", encoding="utf-8")
                    with self.assertRaises(ValueError) as context:
                        list(replay_jsonl(path))
                    self.assertIn(f"{path}:2", str(context.exception))

    def test_cli_accepts_video_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            video = root / "Video-test"
            (video / "gt").mkdir(parents=True)
            (video / "gt" / "Video-test_gt_tracking.txt").write_text("2,0,10,20,30,40\n", encoding="utf-8")
            (video / "gt" / "Video-test_gt_fusion.txt").write_text("2,310000000,10,20,30,40\n", encoding="utf-8")
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("fvessel_tracking_converter.py")),
                                     "convert", "--input-dir", str(video), "--output", str(root / "out.jsonl")],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout)["fusion_matched_tracks"], 1)

    def test_replays_jsonl_grouped_by_frame(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "observations.jsonl"
            path.write_text(
                '{"frame": 2, "track_id": 1}\n'
                '{"frame": 2, "track_id": 2}\n'
                '{"frame": 4, "track_id": 1}\n',
                encoding="utf-8",
            )
            frames = list(replay_jsonl(path))
            self.assertEqual([(frame, len(rows)) for frame, rows in frames], [(2, 2), (4, 1)])


if __name__ == "__main__":
    unittest.main()
