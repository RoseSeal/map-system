import json
import unittest
from datetime import datetime
from pathlib import Path

from notice_pipeline.derive import blocking_review, derive
from notice_pipeline.validate import CST, parse_effective_time, validate_item, validate_notice

DATA = Path(__file__).resolve().parent.parent / 'data'

BODY = ('各有关单位、航行船舶：\n'
        '1、09月24日15:30设置汉口水道武汉二桥#1红浮（虚拟）电子航道图虚拟航标，位置：30°36′21″.3N，114°19′22″.2E。\n'
        '特此通告。')
SOURCES = [BODY.split('\n')[1]]
PUBLISHED = datetime(2026, 9, 24, 17, 32, tzinfo=CST)


def field(value, evidence=None):
    return {'value': value, 'evidence': evidence if evidence is not None else value}


def item(**overrides):
    base = {
        'action': field('SET', '设置'),
        'effective_time': field('09月24日15:30'),
        'waterway': field('汉口水道'),
        'aton_name': field('武汉二桥#1红浮'),
        'is_virtual': field(True, '（虚拟）'),
        'position_raw': field('30°36′21″.3N，114°19′22″.2E'),
        'position_text': field(None),
        'malfunction_kind': field(None),
    }
    base.update(overrides)
    return base


class EffectiveTimeTest(unittest.TestCase):
    def test_year_from_publish_date(self):
        self.assertEqual(parse_effective_time('09月24日15:30', PUBLISHED).isoformat(), '2026-09-24T15:30:00+08:00')

    def test_explicit_year_and_full_width_colon(self):
        self.assertEqual(parse_effective_time('2026年10月1日08：40', datetime(2026, 10, 1, 8, 44, tzinfo=CST)).day, 1)

    def test_rolls_back_across_year_end(self):
        published = datetime(2027, 1, 2, 17, 0, tzinfo=CST)
        self.assertEqual(parse_effective_time('12月31日10:00', published).year, 2026)

    def test_rejects_time_after_publish_day(self):
        with self.assertRaises(ValueError):
            parse_effective_time('09月25日08:00', PUBLISHED)


class ValidateItemTest(unittest.TestCase):
    def test_valid_item_has_no_issues(self):
        result = validate_item(item(), BODY, SOURCES, set(), PUBLISHED)
        self.assertEqual(result['issues'], [])
        self.assertAlmostEqual(result['position']['lon'], 114.3228333, places=6)

    def test_rewritten_evidence_is_rejected(self):
        result = validate_item(item(aton_name=field('武汉二桥1号红浮')), BODY, SOURCES, set(), PUBLISHED)
        self.assertIn('EVIDENCE_NOT_FOUND', result['issues'])

    def test_coordinate_differing_from_source_is_rejected(self):
        changed = item(position_raw=field('30°36′21″.3N，114°19′22″.2E', '30°36′21″.3N，114°19′22″.2E'))
        changed['position_raw']['value'] = '30°36′21″.3N，114°19′23″.2E'
        result = validate_item(changed, BODY, SOURCES, set(), PUBLISHED)
        self.assertIn('COORD_MISMATCH', result['issues'])

    def test_missing_coordinate_is_rejected_when_source_has_one(self):
        result = validate_item(item(position_raw=field(None)), BODY, SOURCES, set(), PUBLISHED)
        self.assertIn('COORD_MISMATCH', result['issues'])

    def test_action_value_must_match_its_evidence(self):
        result = validate_item(item(action=field('REMOVE', '设置')), BODY, SOURCES, set(), PUBLISHED)
        self.assertIn('VALUE_MISMATCH', result['issues'])

    def test_virtual_flag_must_match_item_text(self):
        result = validate_item(item(is_virtual=field(False, '（虚拟）')), BODY, SOURCES, set(), PUBLISHED)
        self.assertIn('VALUE_MISMATCH', result['issues'])
        result = validate_item(item(is_virtual=field('true', '（虚拟）')), BODY, SOURCES, set(), PUBLISHED)
        self.assertIn('VALUE_MISMATCH', result['issues'])

    def test_evidence_borrowed_from_another_item_is_rejected(self):
        body = BODY.replace('特此通告。', '2、09月24日16:00撤除汉口水道某白浮。\n特此通告。')
        sources = body.split('\n')[1:3]
        result = validate_item(item(action=field('REMOVE', '撤除')), body, sources, set(), PUBLISHED)
        self.assertIn('EVIDENCE_NOT_FOUND', result['issues'])

    def test_out_of_bbox(self):
        body = BODY.replace('114°19′22″.2E', '120°19′22″.2E')
        sources = [body.split('\n')[1]]
        result = validate_item(item(position_raw=field('30°36′21″.3N，120°19′22″.2E')), body, sources, set(), PUBLISHED)
        self.assertIn('OUT_OF_BBOX', result['issues'])


class IncompleteExtractionTest(unittest.TestCase):
    def test_dropped_item_goes_to_blocking_review(self):
        manifest = json.loads((DATA / 'manifest.json').read_text(encoding='utf-8'))
        notice = next(n for n in manifest['notices'] if n['notice_id'] == 't20260924_490370')
        page = (DATA / 'snapshots' / 't20260924_490370.html').read_text(encoding='utf-8')
        extracted = json.loads((DATA / 'extracted' / 't20260924_490370.json').read_text(encoding='utf-8'))
        del extracted['result']['items'][3]

        validated = validate_notice(notice, page, extracted)
        state = derive(manifest, [validated])

        self.assertEqual(len(validated['unextracted_sources']), 1)
        self.assertEqual([e['reason'] for e in blocking_review(state)], ['ITEM_NOT_EXTRACTED'])


if __name__ == '__main__':
    unittest.main()
