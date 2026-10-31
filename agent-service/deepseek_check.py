"""Manual, one-request DeepSeek connectivity check using original synthetic evidence."""
from datetime import datetime, timedelta, timezone
from getpass import getpass
from hashlib import sha256
import json
from pathlib import Path
import re
import sys
from time import monotonic
from uuid import uuid4
from pydantic import ValidationError

from agentops_agent.graph import validate_result
from agentops_agent.models import GRAPH_VERSION, Invocation
from agentops_agent.provider import DeepSeekProvider, ModelOutcomeUnknown, ProviderUnavailable
from agentops_agent.safety import RiskUnderestimated, redact


def validation_details(error):
    if isinstance(error, RiskUnderestimated):
        detail=error.diagnostic()
        return {'validationErrorCode':'AGENT_RISK_UNDERESTIMATED',
                'validationFields':[['nextSteps',detail['stepIndex'],'risk']], 'riskDiagnostics':detail}
    if isinstance(error, ValidationError):
        names = {'summary', 'hypotheses', 'evidenceIds', 'nextSteps', 'uncertainties', 'text', 'confidence', 'risk', 'requiresHumanReview'}
        fields = [[part if type(part) is int or part in names else '<field>' for part in item['loc'][:8]]
                  for item in error.errors(include_input=False, include_context=False, include_url=False)[:10]]
        return {'validationErrorCode': 'MODEL_SCHEMA_INVALID', 'validationFields': fields}
    codes = {'MODEL_CONFIGURATION_MISMATCH', 'MODEL_REQUEST_REJECTED', 'MODEL_REFUSED_OR_TRUNCATED', 'MODEL_SCHEMA_INVALID',
             'RESULT_IDENTITY_MISMATCH', 'INVALID_EVIDENCE', 'INVALID_CHECK_CITATION', 'INVALID_SECTION_CITATION',
             'DUPLICATE_CITATION', 'UNCITED_HYPOTHESIS', 'AGENT_SENSITIVE_OUTPUT', 'AGENT_RISK_UNDERESTIMATED',
             'CHECK_REQUIRES_CITED_EVIDENCE', 'CHECK_REQUEST_LIMIT', 'INVALID_MODEL_CONFIGURATION'}
    code = error.args[0] if error.args and type(error.args[0]) is str else ''
    return {'validationErrorCode': code if code in codes else 'UNCLASSIFIED_VALIDATION_ERROR'}


def synthetic_invocation():
    now = datetime.now(timezone.utc)
    text = ('这是Dawnwatch原创的合成验收手册，不对应任何真实服务。'
            '连续503只能说明服务不可用，不能单凭健康检查确认根因。'
            '人工核对检查时间线及部署记录；任何修改或重启建议必须交人工审阅，不能自动执行。')
    return Invocation.model_validate({
        'runId': str(uuid4()), 'graphVersion': GRAPH_VERSION, 'modelName': 'deepseek-flash',
        'incident': {'id': str(uuid4()), 'targetId': str(uuid4()), 'status': 'INVESTIGATING', 'openedAt': now.isoformat()},
        'checks': [{'runId': str(uuid4()), 'outcome': 'DOWN', 'statusCode': 503, 'latencyMs': 20,
                    'completedAt': (now - timedelta(seconds=60 * i)).isoformat(), 'errorCode': 'HTTP_STATUS_UNEXPECTED'} for i in range(3)],
        'runbooks': [{'runbookId': str(uuid4()), 'version': 1, 'sectionId': str(uuid4()), 'text': text,
                      'hash': sha256(text.encode()).hexdigest()}],
        'constraints': {'tools': ['get_check_history', 'get_published_runbook'], 'maxEvidence': 20},
        'capturedAt': now.isoformat(),
    })


class OneRequestProvider(DeepSeekProvider):
    """Observe safe response metadata; leave the production adapter and guards intact."""
    def __init__(self, key, *, transport=None):
        super().__init__('deepseek-flash', key, transport=transport)
        self.attempts = 0
        self.usage = None
        self.returned_model = None

    async def _request(self, body):
        if self.attempts or len(json.dumps(body, ensure_ascii=False).encode()) > 12000:
            raise ValueError('CHECK_REQUEST_LIMIT')
        self.attempts += 1
        response = await super()._request(body)
        if isinstance(response, dict):
            model = response.get('model')
            if isinstance(model, str) and re.fullmatch(r'[A-Za-z0-9._:/-]{1,80}', model) and redact(model) == model:
                self.returned_model = model
            usage = response.get('usage')
            if isinstance(usage, dict):
                numbers = {k: usage[k] for k in ('prompt_tokens', 'completion_tokens', 'total_tokens', 'prompt_cache_hit_tokens', 'prompt_cache_miss_tokens')
                           if type(usage.get(k)) is int and usage[k] >= 0}
                if all(k in numbers for k in ('prompt_tokens', 'completion_tokens', 'total_tokens')):
                    self.usage = numbers
        return response


def run_check(key, *, transport=None):
    start = monotonic()
    provider = None
    result = {'schemaVersion': 1, 'status': 'FAIL', 'sampleMode': 'synthetic',
              'requestedModel': 'deepseek-flash', 'endpoint': 'https://api.deepseek.com/chat/completions',
              'checkedAt': datetime.now(timezone.utc).isoformat(), 'requestAttempts': 0,
              'usage': None, 'returnedModel': None, 'peakUncachedCostEstimateUsd': None,
              'pricingCheckedAt': '2026-10-24', 'pricingSource': 'https://api-docs.deepseek.com/quick_start/pricing/',
              'scope': 'One model request and local schema/citation/safety validation; no database, Java workflow, quality benchmark or automatic repair.'}
    try:
        provider = OneRequestProvider(key.strip(), transport=transport)
        invocation = synthetic_invocation()
        report = validate_result(provider.analyze(invocation), invocation)
        if not report['citations']:
            raise ValueError('CHECK_REQUIRES_CITED_EVIDENCE')
        result.update(status='PASS', report=report)
    except ModelOutcomeUnknown:
        result['error'] = 'OUTCOME_UNKNOWN'
    except ProviderUnavailable as error:
        result['error'] = 'PROVIDER_UNAVAILABLE'
        result['providerFailureKind'] = error.failure_kind if error.failure_kind in {'CONNECT_ERROR', 'CONNECT_TIMEOUT', 'HTTP_STATUS'} else None
        result['providerHttpStatus'] = error.http_status if type(error.http_status) is int and 100 <= error.http_status <= 599 else None
    except ValueError as error:
        result['error'] = ('MODEL_REQUEST_REJECTED' if error.args and error.args[0] == 'MODEL_REQUEST_REJECTED'
                           else 'OUTPUT_REJECTED' if provider and provider.attempts else 'CONFIGURATION_REJECTED')
        result.update(validation_details(error))
    except KeyboardInterrupt:
        result['error'] = 'INTERRUPTED_OUTCOME_UNKNOWN' if provider and provider.attempts else 'CANCELLED'
    except Exception:
        # Never serialize exception text: it may include request or response data.
        result['error'] = 'CHECK_FAILED'
    finally:
        if provider:
            result.update(requestAttempts=provider.attempts, usage=provider.usage, returnedModel=provider.returned_model)
            if provider.usage:
                # Conservative peak, uncached USD estimate; not the account's actual bill.
                result['peakUncachedCostEstimateUsd'] = round((provider.usage['prompt_tokens'] * 0.3 + provider.usage['completion_tokens'] * 1.2) / 1_000_000, 8)
            provider.key = ''
        result['elapsedMs'] = round((monotonic() - start) * 1000)
    return result


def interactive_input():
    if not sys.stdin.isatty():
        return False
    if sys.platform != 'win32':
        return True
    # Windows CRT considers NUL a tty, while getpass would read the real console.
    # Require the actual console input handle before accepting any credential.
    import ctypes
    import msvcrt
    from ctypes import wintypes
    try:
        get_mode = ctypes.windll.kernel32.GetConsoleMode
        get_mode.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        get_mode.restype = wintypes.BOOL
        mode = wintypes.DWORD()
        return bool(get_mode(msvcrt.get_osfhandle(sys.stdin.fileno()), ctypes.byref(mode)))
    except (OSError, ValueError):
        return False


def main():
    if not interactive_input():
        print('Run this command directly in your local IDEA/PowerShell terminal; do not pipe the API key.', file=sys.stderr)
        return 2
    print('DeepSeek official API: 1 request, synthetic evidence, no automatic retry. Output limit: 4096 tokens.')
    print('The API key stays in this process and is not written to the report. Do not paste it into the chat.')
    try:
        key = getpass('DeepSeek API Key (hidden): ')
    except (EOFError, KeyboardInterrupt):
        print('Cancelled before request.')
        return 2
    report = run_check(key)
    key = None
    root = Path(__file__).resolve().parents[1]
    directory = root / '.cache/deepseek-live-check' / (datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S') + '-' + uuid4().hex[:8])
    directory.mkdir(parents=True)
    output = directory / 'result.json'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: report.get(k) for k in ('status', 'error', 'requestAttempts', 'elapsedMs', 'usage', 'peakUncachedCostEstimateUsd')}, ensure_ascii=False))
    print('RESULT_FILE=' + str(output))
    print('No retry was attempted. A failure can still have incurred a provider charge.')
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
