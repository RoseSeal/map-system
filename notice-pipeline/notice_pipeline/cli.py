"""Command line entry: python -m notice_pipeline <command>."""
import argparse
import json
import shutil
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / 'data'
EVAL = ROOT / 'eval'
BACKEND_STATE = ROOT.parent / 'backend' / 'map-service' / 'src' / 'main' / 'resources' / 'notices' / 'aton_state.json'
PUBLISHED_FROM = date(2026, 9, 15)


def main(argv=None):
    parser = argparse.ArgumentParser(prog='notice_pipeline', description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('fetch', help='download list pages and notice snapshots')
    extract = sub.add_parser('extract', help='LLM extraction (needs ZHIPU_API_KEY)')
    extract.add_argument('--only', nargs='*', help='notice ids to (re)extract')
    sub.add_parser('validate', help='verbatim, coordinate and time checks')
    sub.add_parser('derive', help='derive aid-to-navigation state')
    publish = sub.add_parser('publish', help='copy aton_state.json into backend resources')
    publish.add_argument('--allow-incomplete', action='store_true',
                         help='publish even when blocking review entries exist')
    evaluation = sub.add_parser('eval', help='annotation sheet and report')
    evaluation.add_argument('--init', action='store_true', help='(re)create annotations.csv from validated data')
    args = parser.parse_args(argv)

    if args.command == 'fetch':
        from .fetch import fetch
        manifest = fetch(DATA, PUBLISHED_FROM)
        print(f"fetched {len(manifest['notices'])} notices")
    elif args.command == 'extract':
        from .extract import extract_all
        extract_all(DATA, only=args.only)
    elif args.command == 'validate':
        from .validate import validate_all
        validate_all(DATA)
    elif args.command == 'derive':
        from .derive import derive_all
        derive_all(DATA)
    elif args.command == 'publish':
        from .derive import blocking_review
        state = json.loads((DATA / 'aton_state.json').read_text(encoding='utf-8'))
        blocking = blocking_review(state)
        if blocking and not args.allow_incomplete:
            for entry in blocking:
                print(f"  {entry['notice_id']} #{entry['item_seq']}: {entry['reason']} {entry['detail']}")
            raise SystemExit(f'refusing to publish: {len(blocking)} blocking review entries '
                             '(fix extraction or pass --allow-incomplete)')
        BACKEND_STATE.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(DATA / 'aton_state.json', BACKEND_STATE)
        print(f'published {BACKEND_STATE}')
    elif args.command == 'eval':
        from .evalreport import init_annotations, write_report
        if args.init:
            init_annotations(DATA, EVAL)
        else:
            write_report(DATA, EVAL)


if __name__ == '__main__':
    main()
