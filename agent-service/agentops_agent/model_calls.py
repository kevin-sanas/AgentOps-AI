"""Durable model-call journal. The caller must own this run's advisory lock."""
import json
from .graph import validate_result
from .provider import ProviderUnavailable, ModelOutcomeUnknown


class PersistedProvider:
    def __init__(self, conn, delegate):
        self.conn, self.delegate, self.run_id = conn, delegate, None

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
        self.conn.execute("""INSERT INTO model_call(run_id,model_name,status) VALUES(%s,%s,'STARTED')
            ON CONFLICT(run_id) DO UPDATE SET status='STARTED',updated_at=now()
            WHERE model_call.status='RETRYABLE'""", (self.run_id, invocation.modelName))
        try:
            result = validate_result(self.delegate.analyze(invocation), invocation)
        except ModelOutcomeUnknown:
            raise
        except (ProviderUnavailable, ValueError):
            # A definite HTTP rejection, connection failure or invalid response is a known outcome.
            # Java decides whether to retry; malformed results are not automatically retried.
            self.conn.execute("UPDATE model_call SET status='RETRYABLE',updated_at=now() WHERE run_id=%s", (self.run_id,))
            raise
        # Commit before returning to LangGraph. A lost graph checkpoint can reuse this exact result.
        self._store_result(result)
        return result

    def _store_result(self, result):
        self.conn.execute("UPDATE model_call SET status='COMPLETED',result=%s::jsonb,updated_at=now() WHERE run_id=%s", (json.dumps(result, ensure_ascii=False), self.run_id))
