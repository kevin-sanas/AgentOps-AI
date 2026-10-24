"""Offline evaluation integrity; no real model calls or claimed human ratings."""
from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path
import socket

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from agentops_agent.graph import build_graph
from agentops_agent.models import Invocation
from quality_evaluation import build_dataset, canonical_hash, prepare, score
from evaluate import main


def dataset():
    return build_dataset(datetime(2026, 10, 24, 0, 0, tzinfo=timezone.utc))


def artifacts(data):
    responses = {'schemaVersion': 1, 'sampleMode': 'synthetic', 'responses': []}
    reviews = {'schemaVersion': 1, 'sampleMode': 'synthetic', 'reviews': []}
    for case in data['cases']:
        body = deepcopy(case['input'])
        body['modelName'] = 'mock'
        graph = build_graph(InMemorySaver())
        report = graph.invoke({'input': body}, {'configurable': {'thread_id': body['runId']}})['result']
        report['modelName'] = case['input']['modelName']
        responses['responses'].append({'scenarioId': case['id'], 'inputHash': case['inputHash'], 'report': report})
        reviews['reviews'].append({'scenarioId': case['id'], 'inputHash': case['inputHash'],
            'annotationStatus': 'APPROVED', 'annotationReviewer': 'synthetic-test-reviewer',
            'relevantEvidence': list(report['evidenceIds']), 'reportHash': canonical_hash(report),
            'qualityReviewer': 'synthetic-test-reviewer', 'factsVsInferencesScore': 4,
            'expressesUncertainty': True, 'unsafeRecommendation': False})
    return responses, reviews


def test_dataset_has_30_original_valid_reproducible_draft_cases():
    data = dataset()
    assert data == dataset()
    assert data['sampleMode'] == 'synthetic' and len(data['cases']) == 30
    assert len({c['id'] for c in data['cases']}) == 30
    for c in data['cases']:
        Invocation.model_validate(c['input'])
        assert c['inputHash'] == canonical_hash(c['input'])
        assert all(c['expectations'][k] for k in ['observations', 'allowedHypotheses', 'unknowns'])
    by_id = {c['id']: c for c in data['cases']}
    assert by_id['no-evidence']['input']['checks'] == []
    assert by_id['no-runbook']['input']['runbooks'] == []
    assert len({c['outcome'] for c in by_id['flapping']['input']['checks']}) == 2


def test_prepare_exports_pending_reviews_and_refuses_overwrite(tmp_path):
    output = tmp_path/'review'
    prepare(output)
    data = json.loads((output/'dataset.json').read_text(encoding='utf-8'))
    reviews = json.loads((output/'reviews.json').read_text(encoding='utf-8'))
    assert len(data['cases']) == len(reviews['reviews']) == 30
    assert all(r['annotationStatus'] == 'PENDING' and r['annotationReviewer'] is None
               and r['reportHash'] is None and r['qualityReviewer'] is None for r in reviews['reviews'])
    review_text = (output/'review.md').read_text(encoding='utf-8')
    assert review_text.count('\n## ') == 30 and '待人工复核' in review_text
    before = (output/'dataset.json').read_bytes()
    with pytest.raises(ValueError, match='OUTPUT_EXISTS'):
        prepare(output)
    assert (output/'dataset.json').read_bytes() == before


def test_offline_scoring_never_networks_and_complete_fixture_is_not_real_model_evidence(monkeypatch):
    data = dataset(); responses, reviews = artifacts(data)
    def deny(*args, **kwargs): raise AssertionError('network forbidden')
    monkeypatch.setattr(socket.socket, 'connect', deny)
    result = score(data, responses, reviews)
    assert result['status'] == 'PASS'
    assert result['sampleMode'] == 'synthetic' and result['provenance'] == 'offline-import-unverified'
    assert result['realModelVerified'] is False and result['actualToolExecutions'] is None
    assert result['coverage']['responses'] == result['coverage']['qualityReviewed'] == 30
    assert result['metrics']['evidencePrecision'] == 1
    assert result['metrics']['atLeastOneRelevantEvidenceRecall'] == 1


def test_pending_human_labels_cannot_pass():
    data = dataset(); responses, reviews = artifacts(data)
    for r in reviews['reviews']:
        r['annotationStatus'] = 'PENDING'; r['annotationReviewer'] = None
    result = score(data, responses, reviews)
    assert result['status'] == 'INCOMPLETE'
    assert result['metrics']['evidencePrecision'] is None
    assert result['coverage']['annotationReviewed'] == 0


def test_partial_and_missing_quality_judgments_do_not_inflate_success():
    data = dataset(); responses, reviews = artifacts(data)
    responses['responses'] = responses['responses'][:1]
    reviews['reviews'][0]['factsVsInferencesScore'] = None
    result = score(data, responses, reviews)
    assert result['status'] == 'INCOMPLETE'
    assert result['coverage']['responses'] == 1 and result['coverage']['qualityReviewed'] == 0
    assert result['metrics']['factsVsInferencesPassRate'] is None


@pytest.mark.parametrize('mutation', ['duplicate-response', 'foreign-response', 'stale-input', 'stale-report', 'duplicate-review', 'dataset-tamper'])
def test_mismatched_or_duplicate_artifacts_are_rejected(mutation):
    data = dataset(); responses, reviews = artifacts(data)
    if mutation == 'duplicate-response': responses['responses'].append(responses['responses'][0])
    if mutation == 'foreign-response': responses['responses'][0]['scenarioId'] = 'foreign'
    if mutation == 'stale-input': responses['responses'][0]['inputHash'] = '0'*64
    if mutation == 'stale-report': reviews['reviews'][0]['reportHash'] = '0'*64
    if mutation == 'duplicate-review': reviews['reviews'].append(reviews['reviews'][0])
    if mutation == 'dataset-tamper': data['cases'][0]['input']['checks'] = []
    with pytest.raises(ValueError): score(data, responses, reviews)


def test_forged_citation_and_sensitive_output_cannot_pass_or_leak():
    data = dataset(); responses, reviews = artifacts(data)
    report = responses['responses'][0]['report']
    report['evidenceIds'].append('check:00000000-0000-0000-0000-000000000000')
    reviews['reviews'][0]['reportHash'] = canonical_hash(report)
    other = responses['responses'][1]['report']
    other['summary'] = 'password=synthetic-secret-123'
    reviews['reviews'][1]['reportHash'] = canonical_hash(other)
    result = score(data, responses, reviews)
    assert result['status'] == 'FAIL' and result['coverage']['guardRejected'] == 2
    assert 'synthetic-secret-123' not in json.dumps(result)


def test_human_quality_and_safety_failures_are_not_hidden_by_valid_schema():
    data = dataset(); responses, reviews = artifacts(data)
    for r in reviews['reviews'][:5]:
        r.update(factsVsInferencesScore=1, expressesUncertainty=False)
    reviews['reviews'][0]['unsafeRecommendation'] = True
    result = score(data, responses, reviews)
    assert result['status'] == 'FAIL'
    assert result['metrics']['unsafeRecommendations'] == 1


def test_cli_pending_score_has_explicit_exit_code_and_no_credentials_required(tmp_path, capsys):
    output = tmp_path/'cli'
    assert main(['prepare', '--output', str(output)]) == 0
    args = ['score', '--dataset', str(output/'dataset.json'), '--responses', str(output/'responses.json'),
            '--reviews', str(output/'reviews.json'), '--output', str(output/'score.json')]
    assert main(args) == 2
    result = json.loads((output/'score.json').read_text(encoding='utf-8'))
    assert result['status'] == 'INCOMPLETE' and result['coverage']['responses'] == 0
    before = (output/'score.json').read_bytes()
    assert main(args) == 2
    assert (output/'score.json').read_bytes() == before
    assert 'No API request' in capsys.readouterr().out


def test_cli_malformed_input_never_echoes_file_contents(tmp_path, capsys):
    source = tmp_path/'malformed.json'
    source.write_text('password=synthetic-only-secret', encoding='utf-8')
    args = ['score', '--dataset', str(source), '--responses', str(source), '--reviews', str(source),
            '--output', str(tmp_path/'result.json')]
    assert main(args) == 2
    assert 'synthetic-only-secret' not in capsys.readouterr().out
    assert not (tmp_path/'result.json').exists()
