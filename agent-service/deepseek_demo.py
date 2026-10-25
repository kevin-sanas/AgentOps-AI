"""Explicit, disposable live-model factory. The normal application factory stays unchanged."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import socket
import ssl
from threading import Lock
from time import monotonic

from deepseek_check import OneRequestProvider, validation_details
from agentops_agent.app import create_app
from agentops_agent.graph import validate_result
from agentops_agent.provider import ModelOutcomeUnknown, ProviderUnavailable
from agentops_agent.store import Store


def connection_details(error):
    seen = set()
    current = error
    for _ in range(8):
        if current is None or id(current) in seen:
            break
        seen.add(id(current))
        categories = ((ssl.SSLCertVerificationError, 'TLS_VERIFY_FAILED'), (socket.gaierror, 'DNS_FAILED'),
                      (ConnectionResetError, 'CONNECTION_RESET'), (ConnectionRefusedError, 'CONNECTION_REFUSED'),
                      (PermissionError, 'NETWORK_PERMISSION_DENIED'))
        for kind, category in categories:
            if isinstance(current, kind):
                result = {'category': category}
                for name in ('errno', 'winerror', 'verify_code'):
                    value = getattr(current, name, None)
                    if type(value) is int:
                        result[name] = value
                return result
        current = current.__cause__ or current.__context__
    return {'category': 'UNCLASSIFIED_CONNECTION_FAILURE'}


class SessionProvider(OneRequestProvider):
    def __init__(self, key, directory, *, transport=None, claim=None):
        root = Path(__file__).resolve().parents[1]
        base = (root / '.cache/demo').resolve()
        directory = Path(directory).resolve()
        if not directory.is_relative_to(base) or directory == base or not directory.is_dir():
            raise ValueError('INVALID_LIVE_SESSION_DIRECTORY')
        super().__init__(key, transport=transport)
        self.directory = directory
        self._claim_lock = Lock()
        self._claimed = False
        self._shared_claim = claim

    def analyze(self, invocation):
        # Shared across all HTTP worker threads and run IDs in this session.
        if self._shared_claim is not None:
            if not self._shared_claim(): raise ValueError('LIVE_MODEL_LIMIT_REACHED')
        else:
            with self._claim_lock:
                if self._claimed: raise ValueError('LIVE_MODEL_LIMIT_REACHED')
                self._claimed = True
        start = monotonic()
        receipt = {'schemaVersion': 1, 'mode': 'synthetic/live-deepseek', 'modelName': self.model,
                   'runId': str(invocation.runId), 'checkedAt': datetime.now(timezone.utc).isoformat(),
                   'pythonGuardPassed': False, 'requestLimit': 1,
                   'scope': 'Model request and Python guard only; Java/checkpoint/human workflow must be verified separately.'}
        try:
            result = validate_result(super().analyze(invocation), invocation)
            if not result['citations']:
                raise ValueError('CHECK_REQUIRES_CITED_EVIDENCE')
            receipt['pythonGuardPassed'] = True
            return result
        except ModelOutcomeUnknown:
            receipt['error'] = 'OUTCOME_UNKNOWN'
            raise
        except ProviderUnavailable as error:
            receipt['error'] = 'PROVIDER_UNAVAILABLE'
            receipt['providerFailureKind'] = error.failure_kind if error.failure_kind in {'CONNECT_ERROR', 'CONNECT_TIMEOUT', 'HTTP_STATUS', 'TLS_VERIFY'} else None
            receipt['providerHttpStatus'] = error.http_status if type(error.http_status) is int and 100 <= error.http_status <= 599 else None
            if error.failure_kind == 'TLS_VERIFY':
                receipt['connectionDetails'] = {'category': 'TLS_VERIFY_FAILED'}
            elif error.failure_kind in {'CONNECT_ERROR', 'CONNECT_TIMEOUT'}:
                receipt['connectionDetails'] = connection_details(error)
            raise
        except ValueError as error:
            receipt['error'] = 'OUTPUT_OR_REQUEST_REJECTED'
            receipt.update(validation_details(error))
            raise
        finally:
            self.key = ''
            receipt.update(requestAttempts=self.attempts, usage=self.usage, returnedModel=self.returned_model,
                           elapsedMs=round((monotonic() - start) * 1000))
            if self.usage:
                receipt['peakUncachedCostEstimateUsd'] = round((self.usage['prompt_tokens'] * 0.3 + self.usage['completion_tokens'] * 1.2) / 1_000_000, 8)
            else:
                receipt['peakUncachedCostEstimateUsd'] = None
            temporary = self.directory / 'live-model-call.json.tmp'
            temporary.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            temporary.replace(self.directory / 'live-model-call.json')


class SessionProviderFactory:
    """Creates providers from page-supplied credentials while sharing one paid-call claim."""
    def __init__(self, directory, *, transport=None):
        self.directory = directory
        self.transport = transport
        self._lock = Lock()
        self._claimed = False

    def _claim(self):
        with self._lock:
            if self._claimed: return False
            self._claimed = True
            return True

    def __call__(self, key):
        return SessionProvider(key, self.directory, transport=self.transport, claim=self._claim)


def from_environment():
    for name in ('LANGSMITH_TRACING', 'LANGCHAIN_TRACING_V2', 'LANGCHAIN_TRACING'):
        os.environ[name] = 'false'
    factory = SessionProviderFactory(os.environ['DAWNWATCH_LIVE_DIRECTORY'])
    return create_app(Store(os.environ['AGENT_CHECKPOINT_DSN'], provider=None), os.environ['AGENT_INTERNAL_TOKEN'],
                      managed_provider_factory=factory)
