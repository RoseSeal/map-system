"""HTML snapshot parsing: notice metadata, body text and numbered items."""
import html
import re
from dataclasses import dataclass

_SPACE_BETWEEN = re.compile(r'(?<=\S)[ \t　\xa0]+(?=\S)')
_ITEM_START = re.compile(r'^(\d+)[、.．]')
_BODY_START = ('各有关单位', '各有关')
_BODY_END = '特此通告'


@dataclass(frozen=True)
class NoticePage:
    title: str
    notice_no: str | None
    published_at: str  # 'YYYY-MM-DD HH:MM'
    body: str          # normalized body lines joined by '\n'


def compact(text: str) -> str:
    """Remove every whitespace character; used for verbatim evidence checks."""
    return re.sub(r'\s+', '', text or '')


def _strip_tags(fragment: str) -> str:
    fragment = re.sub(r'(?is)<(script|style)[^>]*>.*?</\1>', '', fragment)
    fragment = re.sub(r'(?i)<br\s*/?>|</p>|</div>|</tr>|</li>', '\n', fragment)
    fragment = re.sub(r'<[^>]+>', '', fragment)
    return html.unescape(fragment)


def _normalize_lines(text: str) -> list[str]:
    lines = []
    for line in text.split('\n'):
        line = _SPACE_BETWEEN.sub('', line).strip(' \t　\xa0')
        if line:
            lines.append(line)
    return lines


def _first(pattern: str, page: str) -> str | None:
    match = re.search(pattern, page, re.S)
    if not match:
        return None
    value = compact(_strip_tags(match.group(1)))
    return value or None


def parse_page(page: str) -> NoticePage:
    title = _first(r'<p class="biaoti">(.*?)</p>', page)
    if not title:
        raise ValueError('notice title not found')
    notice_no = _first(r'<p class="wh">(.*?)</p>', page)
    time_match = re.search(r'时间：\s*(\d{4}-\d{2}-\d{2} \d{2}:\d{2})', page)
    if not time_match:
        raise ValueError('publish time not found')
    start = page.find('<p class="biaoti">')
    end = page.find('class="xl_icon"', start)
    lines = _normalize_lines(_strip_tags(page[start:end if end > 0 else len(page)]))
    begin = next((i for i, l in enumerate(lines) if l.startswith(_BODY_START)), None)
    if begin is None:
        raise ValueError('notice body not found')
    stop = next((i for i, l in enumerate(lines) if i > begin and l.startswith(_BODY_END)), len(lines))
    return NoticePage(title, notice_no, time_match.group(1), '\n'.join(lines[begin:stop + 1]))


def split_items(body: str) -> list[str]:
    """Numbered items ('1、…') in document order; a body without numbering is one item."""
    items = [line for line in body.split('\n') if _ITEM_START.match(line)]
    if items:
        return items
    lines = body.split('\n')
    content = [l for l in lines[1:] if not l.startswith(_BODY_END)]
    return ['\n'.join(content)] if content else []
