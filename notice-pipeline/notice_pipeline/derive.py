"""Derive aid-to-navigation state from validated notice items (step-1 §4.4)."""
import hashlib
import json
import re
from pathlib import Path

ASSUMPTION_DATUM = '坐标为 2000 国家大地坐标系，按通告原文“航海用途等同于 WGS-84 坐标系”直接作为 WGS84 使用'
ASSUMPTION_RESTORE = '失常航标的无坐标“恢复”条目视为恢复到最后已知位置'
# 只有“区间内无坐标记录”的条目不影响已发布状态的正确性；其余复核原因都可能隐藏状态变更，阻断发布。
NON_BLOCKING_REVIEW = {'UNMATCHED_NO_COORD'}
_NAME_NOISE = re.compile(r'（虚拟）|\(虚拟\)|电子航道图虚拟航标|\s+')


def normalize_name(name: str, waterway: str) -> str:
    name = _NAME_NOISE.sub('', name or '').replace('＃', '#')
    waterway = _NAME_NOISE.sub('', waterway or '')
    return name[len(waterway):] if waterway and name.startswith(waterway) else name


def aton_key(waterway: str, name: str, is_virtual: bool) -> str:
    waterway = _NAME_NOISE.sub('', waterway or '')
    return f'{waterway}|{normalize_name(name, waterway)}|{"virtual" if is_virtual else "physical"}'


def aton_id(key: str) -> str:
    return hashlib.sha1(key.encode('utf-8')).hexdigest()[:12]


def _value(item: dict, name: str):
    return item['fields'][name]['value']


def _history_entry(notice: dict, item: dict) -> dict:
    return {
        'notice_id': notice['notice_id'],
        'item_seq': item['index'],
        'action': _value(item, 'action'),
        'effective_at': item['effective_at'],
        'position_raw': _value(item, 'position_raw'),
        'position_text': _value(item, 'position_text'),
        'evidence': {name: field['evidence'] for name, field in item['fields'].items() if field['evidence']},
    }


def apply_item(state: dict | None, item: dict) -> tuple[dict | None, str | None]:
    """Return (new state, review reason). state is None while the aid is unknown."""
    action, position = _value(item, 'action'), item['position']
    if position and action in ('SET', 'ADJUST', 'RESTORE'):
        return {'state': 'VALID', 'position': position}, None
    if state is None:
        return None, 'UNMATCHED_NO_COORD'
    if action == 'ADJUST':
        return {**state, 'state': 'POSITION_UNVERIFIED'}, None
    if action == 'REMOVE':
        return {**state, 'state': 'REMOVED'}, None
    if action == 'MALFUNCTION':
        return {**state, 'state': 'MALFUNCTION'}, None
    if action == 'RESTORE' and state['state'] == 'MALFUNCTION':
        return {**state, 'state': 'VALID', 'restored': True}, None
    # SET without coordinates on a known aid, or RESTORE of an aid that is not malfunctioning.
    return {**state, 'state': 'POSITION_UNVERIFIED'}, None


def derive(manifest: dict, validated: list[dict]) -> dict:
    notices_by_id = {v['notice_id']: v for v in validated}
    order = {v['notice_id']: i for i, v in enumerate(sorted(validated, key=lambda v: v['published_at']))}
    review, accepted = [], []
    for notice in validated:
        if notice['extraction_error']:
            review.append({'notice_id': notice['notice_id'], 'item_seq': None,
                           'reason': 'EXTRACTION_FAILED', 'detail': notice['extraction_error']})
        for item in notice['items']:
            if item['issues']:
                review.append({'notice_id': notice['notice_id'], 'item_seq': item['index'],
                               'reason': item['issues'][0], 'detail': ', '.join(item['issues'])})
            else:
                accepted.append((notice, item))
        for source in notice.get('unextracted_sources', []):
            review.append({'notice_id': notice['notice_id'], 'item_seq': None, 'reason': 'ITEM_NOT_EXTRACTED',
                           'detail': f"原文第 {source['source_index'] + 1} 个条目未被抽取：{source['text'][:60]}"})
    accepted.sort(key=lambda pair: (pair[1]['effective_at'], order[pair[0]['notice_id']], pair[1]['index']))

    atons: dict[str, dict] = {}
    # 未知航标的无坐标条目先挂起：该航标此后获得坐标时并入其 history，始终没有坐标才进入 review。
    pending: dict[str, list[tuple[dict, dict]]] = {}
    restored = False
    for notice, item in accepted:
        waterway, name = _value(item, 'waterway'), _value(item, 'aton_name')
        is_virtual = bool(_value(item, 'is_virtual'))
        key = aton_key(waterway, name, is_virtual)
        current = atons.get(key)
        new_state, reason = apply_item(current and {'state': current['state'], 'position': current['position']}, item)
        if reason:
            pending.setdefault(key, []).append((notice, item))
            continue
        restored = restored or new_state.pop('restored', False)
        record = current or {
            'aton_id': aton_id(key),
            'waterway': _NAME_NOISE.sub('', waterway),
            'name': normalize_name(name, waterway),
            'is_virtual': is_virtual,
            'history': [_history_entry(n, i) for n, i in pending.pop(key, [])],
        }
        record.update(new_state)
        record['last_action'] = _value(item, 'action')
        record['effective_at'] = item['effective_at']
        record['history'].append(_history_entry(notice, item))
        atons[key] = record

    for entries in pending.values():
        for notice, item in entries:
            review.append({'notice_id': notice['notice_id'], 'item_seq': item['index'], 'reason': 'UNMATCHED_NO_COORD',
                           'detail': f"{_value(item, 'waterway')}{normalize_name(_value(item, 'aton_name'), _value(item, 'waterway'))}"
                                     '：覆盖区间内无该航标坐标记录'})
    review.sort(key=lambda r: (r['notice_id'], r['item_seq'] or 0))

    published = sorted(v['published_at'][:10] for v in validated)
    assumptions = [ASSUMPTION_DATUM] + ([ASSUMPTION_RESTORE] if restored else [])
    fields_order = ('aton_id', 'waterway', 'name', 'is_virtual', 'state', 'position',
                    'last_action', 'effective_at', 'history')
    return {
        'schema_version': 1,
        'publisher': manifest['publisher'],
        'coverage': {
            'published_from': manifest['published_from'],
            'published_to': published[-1] if published else None,
            'fetched_at': manifest['fetched_at'],
        },
        'assumptions': assumptions,
        'atons': [{k: a[k] for k in fields_order} for a in sorted(atons.values(), key=lambda a: a['aton_id'])],
        'notices': [
            {'notice_id': v['notice_id'], 'notice_no': v['notice_no'], 'title': v['title'],
             'published_at': v['published_at'][:10], 'url': v['url']}
            for v in sorted(notices_by_id.values(), key=lambda v: v['published_at'])
        ],
        'review': review,
    }


def blocking_review(state: dict) -> list[dict]:
    """Review entries that may hide a state change; only UNMATCHED_NO_COORD is informational."""
    return [entry for entry in state['review'] if entry['reason'] not in NON_BLOCKING_REVIEW]


def derive_all(data_dir: Path) -> None:
    manifest = json.loads((data_dir / 'manifest.json').read_text(encoding='utf-8'))
    validated = [json.loads((data_dir / 'validated' / f"{n['notice_id']}.json").read_text(encoding='utf-8'))
                 for n in manifest['notices']]
    state = derive(manifest, validated)
    (data_dir / 'aton_state.json').write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n',
                                               encoding='utf-8')
    counts: dict[str, int] = {}
    for aton in state['atons']:
        counts[aton['state']] = counts.get(aton['state'], 0) + 1
    print(f"atons={len(state['atons'])} {counts} review={len(state['review'])}")
