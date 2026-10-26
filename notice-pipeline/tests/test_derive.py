import json
import unittest
from pathlib import Path

from notice_pipeline.derive import aton_key, blocking_review, derive

DATA = Path(__file__).resolve().parent.parent / 'data'
MANIFEST = {'publisher': '长江武汉航道局', 'published_from': '2026-09-15', 'fetched_at': '2026-10-26T00:00:00+08:00'}
P1 = {'lon': 114.32, 'lat': 30.60}
P2 = {'lon': 114.33, 'lat': 30.61}


def entry(index, action, at, *, name='沙村#2红浮', virtual=False, position=None):
    fields = {
        'action': {'value': action, 'evidence': action},
        'waterway': {'value': '搁排矶水道', 'evidence': '搁排矶水道'},
        'aton_name': {'value': name, 'evidence': name},
        'is_virtual': {'value': virtual, 'evidence': None},
        'position_raw': {'value': 'raw' if position else None, 'evidence': None},
        'position_text': {'value': None if position else '里程', 'evidence': None},
    }
    return {'index': index, 'fields': fields, 'position': position, 'issues': [],
            'effective_at': f'2026-10-{at}T10:00:00+08:00'}


def notice(*items, notice_id='t20261022_1', published='2026-10-25T17:00:00+08:00'):
    return {'notice_id': notice_id, 'url': 'u', 'title': 't', 'notice_no': None,
            'published_at': published, 'extraction_error': None, 'items': list(items)}


def only_aton(state):
    assert len(state['atons']) == 1, state['atons']
    return state['atons'][0]


class DeriveTest(unittest.TestCase):
    def test_set_then_coordinate_adjust_then_mileage_adjust(self):
        state = derive(MANIFEST, [notice(entry(1, 'SET', '01', position=P1), entry(2, 'ADJUST', '02', position=P2),
                                         entry(3, 'ADJUST', '03'))])
        aton = only_aton(state)
        self.assertEqual(aton['state'], 'POSITION_UNVERIFIED')
        self.assertEqual(aton['position'], P2)
        self.assertEqual([h['action'] for h in aton['history']], ['SET', 'ADJUST', 'ADJUST'])

    def test_set_then_remove(self):
        aton = only_aton(derive(MANIFEST, [notice(entry(1, 'SET', '01', position=P1), entry(2, 'REMOVE', '02'))]))
        self.assertEqual(aton['state'], 'REMOVED')

    def test_malfunction_then_restore(self):
        state = derive(MANIFEST, [notice(entry(1, 'SET', '01', position=P1), entry(2, 'MALFUNCTION', '02'),
                                         entry(3, 'RESTORE', '03'))])
        self.assertEqual(only_aton(state)['state'], 'VALID')
        self.assertEqual(len(state['assumptions']), 2)

    def test_items_are_applied_in_time_order(self):
        aton = only_aton(derive(MANIFEST, [notice(entry(1, 'REMOVE', '05'), entry(2, 'SET', '01', position=P1))]))
        self.assertEqual(aton['state'], 'REMOVED')

    def test_unmatched_no_coordinate_goes_to_review(self):
        state = derive(MANIFEST, [notice(entry(1, 'ADJUST', '01', name='其春石白浮'))])
        self.assertEqual(state['atons'], [])
        self.assertEqual(state['review'][0]['reason'], 'UNMATCHED_NO_COORD')

    def test_pre_coordinate_item_joins_history_of_later_located_aid(self):
        state = derive(MANIFEST, [notice(entry(1, 'MALFUNCTION', '22'), entry(2, 'ADJUST', '23', position=P1))])
        aton = only_aton(state)
        self.assertEqual(aton['state'], 'VALID')
        self.assertEqual([h['action'] for h in aton['history']], ['MALFUNCTION', 'ADJUST'])
        self.assertEqual(state['review'], [])

    def test_virtual_and_physical_aids_with_same_name_stay_apart(self):
        state = derive(MANIFEST, [notice(entry(1, 'SET', '01', position=P1, virtual=True), entry(2, 'REMOVE', '02'))])
        self.assertEqual(only_aton(state)['state'], 'VALID')
        self.assertEqual(state['review'][0]['reason'], 'UNMATCHED_NO_COORD')

    def test_only_unmatched_no_coordinate_review_is_non_blocking(self):
        state = derive(MANIFEST, [notice(entry(1, 'ADJUST', '01', name='其春石白浮'))])
        self.assertEqual(blocking_review(state), [])

    def test_key_normalizes_virtual_suffix_and_waterway_prefix(self):
        self.assertEqual(aton_key('汉口水道', '汉口水道武汉二桥＃1红浮（虚拟）', True),
                         aton_key('汉口水道', '武汉二桥#1红浮', True))


class ReproducibilityTest(unittest.TestCase):
    def test_committed_validated_data_reproduces_committed_state(self):
        manifest = json.loads((DATA / 'manifest.json').read_text(encoding='utf-8'))
        validated = [json.loads((DATA / 'validated' / f"{n['notice_id']}.json").read_text(encoding='utf-8'))
                     for n in manifest['notices']]
        committed = json.loads((DATA / 'aton_state.json').read_text(encoding='utf-8'))
        self.assertEqual(derive(manifest, validated), committed)


if __name__ == '__main__':
    unittest.main()
