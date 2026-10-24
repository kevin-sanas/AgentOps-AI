"""Offline-only quality evaluation CLI. Does not read credentials or call providers."""
import argparse
import json
from pathlib import Path
from quality_evaluation import dump, prepare, score


def main(argv=None):
    parser = argparse.ArgumentParser(description='Dawnwatch offline synthetic quality evaluation; no API calls.')
    commands = parser.add_subparsers(dest='command', required=True)
    create = commands.add_parser('prepare', help='Export 30 draft cases and pending human-review sheets.')
    create.add_argument('--output', type=Path, required=True)
    evaluate = commands.add_parser('score', help='Score explicitly supplied offline responses and human reviews.')
    for name in ('dataset', 'responses', 'reviews', 'output'): evaluate.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == 'prepare':
            prepare(args.output)
            print('PREPARED_DRAFT: 30 synthetic cases; human review and real model evaluation pending. No API request.')
            return 0
        if args.output.exists(): raise ValueError('OUTPUT_EXISTS')
        inputs = [json.loads(p.read_text(encoding='utf-8-sig')) for p in (args.dataset, args.responses, args.reviews)]
        result = score(*inputs)
        dump(args.output, result)
        print(json.dumps({'status': result['status'], 'coverage': result['coverage'], 'realModelVerified': False}))
        return 0 if result['status'] == 'PASS' else 2
    except (ValueError, OSError, KeyError, TypeError):
        # Imported files and errors may contain sensitive text. Never echo them.
        print('EVALUATION_INPUT_OR_OUTPUT_REJECTED: check file schema, IDs, hashes and output path.')
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
