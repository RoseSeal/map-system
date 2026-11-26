# -*- coding: utf-8 -*-
"""按 UTC 时间和位置核对雷达、视觉观测；无身份辅助匹配。"""
import argparse
import json
import math
import re
from datetime import datetime, timezone
from pathlib import Path


def haversine_m(a, b):
    lon1, lat1, lon2, lat2 = map(math.radians, (*a, *b))
    h = math.sin((lat2-lat1)/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin((lon2-lon1)/2)**2
    return 2 * 6371000 * math.asin(math.sqrt(min(1, max(0, h))))


class LocatedRow(dict):
    """位置只供诊断使用，不进入消息字段。"""
    pass


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'重复 JSON 字段 {key!r}')
        result[key] = value
    return result


def strict_json(text):
    def reject(value):
        raise ValueError(f'非标准 JSON 数值 {value}')
    return json.loads(text, object_pairs_hook=_unique_object, parse_constant=reject)


def load_jsonl(path):
    rows = []
    with Path(path).open(encoding='utf-8') as stream:
        for line_no, text in enumerate(stream, 1):
            if not text.strip():
                continue
            try:
                value = strict_json(text)
                if not isinstance(value, dict):
                    raise ValueError('每行必须为 JSON object')
            except (ValueError, TypeError) as exc:
                raise ValueError(f'{path}:{line_no}: {exc}') from exc
            row = LocatedRow(value)
            row.location = f'{path}:{line_no}'
            rows.append(row)
    return rows


def _fail(row, index, message):
    raise ValueError(f'{getattr(row, "location", "记录 #" + str(index+1))}: {message}')


def _number(value, name, low, high):
    if type(value) not in (int, float) or not math.isfinite(value) or not low <= value <= high:
        raise ValueError(f'{name} 必须为 [{low},{high}] 内有限 JSON number')
    return value


def canonical_utc(value):
    if not isinstance(value, str) or not re.fullmatch(
            r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z', value):
        raise ValueError('timestampUtc 必须为完整 ISO 8601 UTC 时间，尾缀 Z')
    dt = datetime.fromisoformat(value[:-1] + '+00:00')
    return dt.astimezone(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')


def match_rectangular(cost, gate):
    """在 cost 矩阵（n_truth x n_obs）中按门限 gate 做一对一指派。

    超过 gate 的配对视为禁止。返回 (matched_pairs, total_distance)。
    matched_pairs 为 [(i, j), ...]，i 为真值下标，j 为观测下标。
    先最大化匹配数，再最小化总距离。
    """
    n = len(cost)
    m = len(cost[0]) if n else 0
    if n == 0 or m == 0:
        return [], 0.0
    # 构造禁止矩阵
    forbidden = [[cost[i][j] > gate for j in range(m)] for i in range(n)]
    # 大常数：超过任意可匹配总距离
    finite = [cost[i][j] for i in range(n) for j in range(m) if not forbidden[i][j]]
    big = (sum(finite) + 1.0) if finite else 1.0
    # 方阵大小 max(n, m)，用 dummy 行/列（费用 0）允许不匹配
    size = max(n, m)
    inf = float('inf')
    mat = [[inf] * size for _ in range(size)]
    for i in range(size):
        for j in range(size):
            if i < n and j < m:
                mat[i][j] = big if forbidden[i][j] else cost[i][j]
            else:
                mat[i][j] = 0.0
    total, assign = _hungarian(mat)
    pairs = []
    real_total = 0.0
    for j in range(size):
        i = assign[j]
        if i == -1:
            continue
        if i < n and j < m and not forbidden[i][j]:
            pairs.append((i, j))
            real_total += cost[i][j]
    return pairs, real_total


def _hungarian(cost):
    """方阵 cost 的最小费用完美指派（O(n^3)）。

    返回 (total_cost, assignment)，assignment[j]=i 表示列 j 匹配行 i，未匹配为 -1。
    """
    n = len(cost)
    if n == 0:
        return 0.0, []
    inf = float('inf')
    u = [0.0] * (n + 1)
    v = [0.0] * (n + 1)
    p = [0] * (n + 1)
    way = [0] * (n + 1)
    for i in range(1, n + 1):
        p[0] = i
        j0 = 0
        minv = [inf] * (n + 1)
        used = [False] * (n + 1)
        while True:
            used[j0] = True
            i0 = p[j0]
            delta = inf
            j1 = -1
            for j in range(1, n + 1):
                if not used[j]:
                    cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                    if cur < minv[j]:
                        minv[j] = cur
                        way[j] = j0
                    if minv[j] < delta:
                        delta = minv[j]
                        j1 = j
            for j in range(n + 1):
                if used[j]:
                    u[p[j]] += delta
                    v[j] -= delta
                else:
                    minv[j] -= delta
            j0 = j1
            if p[j0] == 0:
                break
        while j0 != 0:
            j1 = way[j0]
            p[j0] = p[j1]
            j0 = j1
    assignment = [-1] * n
    for j in range(1, n + 1):
        if p[j] != 0:
            assignment[j - 1] = p[j] - 1
    total = 0.0
    for j in range(n):
        i = assignment[j]
        if i != -1 and cost[i][j] != inf:
            total += cost[i][j]
    return total, assignment


# ---------------------------------------------------------------------------
# 契约校验
# ---------------------------------------------------------------------------

_SENSOR_FIELDS = {'sourceType', 'timestampUtc', 'latitude', 'longitude', 'sourceConfidence', 'qualityFlags'}
_TRUTH_FIELDS = {'timestampUtc', 'targetId', 'longitude', 'latitude', 'aisEnabled'}
_AIS_FIELDS = {'MSGTIME', 'MMSI', 'LAT', 'LON', 'SOG', 'COG', 'HEADING'}


def _validated_truth(rows):
    result, seen = [], set()
    states = {}
    for i, row in enumerate(rows):
        try:
            if not isinstance(row, dict) or set(row) != _TRUTH_FIELDS:
                raise ValueError('真值字段集合不符')
            t = canonical_utc(row['timestampUtc'])
            target = row['targetId']
            if not isinstance(target, str) or not target.strip():
                raise ValueError('targetId 必须为非空字符串')
            _number(row['latitude'], 'latitude', -90, 90)
            _number(row['longitude'], 'longitude', -180, 180)
            if type(row['aisEnabled']) is not bool:
                raise ValueError('aisEnabled 必须为 bool')
            if (t, target) in seen:
                raise ValueError('同一 tick 的目标真值重复')
            if target in states and states[target] != row['aisEnabled']:
                raise ValueError('本轮验收要求目标全程 AIS 开关一致')
            seen.add((t, target))
            states[target] = row['aisEnabled']
            result.append(dict(row, timestampUtc=t))
        except (ValueError, KeyError, TypeError) as exc:
            _fail(row, i, str(exc))
    return result


def _validated_messages(rows, ais_topic):
    result = []
    topics = {'usv/RadarObservation': 'RADAR', 'usv/VisionObservation': 'VISION'}
    for i, row in enumerate(rows):
        try:
            if not isinstance(row, dict) or set(row) != {'topic', 'payload'}:
                raise ValueError('消息封装必须只有 topic/payload')
            topic, p = row['topic'], row['payload']
            if not isinstance(p, dict) or not isinstance(topic, str):
                raise ValueError('topic 必须为字符串，payload 必须为对象')
            if topic in topics:
                if set(p) != _SENSOR_FIELDS or p['sourceType'] != topics[topic]:
                    raise ValueError('传感器字段或 sourceType/topic 不符契约')
                stamp = canonical_utc(p['timestampUtc'])
                _number(p['latitude'], 'latitude', -90, 90)
                _number(p['longitude'], 'longitude', -180, 180)
                _number(p['sourceConfidence'], 'sourceConfidence', 0, 1)
                flags = p['qualityFlags']
                if not isinstance(flags, list) or any(not isinstance(f, str) or not re.fullmatch(
                        r'[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*', f) for f in flags):
                    raise ValueError('qualityFlags 必须为 UPPER_SNAKE_CASE 字符串数组')
                if len(set(flags)) != len(flags):
                    raise ValueError('qualityFlags 不能重复')
                p = dict(p, timestampUtc=stamp)
            elif topic == ais_topic:
                if set(p) != _AIS_FIELDS:
                    raise ValueError('AIS 字段集合不符契约')
                m = p['MMSI']
                if not isinstance(m, str) or not m.isascii() or not m.isdigit():
                    raise ValueError('MMSI 必须为数字字符串')
                if not isinstance(p['MSGTIME'], str):
                    raise ValueError('MSGTIME 必须为本地时间字符串')
                datetime.strptime(p['MSGTIME'], '%Y-%m-%d %H:%M:%S')
                for key, low, high in [('LAT', -90, 90), ('LON', -180, 180),
                                       ('SOG', 0, 10000), ('COG', 0, 360), ('HEADING', 0, 511)]:
                    _number(p[key], key, low, high)
            else:
                raise ValueError(f'未知 topic {topic!r}')
            result.append({'topic': topic, 'payload': p})
        except (ValueError, TypeError, KeyError) as exc:
            _fail(row, i, str(exc))
    return result


def verify_observations(truth, messages, max_distance_m, ais_topic='usv/AisMessage'):
    _number(max_distance_m, 'max_distance_m', 0.000001, 40000000)
    truth = _validated_truth(truth)
    messages = _validated_messages(messages, ais_topic)
    by_time = {}
    for t in truth:
        by_time.setdefault(t['timestampUtc'], []).append(t)
    off_ids = sorted({t['targetId'] for t in truth if not t['aisEnabled']})
    on_ids = {t['targetId'] for t in truth if t['aisEnabled']}
    ais_counts = {}
    for msg in messages:
        if msg['topic'] == ais_topic:
            m = msg['payload']['MMSI']
            ais_counts[m] = ais_counts.get(m, 0) + 1
    off = {m: {'RADAR': 0, 'VISION': 0, 'aisCount': ais_counts.get(m, 0)} for m in off_ids}
    report = {'aisOffTargetIds': off_ids,
              'aisOffAisCount': sum(v['aisCount'] for v in off.values()),
              'aisCounts': ais_counts, 'maxDistanceM': max_distance_m,
              'matching': 'UTC timestamp, maximum cardinality then minimum total distance'}
    for source, topic in [('RADAR', 'usv/RadarObservation'), ('VISION', 'usv/VisionObservation')]:
        obs_by_time = {}
        for msg in messages:
            if msg['topic'] == topic:
                p = msg['payload']
                obs_by_time.setdefault(p['timestampUtc'], []).append(p)
        observed = sum(map(len, obs_by_time.values()))
        matched, errors, details = 0, [], []
        for ts in sorted(set(by_time) | set(obs_by_time)):
            ts_truth, ts_obs = by_time.get(ts, []), obs_by_time.get(ts, [])
            cost = [[haversine_m((t['longitude'], t['latitude']), (o['longitude'], o['latitude']))
                     for o in ts_obs] for t in ts_truth]
            pairs, _ = match_rectangular(cost, max_distance_m)
            for ti, oi in pairs:
                target = ts_truth[ti]['targetId']
                error = cost[ti][oi]
                matched += 1
                errors.append(error)
                if target in off:
                    off[target][source] += 1
                details.append({'timestampUtc': ts, 'targetId': target, 'observationIndex': oi, 'errorM': error})
        report[source] = {
            'expected': len(truth), 'observed': observed, 'matched': matched,
            'missing': len(truth) - matched, 'unmatched': observed - matched,
            'meanErrorM': sum(errors)/len(errors) if errors else None,
            'rmseErrorM': math.sqrt(sum(e*e for e in errors)/len(errors)) if errors else None,
            'maxErrorM': max(errors) if errors else None,
            'aisOffMatched': sum(v[source] for v in off.values()), 'matches': details}
    for target in off.values():
        target['pass'] = target['aisCount'] == 0 and target['RADAR'] > 0 and target['VISION'] > 0
    report['aisOffTargets'] = off
    # 有开机目标时要求 AIS 流确实出现，避免根本没订阅 AIS 却声称关闭目标零报文。
    report['aisStreamObserved'] = bool(on_ids) and all(ais_counts.get(m, 0) > 0 for m in on_ids)
    report['pass'] = bool(truth) and bool(off) and report['aisStreamObserved'] and all(
        v['pass'] for v in off.values()) and all(report[s]['unmatched'] == 0 for s in ('RADAR', 'VISION'))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--truth', required=True)
    parser.add_argument('--messages', required=True)
    parser.add_argument('--max-distance-m', type=float, required=True)
    parser.add_argument('--report')
    parser.add_argument('--ais-topic', default='usv/AisMessage')
    args = parser.parse_args()
    try:
        if args.report and Path(args.report).resolve() in {Path(args.truth).resolve(), Path(args.messages).resolve()}:
            raise ValueError('报告不能覆盖输入文件')
        report = verify_observations(load_jsonl(args.truth), load_jsonl(args.messages),
                                     args.max_distance_m, args.ais_topic)
        text = json.dumps(report, ensure_ascii=False, allow_nan=False, indent=2)
        if args.report:
            with Path(args.report).open('x', encoding='utf-8') as stream:
                stream.write(text + '\n')
        summary = {key: value for key, value in report.items() if key not in ('RADAR', 'VISION')}
        for source in ('RADAR', 'VISION'):
            summary[source] = {k: v for k, v in report[source].items() if k != 'matches'}
        print(json.dumps(summary, ensure_ascii=False, allow_nan=False, indent=2))
        if not report['pass']:
            parser.exit(2, '核对未满足本轮验收条件\n')
    except (ValueError, OSError) as exc:
        parser.exit(2, f'错误: {exc}\n')


if __name__ == '__main__':
    main()
