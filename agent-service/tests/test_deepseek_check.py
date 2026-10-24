import json
import httpx
import pytest

from deepseek_check import run_check, synthetic_invocation


def response_for(request):
    body = json.loads(request.content)
    evidence = json.loads(body['messages'][1]['content'])
    content = {
        'summary': '合成检查连续返回503，尚不能确认根因。',
        'hypotheses': [],
        'evidenceIds': ['check:' + evidence['checks'][0]['runId']],
        'nextSteps': [{'text': '人工核对检查记录。', 'risk': 'LOW', 'requiresHumanReview': True}],
        'uncertainties': ['未提供真实服务日志。'],
    }
    return {'model': 'deepseek-flash', 'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps(content)}}],
            'usage': {'prompt_tokens': 1000, 'completion_tokens': 100, 'total_tokens': 1100}}


def test_one_official_request_uses_existing_guards_and_records_usage_without_key():
    seen = []
    def handle(request):
        seen.append(request)
        body = json.loads(request.content)
        assert str(request.url) == 'https://api.deepseek.com/chat/completions'
        assert request.headers['Authorization'] == 'Bearer fixture-key'
        assert body['thinking'] == {'type': 'disabled'}
        assert body['response_format'] == {'type': 'json_object'}
        assert body['max_tokens'] == 4096
        assert 'tools' not in body
        return httpx.Response(200, json=response_for(request))
    report = run_check('fixture-key', transport=httpx.MockTransport(handle))
    assert report['status'] == 'PASS'
    assert report['requestAttempts'] == len(seen) == 1
    assert report['usage']['total_tokens'] == 1100
    assert report['peakUncachedCostEstimateUsd'] == pytest.approx(0.00042)
    assert report['report']['citations'][0]['kind'] == 'CHECK_RUN'
    assert 'fixture-key' not in json.dumps(report)


@pytest.mark.parametrize('case,expected', [('fake-citation', 'OUTPUT_REJECTED'), ('401', 'MODEL_REQUEST_REJECTED'), ('503', 'PROVIDER_UNAVAILABLE'), ('read-timeout', 'OUTCOME_UNKNOWN')])
def test_failure_never_retries_or_falls_back_to_mock(case, expected):
    calls = []
    def handle(request):
        calls.append(request)
        if case in ('401', '503'):
            return httpx.Response(int(case))
        if case == 'read-timeout':
            raise httpx.ReadTimeout('fixture-timeout')
        envelope = response_for(request)
        answer = json.loads(envelope['choices'][0]['message']['content'])
        answer['evidenceIds'] = ['check:00000000-0000-0000-0000-000000000000']
        envelope['choices'][0]['message']['content'] = json.dumps(answer)
        return httpx.Response(200, json=envelope)
    report = run_check('fixture-key', transport=httpx.MockTransport(handle))
    assert report['status'] == 'FAIL'
    assert report['error'] == expected
    assert report['requestAttempts'] == len(calls) == 1
    assert 'report' not in report
    assert 'fixture-key' not in json.dumps(report)


def test_missing_usage_is_unknown_instead_of_invented_zero_cost():
    def handle(request):
        envelope = response_for(request)
        del envelope['usage']
        return httpx.Response(200, json=envelope)
    report = run_check('fixture-key', transport=httpx.MockTransport(handle))
    assert report['status'] == 'PASS'
    assert report['usage'] is None
    assert report['peakUncachedCostEstimateUsd'] is None


def test_synthetic_payload_is_valid_and_does_not_need_database_configuration():
    body = synthetic_invocation()
    assert body.modelName == 'deepseek-flash'
    assert len(body.checks) == 3
    assert all(check.statusCode == 503 for check in body.checks)
    assert body.runbooks[0].text.startswith('这是Dawnwatch原创的合成验收手册')


def test_empty_key_cannot_send_request():
    calls = []
    report = run_check('', transport=httpx.MockTransport(lambda request: calls.append(request)))
    assert report['status'] == 'FAIL'
    assert report['requestAttempts'] == len(calls) == 0


def test_response_cannot_persist_a_secret_as_model_metadata():
    def handle(request):
        envelope = response_for(request)
        envelope['model'] = 'sk-fixture-secret-1234567890'
        return httpx.Response(200, json=envelope)
    report = run_check('fixture-key', transport=httpx.MockTransport(handle))
    assert report['status'] == 'PASS'
    assert report['returnedModel'] is None
    assert 'sk-fixture-secret' not in json.dumps(report)


def test_noninteractive_command_refuses_key_input_without_sending_request():
    from pathlib import Path
    import subprocess
    import sys
    result = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / 'deepseek_check.py')],
                            stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=15)
    assert result.returncode == 2
    assert 'do not pipe the API key' in result.stderr
    assert 'RESULT_FILE=' not in result.stdout


def test_model_receives_authoritative_prefixed_citation_ids():
    def handle(request):
        body = json.loads(request.content)
        evidence = json.loads(body['messages'][1]['content'])
        expected = ['check:' + item['runId'] for item in evidence['checks']]
        expected += ['runbook:' + item['sectionId'] for item in evidence['runbooks']]
        assert evidence['allowedEvidenceIds'] == expected
        assert len(expected) == 4
        return httpx.Response(200, json=response_for(request))
    assert run_check('fixture-key', transport=httpx.MockTransport(handle))['status'] == 'PASS'


def test_schema_failure_records_only_field_locations_never_invalid_content():
    def handle(request):
        envelope = response_for(request)
        content = json.loads(envelope['choices'][0]['message']['content'])
        content['hypotheses'] = [{'text': 'sk-fixture-private-1234567890', 'confidence': 'not-a-number', 'evidenceIds': content['evidenceIds']}]
        envelope['choices'][0]['message']['content'] = json.dumps(content)
        return httpx.Response(200, json=envelope)
    report = run_check('fixture-key', transport=httpx.MockTransport(handle))
    assert report['status'] == 'FAIL'
    assert report['validationErrorCode'] == 'MODEL_SCHEMA_INVALID'
    assert report['validationFields'] == [['hypotheses', 0, 'confidence']]
    assert 'sk-fixture-private' not in json.dumps(report)
    assert 'not-a-number' not in json.dumps(report)


@pytest.mark.parametrize('failure,kind,status', [('connect', 'CONNECT_ERROR', None), ('429', 'HTTP_STATUS', 429), ('503', 'HTTP_STATUS', 503)])
def test_provider_unavailability_has_safe_transport_or_status_diagnostics(failure, kind, status):
    calls = []
    def handle(request):
        calls.append(request)
        if failure == 'connect':
            raise httpx.ConnectError('sk-fixture-private-1234567890')
        return httpx.Response(int(failure), text='sk-fixture-private-1234567890')
    report = run_check('fixture-key', transport=httpx.MockTransport(handle))
    assert report['error'] == 'PROVIDER_UNAVAILABLE'
    assert report['providerFailureKind'] == kind
    assert report['providerHttpStatus'] == status
    assert report['requestAttempts'] == len(calls) == 1
    assert 'sk-fixture-private' not in json.dumps(report)
