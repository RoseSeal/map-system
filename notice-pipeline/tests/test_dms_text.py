import unittest

from notice_pipeline.dms import find_positions, parse_position
from notice_pipeline.text import compact, split_items


class DmsTest(unittest.TestCase):
    def test_fraction_after_second_mark(self):
        lon, lat = parse_position('30°36′21″.3N，114°19′22″.2E')
        self.assertAlmostEqual(lat, 30 + 36 / 60 + 21.3 / 3600, places=9)
        self.assertAlmostEqual(lon, 114 + 19 / 60 + 22.2 / 3600, places=9)

    def test_two_digit_fraction_and_zero_fraction(self):
        lon, lat = parse_position('30°26′0″.1N，114°10′53″.82E')
        self.assertAlmostEqual(lon, 114 + 10 / 60 + 53.82 / 3600, places=9)
        lon, lat = parse_position('30°34′4″.0N、114°38′20″.0E')
        self.assertAlmostEqual(lat, 30 + 34 / 60 + 4 / 3600, places=9)

    def test_fraction_before_second_mark(self):
        lon, lat = parse_position('30°36′21.3″N 114°19′22.2″E')
        self.assertAlmostEqual(lat, 30 + 36 / 60 + 21.3 / 3600, places=9)

    def test_rejects_incomplete_or_out_of_range(self):
        with self.assertRaises(ValueError):
            parse_position('30°36′21″.3N')
        with self.assertRaises(ValueError):
            parse_position('30°61′21″.3N，114°19′22″.2E')

    def test_find_positions_in_interleaved_text(self):
        text = compact('位 置 ： 3 0 ° 3 6 ′ 2 1 ″ . 3 N ， 1 1 4 ° 1 9 ′ 2 2 ″ . 2 E 。')
        self.assertEqual(len(find_positions(text)), 1)


class SplitItemsTest(unittest.TestCase):
    def test_numbered_items_across_sections(self):
        body = '各有关单位：\n一、航标设置\n1、设置A\n二、航标位置调整\n1、调整B\n2、调整C\n特此通告。'
        self.assertEqual(split_items(body), ['1、设置A', '1、调整B', '2、调整C'])

    def test_unnumbered_body_is_one_item(self):
        body = '各有关单位、航行船舶：\n自2026年10月1日08:40起，某浮标漂失。\n特此通告。'
        self.assertEqual(split_items(body), ['自2026年10月1日08:40起，某浮标漂失。'])


if __name__ == '__main__':
    unittest.main()
