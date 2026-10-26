"""Degree-minute-second coordinate parsing for notice text.

Accepted second notations: 21″.3, 21.3″, 4″.0, 53″.82 (fraction before or after ″).
"""
import re

_DMS = re.compile(
    r"(\d{1,3})\s*°\s*(\d{1,2})\s*[′']\s*(\d{1,2}(?:\.\d+)?)\s*(?:″|\"|'')\s*(?:\.(\d+))?\s*([NSEW])"
)


def _to_decimal(deg: str, minute: str, sec: str, frac: str | None, hemi: str) -> float:
    seconds = float(sec + ('.' + frac if frac and '.' not in sec else ''))
    if int(minute) >= 60 or seconds >= 60:
        raise ValueError(f'minute/second out of range: {deg}°{minute}′{sec}″')
    value = int(deg) + int(minute) / 60 + seconds / 3600
    return -value if hemi in 'SW' else value


def find_positions(text: str) -> list[tuple[float, float]]:
    """All (lon, lat) pairs in text order; a pair is one latitude followed by one longitude."""
    pairs, lat = [], None
    for match in _DMS.finditer(text or ''):
        value = _to_decimal(*match.groups())
        if match.group(5) in 'NS':
            lat = value
        elif lat is not None:
            pairs.append((value, lat))
            lat = None
    return pairs


def parse_position(raw: str) -> tuple[float, float]:
    """Parse exactly one coordinate pair; raise ValueError otherwise."""
    pairs = find_positions(raw)
    if len(pairs) != 1:
        raise ValueError(f'expected one coordinate pair, got {len(pairs)}: {raw!r}')
    return pairs[0]
