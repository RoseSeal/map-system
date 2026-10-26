# Aid-to-Navigation Notice Pipeline

Offline pipeline for map-system v1.3 step-1. It turns Changjiang Wuhan Waterway Bureau
(长江武汉航道局) aids-to-navigation notices into a reproducible state file that the backend
loads at startup to draw notices on the map and alert when the own-ship planned route passes
a valid aid. The LLM only locates verbatim text; coordinate conversion, validation, state
derivation and route hit detection are deterministic (ADR-017).

Source: <https://www.cjhdj.com.cn/hdfw/hdtg/xy/> (public HTML, no login). Coordinates are
CGCS2000 and are used as WGS84, as the notices themselves state ("航海用途等同于 WGS-84 坐标系").

## Commands

Python 3.12, standard library only. Run from this directory:

```bash
python3 -m notice_pipeline fetch      # list pages + notice HTML snapshots -> data/snapshots/, data/manifest.json
python3 -m notice_pipeline extract    # LLM extraction (needs ZHIPU_API_KEY) -> data/extracted/
python3 -m notice_pipeline validate   # verbatim / coordinate / time checks -> data/validated/
python3 -m notice_pipeline derive     # state derivation -> data/aton_state.json
python3 -m notice_pipeline publish    # copy to backend resources; refuses while blocking review entries exist
python3 -m notice_pipeline eval       # eval/annotations.csv -> eval/report.md (--init to rebuild the sheet)
python3 -m unittest discover -s tests -t .
```

Only `fetch` and `extract` need the network; everything after runs from committed files, and
`tests/test_derive.py` checks that the committed `validated/` data reproduces the committed
`aton_state.json`.

## Stages

| Stage | Module | Notes |
|---|---|---|
| Fetch | `fetch.py` | Pages the downstream notice column until notices predate `PUBLISHED_FROM` (2026-09-15); keeps titles starting with 长江武汉航道局 that mention 航标; sequential requests, 1 s apart |
| Parse | `text.py` | Title, notice number, publish time and body from the page; removes inter-character spaces; splits numbered items |
| Extract | `extract.py` | `glm-4.5-air`, thinking disabled, temperature 0, prompt `aton-extract-v1`; every field is `{value, evidence}` copied verbatim; coordinates stay as raw strings |
| Validate | `validate.py`, `dms.py` | Evidence and text values must appear verbatim in their own item; the action must be supported by its evidence keyword and the virtual flag must match the item text; numbered source items not covered by any extraction are reported as `ITEM_NOT_EXTRACTED`; DMS parsed by the program and compared with a regex baseline from the same item (≤ 1 m); approximate bounding box 113.0–116.5E, 29.3–31.0N; item time not after the publish day |
| Derive | `derive.py` | Aid identity = waterway + name + virtual flag; items applied in time order to `VALID` / `POSITION_UNVERIFIED` / `MALFUNCTION` / `REMOVED`; coordinate-free items before an aid's first coordinate join its history; aids never located go to `review` |
| Eval | `evalreport.py` | Field-level manual judgement on sampled notices plus automatic split, coordinate-deviation and review statistics |

Results for the committed snapshot are in [`eval/report.md`](eval/report.md).

## Limits

- The snapshot ends at `coverage.published_to`; later changes are unknown to the system.
- Mileage-only or place-name positions are not converted to coordinates.
- Errors in the source text itself (for example a mistyped latitude) pass validation when copied
  verbatim; the bounding box only catches gross errors.
