"""LLM extraction of verbatim fields from notice bodies (Zhipu glm-4.5-air, thinking disabled)."""
import json
import os
import re
import urllib.request
from pathlib import Path

from .text import parse_page

PROMPT_VERSION = 'aton-extract-v1'
MODEL = 'glm-4.5-air'
ENDPOINT = 'https://open.bigmodel.cn/api/paas/v4/chat/completions'

SYSTEM_PROMPT = """你是长江航道通告的信息抽取器。输入是一条航道通告正文，输出 JSON，不输出任何其他文字。

输出格式：
{"items": [ITEM, ...]}
ITEM 的每个字段都是 {"value": ..., "evidence": ...}：
- action: value 取 SET | ADJUST | REMOVE | MALFUNCTION | RESTORE。设置→SET；位置调整→ADJUST；撤除→REMOVE；漂失、熄灭、移位等失常→MALFUNCTION；恢复→RESTORE。evidence 为原文中的动作词。
- effective_time: 该条目发生的时间，value 原样复制（如 "09月24日15:30" 或 "2026年10月1日08:40"）。
- waterway: 所在水道名称，如 "汉口水道"。
- aton_name: 航标名称，不含水道名，不含 "（虚拟）" 和 "电子航道图虚拟航标"，如 "武汉二桥#1红浮"。
- is_virtual: value 为 true 或 false；为 true 时 evidence 为原文中的 "（虚拟）" 或 "虚拟航标" 字样。
- position_raw: 条目中的经纬度坐标原文串，原样复制（如 "30°36′21″.3N，114°19′22″.2E"）；没有坐标时 value 为 null。
- position_text: 没有经纬度、只有里程或文字描述的位置，原样复制（如 "长江中游航道里程18.3千米，左岸"）；有经纬度或没有位置描述时为 null。
- malfunction_kind: 失常类型原文（如 "漂失"）；非失常条目为 null。

规则：
1. 每个编号条目（"1、""2、"……）输出一个 ITEM，按原文顺序，不合并、不拆分。没有编号的通告整段输出一个 ITEM。
2. value 和 evidence 必须从原文逐字复制，不得改写、补全、换算或翻译；evidence 是包含该信息的最短原文片段。
3. 原文没有的信息，value 和 evidence 都填 null。
4. 不要做坐标换算，不要推断年份。"""


def build_messages(body: str) -> list[dict]:
    return [
        {'role': 'system', 'content': SYSTEM_PROMPT},
        {'role': 'user', 'content': body},
    ]


def parse_json_reply(content: str) -> dict:
    content = content.strip()
    fenced = re.match(r'^```(?:json)?\s*(.*?)\s*```$', content, re.S)
    if fenced:
        content = fenced.group(1)
    data = json.loads(content)
    if not isinstance(data, dict) or not isinstance(data.get('items'), list):
        raise ValueError('reply is not {"items": [...]}')
    return data


def _call(api_key: str, body: str) -> str:
    payload = {
        'model': MODEL,
        'messages': build_messages(body),
        'temperature': 0,
        'thinking': {'type': 'disabled'},
        'response_format': {'type': 'json_object'},
    }
    request = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
        headers={'Authorization': f'Bearer {api_key}', 'Content-Type': 'application/json'},
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        reply = json.loads(response.read())
    return reply['choices'][0]['message']['content']


def extract_all(data_dir: Path, only: list[str] | None = None) -> None:
    api_key = os.environ.get('ZHIPU_API_KEY')
    if not api_key:
        raise SystemExit('ZHIPU_API_KEY is not set')
    out_dir = data_dir / 'extracted'
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((data_dir / 'manifest.json').read_text(encoding='utf-8'))
    for notice in manifest['notices']:
        notice_id = notice['notice_id']
        if only and notice_id not in only:
            continue
        page = parse_page((data_dir / 'snapshots' / f'{notice_id}.html').read_text(encoding='utf-8'))
        record = {'notice_id': notice_id, 'model': MODEL, 'prompt_version': PROMPT_VERSION}
        last_error = None
        for _ in range(2):
            try:
                raw = _call(api_key, page.body)
                record['raw_reply'] = raw
                record['result'] = parse_json_reply(raw)
                last_error = None
                break
            except (ValueError, KeyError, json.JSONDecodeError) as error:
                last_error = f'{type(error).__name__}: {error}'
        if last_error:
            record['result'] = None
            record['error'] = last_error
        (out_dir / f'{notice_id}.json').write_text(
            json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        status = 'failed' if last_error else f"{len(record['result']['items'])} items"
        print(f'{notice_id}: {status}', flush=True)
