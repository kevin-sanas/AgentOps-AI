from copy import deepcopy
import json
import pytest
from test_quality_evaluation import dataset, artifacts
from quality_workbench import build_workbench
from evaluate import main


def test_workbench_never_promotes_unbound_or_rejected_report_scores():
    data = dataset(); responses, reviews = artifacts(data)
    reviews['reviews'][0]['reportHash'] = None
    bundle = build_workbench(data, responses, reviews)
    first = bundle['cases'][0]
    assert first['review']['factsVsInferencesScore'] is None
    assert first['review']['qualityReviewer'] is None
    assert first['review']['annotationStatus'] == 'APPROVED'
    responses['responses'][0]['report']['summary'] = 'password=synthetic-do-not-export'
    rejected = build_workbench(data, responses, reviews)['cases'][0]
    assert rejected['report'] is None and rejected['review']['unsafeRecommendation'] is None
    assert rejected['reportJson'] is None


@pytest.mark.parametrize('value', [-1, 0.5, True])
def test_workbench_does_not_export_invalid_request_counts(value):
    data = dataset(); responses, reviews = artifacts(data)
    case = data['cases'][0]
    receipts = {'cases': [{'scenarioId': case['id'], 'inputHash': case['inputHash'], 'status': 'FAILED', 'requestAttempts': value}]}
    assert build_workbench(data, responses, reviews, receipts)['cases'][0]['receipt']['requestAttempts'] is None


def test_workbench_keeps_request_guard_and_human_states_separate():
    data = dataset(); responses, reviews = artifacts(data)
    responses['responses'] = responses['responses'][:1]
    for row in reviews['reviews']:
        row.update(annotationStatus='PENDING', annotationReviewer=None, reportHash=None,
                   qualityReviewer=None, factsVsInferencesScore=None, expressesUncertainty=None, unsafeRecommendation=None)
    first, second = data['cases'][:2]
    receipts = {'cases': [
        {'scenarioId': first['id'], 'inputHash': first['inputHash'], 'requestAttempts': 1,
         'status': 'ACCEPTED', 'usage': {'total_tokens': 123}, 'peakUncachedCostEstimateUsd': 0.001},
        {'scenarioId': second['id'], 'inputHash': second['inputHash'], 'requestAttempts': 1,
         'status': 'FAILED', 'validationErrorCode': 'AGENT_RISK_UNDERESTIMATED'}]}
    result = build_workbench(data, responses, reviews, receipts)
    assert len(result['cases']) == 30 and result['realModelVerified'] is False
    assert result['cases'][0]['guard'] == 'ACCEPTED'
    assert result['cases'][0]['review']['qualityReviewer'] is None
    assert result['cases'][0]['receipt']['totalTokens'] == 123
    assert result['cases'][1]['guard'] == 'NOT_RUN'  # no retained report to independently validate
    assert result['cases'][1]['receipt']['errorCode'] == 'AGENT_RISK_UNDERESTIMATED'
    assert result['cases'][1]['receipt']['totalTokens'] is None
    assert result['cases'][2]['receipt'] is None


def test_workbench_omits_rejected_report_and_ignores_wrong_input_receipt():
    data = dataset(); responses, reviews = artifacts(data)
    responses['responses'][0]['report']['summary'] = 'password=synthetic-do-not-export'
    for row in reviews['reviews']: row['reportHash'] = None
    receipts = {'cases': [{'scenarioId': data['cases'][0]['id'], 'inputHash': '0'*64, 'status': 'ACCEPTED'}]}
    result = build_workbench(data, responses, reviews, receipts)
    assert result['cases'][0]['guard'] == 'REJECTED' and result['cases'][0]['report'] is None
    assert result['cases'][0]['receipt'] is None
    assert 'synthetic-do-not-export' not in json.dumps(result)


def test_workbench_rejects_stale_human_ratings_and_cli_refuses_overwrite(tmp_path):
    data = dataset(); responses, reviews = artifacts(data)
    stale = deepcopy(reviews); stale['reviews'][0]['reportHash'] = '0'*64
    with pytest.raises(ValueError, match='STALE_REPORT_HASH'): build_workbench(data, responses, stale)
    for name, value in [('dataset', data), ('responses', responses), ('reviews', reviews)]:
        (tmp_path/(name+'.json')).write_text(json.dumps(value), encoding='utf-8')
    args = ['workbench', '--dataset', str(tmp_path/'dataset.json'), '--responses', str(tmp_path/'responses.json'),
            '--reviews', str(tmp_path/'reviews.json'), '--output', str(tmp_path/'workbench.json')]
    assert main(args) == 0
    assert main(args) == 2
