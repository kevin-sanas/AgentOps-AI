"""Explicit five-case live pilot. No request is made by planning or importing this module."""
from datetime import datetime
from decimal import Decimal
import json
from pathlib import Path
import re
from time import monotonic

from agentops_agent.graph import validate_result
from agentops_agent.models import Invocation
from agentops_agent.safety import redact
from deepseek_check import OneRequestProvider, validation_details
from quality_evaluation import build_dataset, canonical_hash, dump

DEFAULT_CASES = ('http-503', 'request-timeout', 'flapping', 'no-evidence', 'injected-instructions')
RESERVATION = Decimal('0.01')


def plan_fields(dataset, selected, budget_usd):
    if not isinstance(dataset, dict) or not isinstance(dataset.get('cases'), list) or len(dataset['cases']) != 30:
        raise ValueError('ORIGINAL_SYNTHETIC_DATASET_REQUIRED')
    anchor = datetime.fromisoformat(dataset['cases'][0]['input']['capturedAt'])
    if canonical_hash(dataset) != canonical_hash(build_dataset(anchor)):
        raise ValueError('ORIGINAL_SYNTHETIC_DATASET_REQUIRED')
    selected = list(selected)
    budget = Decimal(str(budget_usd))
    if not budget.is_finite() or not Decimal('0') < budget <= Decimal('0.05'):
        raise ValueError('INVALID_PILOT_BUDGET')
    if not 1 <= len(selected) <= 5 or len(set(selected)) != len(selected) or not set(selected) <= {c['id'] for c in dataset['cases']}:
        raise ValueError('INVALID_PILOT_SELECTION')
    reserved = RESERVATION*len(selected)
    if reserved > budget: raise ValueError('INSUFFICIENT_PILOT_BUDGET')
    return {'schemaVersion': 1, 'sampleMode': 'synthetic', 'modelName': 'deepseek-flash',
            'datasetHash': canonical_hash(dataset), 'scenarioIds': selected, 'requestLimit': len(selected),
            'budgetUsd': float(budget), 'reservedCostEstimateUsd': float(reserved),
            'reservationPerRequestUsd': float(RESERVATION), 'automaticRetry': False,
            'maxRequestBytes': 12000, 'maxOutputTokens': 4096, 'requestDeadlineSeconds': 25,
            'pricingCheckedOn': '2026-10-24', 'peakUncachedInputUsdPerMillion': .3,
            'peakOutputUsdPerMillion': 1.2,
            'pricingSource': 'https://api-docs.deepseek.com/quick_start/pricing/',
            'scope': 'Direct official HTTPS, original synthetic evidence; local guard only. Human labels, Java workflow and model quality are separate.',
            'budgetNote': 'Conservative estimated accounting; not an account-level spending limit or actual invoice. Missing usage stops the batch.'}


def make_plan(dataset, directory, *, selected=DEFAULT_CASES, budget_usd=.05):
    directory = Path(directory)
    if directory.exists(): raise ValueError('OUTPUT_EXISTS')
    plan = plan_fields(dataset, selected, budget_usd)
    plan['planHash'] = canonical_hash(plan)
    directory.mkdir(parents=True, exist_ok=False)
    dump(directory/'dataset.json', dataset)
    dump(directory/'plan.json', plan)
    return plan


def load_plan(directory, approved_plan_hash):
    directory = Path(directory)
    plan = json.loads((directory/'plan.json').read_text(encoding='utf-8'))
    dataset = json.loads((directory/'dataset.json').read_text(encoding='utf-8'))
    expected = plan_fields(dataset, plan['scenarioIds'], plan['budgetUsd'])
    expected['planHash'] = canonical_hash(expected)
    if plan != expected or approved_plan_hash != expected['planHash']:
        raise ValueError('PILOT_APPROVAL_MISMATCH')
    if (directory/'started.json').exists(): raise ValueError('PILOT_ALREADY_STARTED')
    return plan, dataset


def persist(directory, result, responses):
    for name, value in [('receipts.json', result), ('responses.json', responses)]:
        temporary = directory/(name+'.tmp')
        dump(temporary, value)
        temporary.replace(directory/name)


def usage_values(provider):
    value = provider.usage
    fields = ('prompt_tokens', 'completion_tokens', 'total_tokens')
    if not isinstance(value, dict) or not all(type(value.get(k)) is int and value[k] >= 0 for k in fields): return None
    return {k: value[k] for k in fields}


def run_plan(directory, key, approved_plan_hash, *, provider_factory=OneRequestProvider):
    directory = Path(directory)
    plan, dataset = load_plan(directory, approved_plan_hash)
    # Exclusive and persistent even after failure/crash: a second process cannot pay again.
    with (directory/'started.json').open('x', encoding='utf-8') as claim:
        json.dump({'planHash': plan['planHash'], 'automaticRetry': False}, claim)
    responses = {'schemaVersion': 1, 'sampleMode': 'synthetic', 'responses': []}
    result = {'schemaVersion': 1, 'sampleMode': 'synthetic', 'planHash': plan['planHash'], 'status': 'RUNNING',
              'requestAttempts': 0, 'acceptedReports': 0, 'knownCostEstimateUsd': 0,
              'unknownPotentialCharges': False, 'realModelQualityMeasured': False, 'cases': []}
    known_cost = Decimal('0')
    cases = {c['id']: c for c in dataset['cases']}
    try:
        persist(directory, result, responses)
        for scenario_id in plan['scenarioIds']:
            if known_cost+RESERVATION > Decimal(str(plan['budgetUsd'])):
                result.update(status='STOPPED', stopReason='BUDGET_RESERVE_EXHAUSTED')
                break
            case = cases[scenario_id]; start = monotonic(); provider = None
            receipt = {'scenarioId': scenario_id, 'inputHash': case['inputHash'], 'status': 'FAILED'}
            try:
                provider = provider_factory(key.strip())
                invocation = Invocation.model_validate(case['input'])
                report = validate_result(provider.analyze(invocation), invocation)
                responses['responses'].append({'scenarioId': scenario_id, 'inputHash': case['inputHash'], 'report': report})
                receipt['status'] = 'ACCEPTED'
                result['acceptedReports'] += 1
            except KeyboardInterrupt:
                receipt['error'] = 'INTERRUPTED_OUTCOME_UNKNOWN'
            except Exception as error:
                # Preserve only safe categories; neither model errors nor rejected reports are serialized.
                receipt['error'] = 'PROVIDER_OR_GUARD_FAILED'
                if isinstance(error, ValueError): receipt.update(validation_details(error))
            finally:
                usage = usage_values(provider) if provider else None
                attempts = provider.attempts if provider else 0
                returned = provider.returned_model if provider else None
                if not isinstance(returned, str) or not re.fullmatch(r'[A-Za-z0-9._:/-]{1,80}', returned) or redact(returned) != returned:
                    returned = None
                if provider: provider.key = ''
                result['requestAttempts'] += attempts
                receipt.update(requestAttempts=attempts, usage=usage, returnedModel=returned,
                               elapsedMs=round((monotonic()-start)*1000))
                if usage:
                    cost = (Decimal(usage['prompt_tokens'])*Decimal('.3')+Decimal(usage['completion_tokens'])*Decimal('1.2'))/1_000_000
                    known_cost += cost
                    receipt['peakUncachedCostEstimateUsd'] = float(cost)
                else:
                    receipt['peakUncachedCostEstimateUsd'] = None
                    if attempts: result['unknownPotentialCharges'] = True
                result['knownCostEstimateUsd'] = float(known_cost)
                result['cases'].append(receipt)
            if receipt['status'] != 'ACCEPTED' or not usage:
                result.update(status='STOPPED', stopReason=receipt.get('error', 'USAGE_UNKNOWN'))
                persist(directory, result, responses)
                break
            persist(directory, result, responses)
        else:
            result['status'] = 'COMPLETED'
        persist(directory, result, responses)
        return result
    finally:
        key = None
