"""Offline-only quality evaluation CLI. Does not read credentials or call providers."""
import argparse
import json
from pathlib import Path
from quality_evaluation import dump, prepare, score
from quality_review import import_reviews, review_package


def main(argv=None):
    parser = argparse.ArgumentParser(description='Dawnwatch offline synthetic quality evaluation; no API calls.')
    commands = parser.add_subparsers(dest='command', required=True)
    create = commands.add_parser('prepare', help='Export 30 draft cases and pending human-review sheets.')
    create.add_argument('--output', type=Path, required=True)
    for command, help_text in [('score','Score explicitly supplied offline responses and human reviews.'),
                               ('review','Bind report hashes and export readable reports plus a pending human CSV.')]:
        evaluate = commands.add_parser(command, help=help_text)
        for name in ('dataset', 'responses', 'reviews', 'output'): evaluate.add_argument('--'+name, type=Path, required=True)
    imported=commands.add_parser('import-reviews',help='Import explicit human CSV declarations; never infer ratings.')
    for name in ('dataset','responses','csv','output'): imported.add_argument('--'+name,type=Path,required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == 'prepare':
            prepare(args.output)
            print('PREPARED_DRAFT: 30 synthetic cases; human review and real model evaluation pending. No API request.')
            return 0
        if args.output.exists(): raise ValueError('OUTPUT_EXISTS')
        if args.command == 'import-reviews':
            data=json.loads(args.dataset.read_text(encoding='utf-8-sig'))
            responses=json.loads(args.responses.read_text(encoding='utf-8-sig'))
            dump(args.output,import_reviews(data,responses,args.csv))
            print('REVIEW_DECLARATIONS_IMPORTED: no model request; pending fields remain pending.')
            return 0
        inputs = [json.loads(p.read_text(encoding='utf-8-sig')) for p in (args.dataset, args.responses, args.reviews)]
        if args.command == 'review':
            review_package(*inputs,args.output)
            print('REVIEW_PACKAGE_PREPARED: report hashes bound; human judgments are not created.')
            return 0
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
