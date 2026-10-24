"""Explicit, one-shot pilot CLI; planning is offline and run requires local hidden input."""
import argparse
from getpass import getpass
import json
from pathlib import Path
from deepseek_check import interactive_input
from evaluation_pilot import DEFAULT_CASES, load_plan, make_plan, run_plan


def main(argv=None):
    parser = argparse.ArgumentParser(description='Dawnwatch bounded direct DeepSeek pilot. Paid run requires separate approval.')
    commands = parser.add_subparsers(dest='command', required=True)
    plan = commands.add_parser('plan', help='Offline original-synthetic preflight; no Key and no model request.')
    plan.add_argument('--dataset', type=Path, required=True)
    plan.add_argument('--output', type=Path, required=True)
    plan.add_argument('--scenario', action='append')
    plan.add_argument('--budget-usd', default='.05')
    run = commands.add_parser('run', help='Explicit paid run; one attempt per case, no resume/retry.')
    run.add_argument('--directory', type=Path, required=True)
    run.add_argument('--approve-plan', required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == 'plan':
            data = json.loads(args.dataset.read_text(encoding='utf-8-sig'))
            result = make_plan(data, args.output, selected=args.scenario or DEFAULT_CASES, budget_usd=args.budget_usd)
            print(json.dumps(result, ensure_ascii=False))
            return 0
        plan, _ = load_plan(args.directory, args.approve_plan)
        if not interactive_input():
            print('LOCAL_INTERACTIVE_TERMINAL_REQUIRED: do not pipe or send the API Key.')
            return 2
        print(f"PAID PILOT: {plan['requestLimit']} direct DeepSeek requests maximum; estimated budget USD {plan['budgetUsd']}; synthetic data only; no retry.")
        print('Proceed only after scope and budget approval. Key stays in this process; never paste it in chat.')
        key = getpass('DeepSeek API Key (hidden): ')
        try:
            result = run_plan(args.directory, key, args.approve_plan)
        finally:
            key = None
        print(json.dumps({k: result[k] for k in ('status','requestAttempts','acceptedReports','knownCostEstimateUsd','unknownPotentialCharges')},ensure_ascii=False))
        print('Results saved in the plan directory. Human quality review is still required. No automatic retry.')
        return 0 if result['status'] == 'COMPLETED' else 1
    except (ValueError, OSError, KeyError, TypeError, ArithmeticError):
        print('PILOT_INPUT_OR_STATE_REJECTED: check original dataset, plan approval, budget and unused output directory.')
        return 2
    except (KeyboardInterrupt, EOFError):
        print('PILOT_INTERRUPTED: if started.json exists, do not rerun; inspect safe receipts first.')
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
