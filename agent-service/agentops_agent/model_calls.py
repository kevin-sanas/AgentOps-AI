"""Durable model-call journal. The caller must own this run's advisory lock."""
import json
from time import monotonic
from .graph import validate_result
from .provider import ProviderUnavailable, ModelOutcomeUnknown


class PersistedProvider:
    def __init__(self, conn, delegate, request_id=None):
        self.conn, self.delegate, self.run_id = conn, delegate, None
        self.request_id = request_id
        self.safe_metadata = None

    def analyze(self, invocation):
        self.run_id = invocation.runId
        previous = self.conn.execute("SELECT status,result FROM model_call WHERE run_id=%s", (self.run_id,)).fetchone()
        if previous and previous["status"] == "COMPLETED":
            return validate_result(previous["result"], invocation)
        if previous and previous["status"] == "STARTED":
            # A previous process may have reached the provider. Never automatically repeat that call.
            raise ModelOutcomeUnknown("MODEL_OUTCOME_UNKNOWN")
        if self.delegate is None or self.delegate.model != invocation.modelName:
            raise ValueError("MODEL_CONFIGURATION_MISMATCH")
        self.conn.execute("""INSERT INTO model_call(run_id,model_name,status,request_id,outcome)
            VALUES(%s,%s,'STARTED',%s,'STARTED')
            ON CONFLICT(run_id) DO UPDATE SET status='STARTED',request_id=EXCLUDED.request_id,
              outcome='STARTED',error_code=NULL,http_status=NULL,elapsed_ms=NULL,returned_model=NULL,
              prompt_tokens=NULL,completion_tokens=NULL,total_tokens=NULL,cache_hit_tokens=NULL,cache_miss_tokens=NULL,updated_at=now()
            WHERE model_call.status='RETRYABLE'""", (self.run_id, invocation.modelName,
            self.request_id or str(self.run_id)))
        started = monotonic()
        try:
            result = validate_result(self.delegate.analyze(invocation), invocation)
        except ModelOutcomeUnknown:
            self._store_failure("STARTED", "UNKNOWN", "MODEL_OUTCOME_UNKNOWN", None, started)
            raise
        except (ProviderUnavailable, ValueError) as error:
            # A definite HTTP rejection, connection failure or invalid response is a known outcome.
            # Java decides whether to retry; malformed results are not automatically retried.
            self._store_failure("RETRYABLE", "FAILED", self._error_code(error),
                                getattr(error, "http_status", None), started)
            raise
        self.safe_metadata = getattr(self.delegate, "last_request", None) or {
            "outcome": "SUCCEEDED", "errorCode": None, "httpStatus": 200,
            "model": invocation.modelName, "usage": None,
        }
        self.safe_metadata["elapsedMs"] = max(0, round((monotonic() - started) * 1000))
        # Commit before returning to LangGraph. A lost graph checkpoint can reuse this exact result.
        self._store_result(result)
        return result

    def _store_result(self, result):
        metadata = self.safe_metadata
        usage = metadata.get("usage")
        self.conn.execute("""UPDATE model_call SET status='COMPLETED',result=%s::jsonb,outcome='SUCCEEDED',
            error_code=NULL,http_status=%s,elapsed_ms=%s,returned_model=%s,
            prompt_tokens=%s,completion_tokens=%s,total_tokens=%s,cache_hit_tokens=%s,cache_miss_tokens=%s,updated_at=now()
            WHERE run_id=%s""", (json.dumps(result, ensure_ascii=False), metadata.get("httpStatus"),
            metadata.get("elapsedMs"), metadata.get("model"),
            None if usage is None else usage.get("promptTokens"), None if usage is None else usage.get("completionTokens"),
            None if usage is None else usage.get("totalTokens"), None if usage is None else usage.get("cacheHitTokens"),
            None if usage is None else usage.get("cacheMissTokens"), self.run_id))

    def _store_failure(self, status, outcome, code, http_status, started):
        metadata = getattr(self.delegate, "last_request", None) or {}
        usage = metadata.get("usage")
        self.conn.execute("""UPDATE model_call SET status=%s,outcome=%s,error_code=%s,http_status=%s,
            elapsed_ms=%s,returned_model=%s,prompt_tokens=%s,completion_tokens=%s,total_tokens=%s,
            cache_hit_tokens=%s,cache_miss_tokens=%s,updated_at=now() WHERE run_id=%s""",
            (status, outcome, code, http_status if http_status is not None else metadata.get("httpStatus"),
             max(0, round((monotonic() - started) * 1000)), metadata.get("model"),
             None if usage is None else usage.get("promptTokens"), None if usage is None else usage.get("completionTokens"),
             None if usage is None else usage.get("totalTokens"), None if usage is None else usage.get("cacheHitTokens"),
             None if usage is None else usage.get("cacheMissTokens"), self.run_id))

    @staticmethod
    def _error_code(error):
        if isinstance(error, ProviderUnavailable):
            if error.failure_kind == "CONNECT_TIMEOUT": return "MODEL_TIMEOUT"
            if error.failure_kind == "CONNECT_ERROR": return "MODEL_CONNECT_FAILED"
            if error.http_status == 429: return "MODEL_RATE_LIMITED"
            if error.http_status is not None and error.http_status >= 500: return "MODEL_PROVIDER_UNAVAILABLE"
            return "MODEL_PROVIDER_UNAVAILABLE"
        code = error.args[0] if error.args and isinstance(error.args[0], str) else "MODEL_RESPONSE_INVALID"
        allowed = {"MODEL_REQUEST_REJECTED", "MODEL_RESPONSE_TOO_LARGE", "MODEL_REFUSED_OR_TRUNCATED",
                   "MODEL_SCHEMA_INVALID", "MODEL_CONFIGURATION_MISMATCH"}
        return code if code in allowed else "MODEL_RESPONSE_INVALID"
