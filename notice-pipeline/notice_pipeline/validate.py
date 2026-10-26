"""Deterministic checks on LLM extraction: verbatim evidence, coordinates, times."""
import json
import math
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .dms import find_positions, parse_position
from .text import compact, parse_page, split_items

# Approximate bounding box of the Wuhan bureau reach; a sanity check, not a jurisdiction boundary.
BBOX = {'lon_min': 113.0, 'lon_max': 116.5, 'lat_min': 29.3, 'lat_max': 31.0}
COORD_TOLERANCE_M = 1.0
CST = timezone(timedelta(hours=8))
TEXT_FIELDS = ('effective_time', 'waterway', 'aton_name', 'position_raw', 'position_text', 'malfunction_kind')
ALL_FIELDS = ('action',) + TEXT_FIELDS + ('is_virtual',)
# 动作取值必须能由其依据中的关键词支持（与抽取 prompt 的动作映射一致）。
ACTION_KEYWORDS = {
    'SET': ('设置',),
    'ADJUST': ('调整',),
    'REMOVE': ('撤除',),
    'RESTORE': ('恢复',),
    'MALFUNCTION': ('漂失', '熄灭', '失常', '移位', '损坏', '缺失', '倾覆'),
}
ACTIONS = set(ACTION_KEYWORDS)
_TIME = re.compile(r'(?:(\d{4})年)?(\d{1,2})月(\d{1,2})日(\d{1,2})[:：](\d{2})')


def distance_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    mid = math.radians((a[1] + b[1]) / 2)
    dx = (a[0] - b[0]) * 111320.0 * math.cos(mid)
    dy = (a[1] - b[1]) * 111320.0
    return math.hypot(dx, dy)


def in_bbox(lon: float, lat: float) -> bool:
    return BBOX['lon_min'] <= lon <= BBOX['lon_max'] and BBOX['lat_min'] <= lat <= BBOX['lat_max']


def parse_effective_time(raw: str, published: datetime) -> datetime:
    """Resolve 'MM月DD日HH:MM' (year from the publish date, rolled back across a year end)."""
    match = _TIME.search(compact(raw))
    if not match:
        raise ValueError(f'unrecognized time: {raw!r}')
    year, month, day, hour, minute = match.groups()
    month, day = int(month), int(day)
    if year:
        year = int(year)
    else:
        year = published.year - 1 if month > published.month else published.year
    value = datetime(year, month, day, int(hour), int(minute), tzinfo=CST)
    end_of_publish_day = published.replace(hour=23, minute=59, second=59)
    if value > end_of_publish_day:
        raise ValueError(f'time {value.isoformat()} is after publish date {published.date()}')
    return value


def _field(item: dict, name: str) -> tuple:
    field = item.get(name) or {}
    return field.get('value'), field.get('evidence')


def _locate(item: dict, sources: list[str], used: set[int]) -> int | None:
    """Index of the numbered source item that contains this item's name and action evidence."""
    name = compact(_field(item, 'aton_name')[0] or '')
    action = compact(_field(item, 'action')[1] or '')
    for index, text in enumerate(sources):
        if index in used:
            continue
        source = compact(text)
        if name and name in source and action in source:
            return index
    return None


def validate_item(item: dict, body: str, sources: list[str], used: set[int], published: datetime) -> dict:
    issues: list[str] = []
    fields = {name: dict(zip(('value', 'evidence'), _field(item, name))) for name in ALL_FIELDS}

    if fields['action']['value'] not in ACTIONS or not fields['waterway']['value'] or not fields['aton_name']['value']:
        issues.append('EXTRACTION_FAILED')

    source_index = _locate(item, sources, used)
    if source_index is None:
        issues.append('EVIDENCE_NOT_FOUND')
        source_text = None
        baseline = []
    else:
        used.add(source_index)
        source_text = sources[source_index]
        baseline = find_positions(source_text)

    # 依据与文本取值必须逐字出现在本条目原文中，不能借用同一通告其他条目的文字。
    item_compact = compact(source_text or '')
    for name in ALL_FIELDS:
        value, evidence = fields[name]['value'], fields[name]['evidence']
        if evidence is not None and compact(str(evidence)) not in item_compact:
            issues.append('EVIDENCE_NOT_FOUND')
        if name in TEXT_FIELDS and value is not None and compact(str(value)) not in item_compact:
            issues.append('EVIDENCE_NOT_FOUND')

    # 取值必须与依据语义对应：动作由依据关键词支持，虚拟标记与本条目原文一致。
    action, action_evidence = fields['action']['value'], compact(fields['action']['evidence'] or '')
    if action in ACTIONS and not any(keyword in action_evidence for keyword in ACTION_KEYWORDS[action]):
        issues.append('VALUE_MISMATCH')
    is_virtual = fields['is_virtual']['value']
    if not isinstance(is_virtual, bool) or (source_text is not None and is_virtual != ('虚拟' in item_compact)):
        issues.append('VALUE_MISMATCH')

    position = None
    raw = fields['position_raw']['value']
    if raw:
        try:
            lon, lat = parse_position(raw)
            position = {'lon': round(lon, 7), 'lat': round(lat, 7)}
            if not in_bbox(lon, lat):
                issues.append('OUT_OF_BBOX')
            if len(baseline) != 1 or distance_m((lon, lat), baseline[0]) > COORD_TOLERANCE_M:
                issues.append('COORD_MISMATCH')
        except ValueError:
            issues.append('COORD_PARSE_FAILED')
    elif baseline:
        issues.append('COORD_MISMATCH')

    effective_at = None
    try:
        effective_at = parse_effective_time(fields['effective_time']['value'] or '', published).isoformat()
    except ValueError:
        issues.append('TIME_PARSE_FAILED')

    return {
        'source_index': source_index,
        'source_text': source_text,
        'fields': fields,
        'position': position,
        'baseline_positions': [{'lon': round(p[0], 7), 'lat': round(p[1], 7)} for p in baseline],
        'effective_at': effective_at,
        'issues': list(dict.fromkeys(issues)),
    }


def validate_notice(notice: dict, page_html: str, extracted: dict) -> dict:
    page = parse_page(page_html)
    published = datetime.strptime(page.published_at, '%Y-%m-%d %H:%M').replace(tzinfo=CST)
    sources = split_items(page.body)
    result = (extracted or {}).get('result')
    items, used = [], set()
    for index, item in enumerate((result or {}).get('items', []), start=1):
        checked = validate_item(item, page.body, sources, used, published)
        items.append({'index': index, **checked})
    # 未被任何抽取条目覆盖的原文条目：可能是漏抽的状态变更，必须进入复核。
    unextracted = [{'source_index': i, 'text': text} for i, text in enumerate(sources) if i not in used]
    return {
        'notice_id': notice['notice_id'],
        'url': notice['url'],
        'title': page.title,
        'notice_no': page.notice_no,
        'published_at': published.isoformat(),
        'source_item_count': len(sources),
        'llm_item_count': len(items),
        'extraction_error': None if result else (extracted or {}).get('error', 'missing extraction'),
        'items': items,
        'unextracted_sources': unextracted,
    }


def validate_all(data_dir: Path) -> None:
    out_dir = data_dir / 'validated'
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((data_dir / 'manifest.json').read_text(encoding='utf-8'))
    for notice in manifest['notices']:
        notice_id = notice['notice_id']
        extracted_path = data_dir / 'extracted' / f'{notice_id}.json'
        extracted = json.loads(extracted_path.read_text(encoding='utf-8')) if extracted_path.exists() else None
        page_html = (data_dir / 'snapshots' / f'{notice_id}.html').read_text(encoding='utf-8')
        validated = validate_notice(notice, page_html, extracted)
        (out_dir / f'{notice_id}.json').write_text(
            json.dumps(validated, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        rejected = sum(1 for item in validated['items'] if item['issues'])
        print(f"{notice_id}: {validated['llm_item_count']}/{validated['source_item_count']} items, {rejected} rejected")
