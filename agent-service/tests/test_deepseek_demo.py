from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import shutil
from threading import Event
from uuid import uuid4
import httpx
import pytest

from deepseek_check import synthetic_invocation
from deepseek_demo import SessionProvider, SessionProviderFactory
from test_deepseek_check import response_for
from agentops_agent.provider import ProviderUnavailable


@pytest.fixture
def session_directory():
    root = Path(__file__).resolve().parents[2]
    directory = root / '.cache/demo' / ('unit-live-' + uuid4().hex)
    directory.mkdir(parents=True)
    yield directory
    shutil.rmtree(directory)


def test_success_is_validated_and_one_session_cannot_make_another_paid_request(session_directory):
    calls = []
    def handle(request):
        calls.append(request)
        return httpx.Response(200, json=response_for(request))
    provider = SessionProvider('fixture-key', session_directory, transport=httpx.MockTransport(handle))
    report = provider.analyze(synthetic_invocation())
    assert report['modelName'] == 'deepseek-flash'
    with pytest.raises(ValueError, match='LIVE_MODEL_LIMIT_REACHED'):
        provider.analyze(synthetic_invocation())
    receipt = json.loads((session_directory / 'live-model-call.json').read_text())
    assert len(calls) == receipt['requestAttempts'] == 1
    assert receipt['pythonGuardPassed'] is True
    assert receipt['usage']['total_tokens'] == 1100
    assert 'fixture-key' not in json.dumps(receipt)


def test_concurrent_diagnoses_share_the_same_one_request_limit(session_directory):
    entered, release = Event(), Event()
    calls = []
    def handle(request):
        calls.append(request)
        entered.set()
        assert release.wait(5)
        return httpx.Response(200, json=response_for(request))
    provider = SessionProvider('fixture-key', session_directory, transport=httpx.MockTransport(handle))
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(provider.analyze, synthetic_invocation())
        assert entered.wait(5)
        second = pool.submit(provider.analyze, synthetic_invocation())
        try:
            with pytest.raises(ValueError, match='LIVE_MODEL_LIMIT_REACHED'):
                second.result(timeout=5)
        finally:
            release.set()
        assert first.result(timeout=5)['modelName'] == 'deepseek-flash'
    assert len(calls) == 1


def test_page_managed_provider_instances_share_one_session_limit(session_directory):
    calls = []
    def handle(request):
        calls.append(request)
        return httpx.Response(200, json=response_for(request))
    factory = SessionProviderFactory(session_directory, transport=httpx.MockTransport(handle))
    assert factory('first-page-key').analyze(synthetic_invocation())['modelName'] == 'deepseek-flash'
    with pytest.raises(ValueError, match='LIVE_MODEL_LIMIT_REACHED'):
        factory('second-page-key').analyze(synthetic_invocation())
    assert len(calls) == 1


def test_connection_failure_is_recorded_and_a_later_delivery_cannot_retry_model(session_directory):
    calls = []
    def handle(request):
        calls.append(request)
        raise httpx.ConnectError('sk-fixture-sensitive-1234567890')
    provider = SessionProvider('fixture-key', session_directory, transport=httpx.MockTransport(handle))
    with pytest.raises(ProviderUnavailable):
        provider.analyze(synthetic_invocation())
    with pytest.raises(ValueError, match='LIVE_MODEL_LIMIT_REACHED'):
        provider.analyze(synthetic_invocation())
    receipt = json.loads((session_directory / 'live-model-call.json').read_text())
    assert receipt['error'] == 'PROVIDER_UNAVAILABLE'
    assert receipt['providerFailureKind'] == 'CONNECT_ERROR'
    assert receipt['pythonGuardPassed'] is False
    assert receipt['usage'] is None
    assert receipt['requestAttempts'] == len(calls) == 1
    assert 'sk-fixture-sensitive' not in json.dumps(receipt)


def test_received_response_is_not_claimed_as_accepted_when_citations_are_invalid(session_directory):
    def handle(request):
        result = response_for(request)
        content = json.loads(result['choices'][0]['message']['content'])
        content['evidenceIds'] = ['check:00000000-0000-0000-0000-000000000000']
        result['choices'][0]['message']['content'] = json.dumps(content)
        return httpx.Response(200, json=result)
    provider = SessionProvider('fixture-key', session_directory, transport=httpx.MockTransport(handle))
    with pytest.raises(ValueError, match='INVALID_EVIDENCE'):
        provider.analyze(synthetic_invocation())
    receipt = json.loads((session_directory / 'live-model-call.json').read_text())
    assert receipt['pythonGuardPassed'] is False
    assert receipt['validationErrorCode'] == 'INVALID_EVIDENCE'
    assert receipt['usage']['total_tokens'] == 1100


def test_receipt_cannot_be_written_outside_project_owned_demo_directory():
    with pytest.raises(ValueError, match='INVALID_LIVE_SESSION_DIRECTORY'):
        SessionProvider('fixture-key', Path(__file__).resolve().parents[1])


def test_certificate_failure_keeps_only_safe_network_category(session_directory):
    import ssl
    def handle(request):
        try:
            raise ssl.SSLCertVerificationError(1, 'sk-fixture-sensitive-1234567890')
        except ssl.SSLCertVerificationError as error:
            raise httpx.ConnectError('sk-fixture-sensitive-1234567890') from error
    provider = SessionProvider('fixture-key', session_directory, transport=httpx.MockTransport(handle))
    with pytest.raises(ProviderUnavailable):
        provider.analyze(synthetic_invocation())
    receipt = json.loads((session_directory / 'live-model-call.json').read_text())
    assert receipt['connectionDetails']['category'] == 'TLS_VERIFY_FAILED'
    assert 'sk-fixture-sensitive' not in json.dumps(receipt)
