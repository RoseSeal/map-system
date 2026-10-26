"""Fetch Wuhan bureau aids-to-navigation notices into raw HTML snapshots."""
import hashlib
import json
import re
import time
import urllib.parse
import urllib.request
from datetime import date, datetime
from pathlib import Path

LIST_BASE = 'https://www.cjhdj.com.cn/hdfw/hdtg/xy/'
PUBLISHER = '长江武汉航道局'
USER_AGENT = ('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 '
              '(KHTML, like Gecko) Chrome/129.0 Safari/537.36')
_LINK = re.compile(r'<a[^>]+href="([^"]*t(\d{8})_(\d+)\.shtml)"[^>]*>(.*?)</a>', re.S)


def _get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
    with urllib.request.urlopen(request, timeout=20) as response:
        return response.read()


def is_target_title(title: str) -> bool:
    return title.startswith(PUBLISHER) and '航标' in title


def list_entries(page_html: str, page_url: str) -> list[dict]:
    entries = []
    for href, ymd, _, raw_title in _LINK.findall(page_html):
        title = re.sub(r'<[^>]+>|\s+', '', raw_title)
        if not title:
            continue
        entries.append({
            'notice_id': Path(href).stem,
            'url': urllib.parse.urljoin(page_url, href),
            'title': title,
            'list_date': datetime.strptime(ymd, '%Y%m%d').date().isoformat(),
        })
    return entries


def fetch(out_dir: Path, published_from: date, max_pages: int = 40, delay_s: float = 1.0) -> dict:
    snapshots = out_dir / 'snapshots'
    snapshots.mkdir(parents=True, exist_ok=True)
    selected: dict[str, dict] = {}
    for page_no in range(max_pages):
        page_url = LIST_BASE + ('index.shtml' if page_no == 0 else f'index_{page_no}.shtml')
        entries = list_entries(_get(page_url).decode('utf-8', 'replace'), page_url)
        time.sleep(delay_s)
        if not entries:
            break
        for entry in entries:
            if date.fromisoformat(entry['list_date']) >= published_from and is_target_title(entry['title']):
                selected.setdefault(entry['notice_id'], entry)
        if min(date.fromisoformat(e['list_date']) for e in entries) < published_from:
            break

    fetched_at = datetime.now().astimezone().isoformat(timespec='seconds')
    notices = []
    for entry in sorted(selected.values(), key=lambda e: e['notice_id']):
        body = _get(entry['url'])
        time.sleep(delay_s)
        (snapshots / f"{entry['notice_id']}.html").write_bytes(body)
        notices.append({**entry, 'sha256': hashlib.sha256(body).hexdigest()})
    manifest = {
        'publisher': PUBLISHER,
        'list_base': LIST_BASE,
        'published_from': published_from.isoformat(),
        'fetched_at': fetched_at,
        'notices': notices,
    }
    (out_dir / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return manifest
