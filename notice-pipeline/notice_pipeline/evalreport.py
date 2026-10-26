"""Annotation sheet and extraction evaluation report (step-1 §4.5)."""
import csv
import json
from pathlib import Path

from .derive import aton_key, aton_id
from .validate import distance_m

SAMPLE_NOTICES = (
    't20260924_490370',  # demo virtual aids near the Wuhan Second Bridge
    't20260918_489986',  # mileage-only adjustments
    't20260928_490570',  # mileage-only adjustments
    't20261003_490888',  # mixed coordinate / mileage adjustments
    't20261022_492297',  # removals
    't20261022_492303',  # malfunction (drifted buoy)
    't20261001_490824',  # malfunction (drifted buoy)
    't20261019_492013',  # single item with bearing/distance and coordinates
)
FIELDS = ('action', 'effective_time', 'waterway', 'aton_name', 'is_virtual',
          'position_raw', 'position_text', 'malfunction_kind')
COLUMNS = ('notice_id', 'item_index', 'field', 'llm_value', 'reference', 'auto_issues', 'correct', 'note')


def _load(data_dir: Path) -> tuple[list[dict], dict]:
    manifest = json.loads((data_dir / 'manifest.json').read_text(encoding='utf-8'))
    validated = [json.loads((data_dir / 'validated' / f"{n['notice_id']}.json").read_text(encoding='utf-8'))
                 for n in manifest['notices']]
    state = json.loads((data_dir / 'aton_state.json').read_text(encoding='utf-8'))
    return validated, state


def _match_reference(item: dict, state: dict) -> str:
    fields = item['fields']
    key = aton_key(fields['waterway']['value'], fields['aton_name']['value'], bool(fields['is_virtual']['value']))
    ident = aton_id(key)
    return ident if any(a['aton_id'] == ident for a in state['atons']) else 'UNMATCHED'


def init_annotations(data_dir: Path, eval_dir: Path) -> None:
    """Prefill one row per sampled item field; 'correct' starts as the reviewer's to fill."""
    validated, state = _load(data_dir)
    rows = []
    for notice in validated:
        if notice['notice_id'] not in SAMPLE_NOTICES:
            continue
        for item in notice['items']:
            for field in FIELDS:
                value = item['fields'][field]['value']
                reference = item['source_text'] or ''
                if field == 'position_raw' and item['baseline_positions']:
                    reference = json.dumps(item['baseline_positions'], ensure_ascii=False)
                rows.append([notice['notice_id'], item['index'], field,
                             json.dumps(value, ensure_ascii=False), reference,
                             ';'.join(item['issues']), '', ''])
            if not item['position'] and not item['issues']:
                rows.append([notice['notice_id'], item['index'], 'aton_match',
                             _match_reference(item, state), item['source_text'] or '', '', '', ''])
    eval_dir.mkdir(parents=True, exist_ok=True)
    with (eval_dir / 'annotations.csv').open('w', encoding='utf-8', newline='') as handle:
        writer = csv.writer(handle, lineterminator='\n')
        writer.writerow(COLUMNS)
        writer.writerows(rows)
    print(f'wrote {len(rows)} annotation rows')


def _rate(rows: list[dict]) -> str:
    judged = [r for r in rows if r['correct'] in ('Y', 'N')]
    if not judged:
        return '—'
    ok = sum(r['correct'] == 'Y' for r in judged)
    return f'{ok}/{len(judged)} ({ok / len(judged):.1%})'


def write_report(data_dir: Path, eval_dir: Path) -> None:
    validated, state = _load(data_dir)
    with (eval_dir / 'annotations.csv').open(encoding='utf-8') as handle:
        rows = list(csv.DictReader(handle))
    unjudged = [r for r in rows if r['correct'] not in ('Y', 'N')]
    if unjudged:
        raise SystemExit(f'{len(unjudged)} annotation rows are not judged (correct must be Y or N)')

    lines = ['# 航标通告抽取评测报告', '',
             '> 生成：`python -m notice_pipeline eval`；人工判定见 [`annotations.csv`](./annotations.csv)。', '']
    sampled = sorted({r['notice_id'] for r in rows})
    items = {(r['notice_id'], r['item_index']) for r in rows}
    lines += ['## 1. 样本', '',
              f'- 通告：{len(sampled)} 条（{", ".join(sampled)}）；条目：{len(items)} 个；判定行：{len(rows)}。',
              f'- 覆盖区间：{state["coverage"]["published_from"]} 至 {state["coverage"]["published_to"]}，'
              f'全区间共 {len(validated)} 条通告。', '']

    lines += ['## 2. 字段级正确率（人工判定）', '', '| 字段 | 正确 / 判定 |', '|---|---|']
    for field in FIELDS:
        lines.append(f'| `{field}` | {_rate([r for r in rows if r["field"] == field])} |')
    field_rows = [r for r in rows if r['field'] in FIELDS]
    lines += [f'| **全部字段** | {_rate(field_rows)} |',
              f'| 航标匹配（无坐标条目，`aton_match`） | {_rate([r for r in rows if r["field"] == "aton_match"])} |', '']
    wrong_items = {(r['notice_id'], r['item_index']) for r in field_rows if r['correct'] == 'N'}
    lines += [f'条目级全字段正确：{len(items) - len(wrong_items)}/{len(items)}。', '']

    mismatched = [n for n in validated if n['llm_item_count'] != n['source_item_count']]
    lines += ['## 3. 条目切分（全区间，自动）', '',
              f'切分一致：{len(validated) - len(mismatched)}/{len(validated)} 条通告。', '']
    if mismatched:
        lines += ['| 通告 | LLM 条目 | 原文编号条目 |', '|---|---|---|']
        lines += [f'| {n["notice_id"]} | {n["llm_item_count"]} | {n["source_item_count"]} |' for n in mismatched]
        lines.append('')

    deviations = [distance_m((i['position']['lon'], i['position']['lat']),
                             (i['baseline_positions'][0]['lon'], i['baseline_positions'][0]['lat']))
                  for n in validated for i in n['items'] if i['position'] and len(i['baseline_positions']) == 1]
    with_coords = sum(1 for n in validated for i in n['items'] if i['baseline_positions'])
    lines += ['## 4. 坐标偏差（全区间，自动）', '',
              f'- 原文含坐标的条目：{with_coords}；LLM 坐标串可换算并可与正则基准比较：{len(deviations)}。',
              f'- 偏差最大值：{max(deviations):.3f} m；平均：{sum(deviations) / len(deviations):.3f} m。'
              if deviations else '- 无可比较条目。',
              f'- 超过 1 m 容差：{sum(d > 1.0 for d in deviations)} 个。', '']

    reasons: dict[str, int] = {}
    for entry in state['review']:
        reasons[entry['reason']] = reasons.get(entry['reason'], 0) + 1
    lines += ['## 5. 待复核（全区间）', '', '| 原因 | 条目数 |', '|---|---|']
    lines += [f'| `{reason}` | {count} |' for reason, count in sorted(reasons.items())]
    lines += ['', '## 6. 判定为错误的字段', '', '| 通告 | 条目 | 字段 | LLM 值 | 说明 |', '|---|---|---|---|---|']
    for r in rows:
        if r['correct'] == 'N':
            lines.append(f"| {r['notice_id']} | {r['item_index']} | `{r['field']}` | {r['llm_value']} | {r['note']} |")
    states: dict[str, int] = {}
    for aton in state['atons']:
        states[aton['state']] = states.get(aton['state'], 0) + 1
    lines += ['', '## 7. 说明', '',
              '- 判定方式：`annotations.csv` 由 `eval --init` 预填 LLM 值与参照原文，`correct` 由实施方逐行对照原文判定，用户抽检；'
              '判定为 Y 但带说明的行列在下方。',
              f'- 状态推演结果：{len(state["atons"])} 个航标，状态分布 {states}。'
              '覆盖区间内的撤除、按里程调整与失常条目，在其生效时都没有对应航标的坐标记录（早于坐标记录的条目并入该航标 history），'
              '非 VALID 状态分支由单元测试覆盖，未被真实数据触发。']
    lines += [f"- {r['notice_id']} 第 {r['item_index']} 条 `{r['field']}`：{r['note']}"
              for r in rows if r['correct'] == 'Y' and r['note']]
    (eval_dir / 'report.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(f'wrote {eval_dir / "report.md"}')

