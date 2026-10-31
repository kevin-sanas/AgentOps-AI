from contextlib import contextmanager
import hashlib
import json
import time
from uuid import UUID
import psycopg
from psycopg.rows import dict_row
from langgraph.checkpoint.postgres import PostgresSaver
from langsmith import tracing_context
from langgraph.types import Command
from .graph import build_graph
from .models import Invocation, Resume


class InputConflict(ValueError):
    pass


class Store:
    def __init__(self, dsn: str, provider=None):
        self.dsn = dsn
        self.provider = provider

    def connect(self):
        return psycopg.connect(self.dsn, autocommit=True, prepare_threshold=0, row_factory=dict_row,
                               connect_timeout=5, options="-c search_path=agent_checkpoint -c statement_timeout=10000 -c lock_timeout=5000")

    def setup(self):
        with self.connect() as conn:
            role = conn.execute("SELECT rolsuper,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname=current_user").fetchone()
            if any(role.values()): raise ValueError("CHECKPOINT_ROLE_MUST_BE_RESTRICTED")
            business = conn.execute("SELECT to_regclass('public.app_user') IS NOT NULL OR to_regclass('public.service_target') IS NOT NULL present").fetchone()
            if business["present"]: raise ValueError("CHECKPOINT_DATABASE_MUST_BE_SEPARATE")
            conn.execute("CREATE SCHEMA IF NOT EXISTS agent_checkpoint AUTHORIZATION CURRENT_USER")
            conn.execute("SELECT pg_advisory_lock(722330120)")
            try:
                self.saver(conn).setup()
                conn.execute("""CREATE TABLE IF NOT EXISTS invocation (
                    run_id uuid PRIMARY KEY, input_hash char(64) NOT NULL,
                    updated_at timestamptz NOT NULL DEFAULT now())""")
                conn.execute("ALTER TABLE invocation ADD COLUMN IF NOT EXISTS graph_version varchar(80) NOT NULL DEFAULT 'readonly-mock-v1'")
                conn.execute("ALTER TABLE invocation ADD COLUMN IF NOT EXISTS decision jsonb")
                conn.execute("ALTER TABLE invocation ADD COLUMN IF NOT EXISTS cancelled boolean NOT NULL DEFAULT false")
                conn.execute("""CREATE TABLE IF NOT EXISTS model_call (
                    run_id uuid PRIMARY KEY REFERENCES invocation(run_id), model_name varchar(80) NOT NULL,
                    status varchar(16) NOT NULL CHECK(status IN ('STARTED','COMPLETED','RETRYABLE')),
                    result jsonb, request_id varchar(80), outcome varchar(16), error_code varchar(80),
                    http_status integer, elapsed_ms integer, returned_model varchar(80),
                    prompt_tokens integer, completion_tokens integer, total_tokens integer,
                    cache_hit_tokens integer, cache_miss_tokens integer,
                    updated_at timestamptz NOT NULL DEFAULT now(),
                    CHECK((status='COMPLETED')=(result IS NOT NULL)))""")
                for definition in (
                    "request_id varchar(80)", "outcome varchar(16)", "error_code varchar(80)",
                    "http_status integer", "elapsed_ms integer", "returned_model varchar(80)",
                    "prompt_tokens integer", "completion_tokens integer", "total_tokens integer",
                    "cache_hit_tokens integer", "cache_miss_tokens integer",
                ):
                    conn.execute("ALTER TABLE model_call ADD COLUMN IF NOT EXISTS " + definition)
            finally:
                conn.execute("SELECT pg_advisory_unlock(722330120)")

    @staticmethod
    def saver(conn):
        return PostgresSaver(conn)

    @contextmanager
    def lock(self, run_id: UUID):
        key = int.from_bytes(hashlib.sha256(b"agentops:run:" + run_id.bytes).digest()[:8], "big", signed=True)
        with self.connect() as conn:
            acquired = conn.execute("SELECT pg_try_advisory_lock(%s) acquired", (key,)).fetchone()["acquired"]
            try:
                yield conn if acquired else None
            finally:
                if acquired: conn.execute("SELECT pg_advisory_unlock(%s)", (key,))

    def register(self, conn, body):
        normalized = Invocation.model_validate(body).model_dump(mode="json")
        # G3 hashes predate modelName. Preserve their canonical representation on retries.
        hashed = {k: v for k, v in normalized.items() if not (k == "modelName" and normalized["graphVersion"] == "readonly-mock-v1")}
        digest = hashlib.sha256(json.dumps(hashed, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
        conn.execute("INSERT INTO invocation(run_id,input_hash,graph_version) VALUES(%s,%s,%s) ON CONFLICT DO NOTHING", (normalized["runId"], digest, normalized["graphVersion"]))
        actual = conn.execute("SELECT input_hash FROM invocation WHERE run_id=%s", (normalized["runId"],)).fetchone()["input_hash"]
        if actual != digest: raise InputConflict("INPUT_CONFLICT")
        return normalized

    def invoke(self, body, provider=None, request_id=None):
        invocation = Invocation.model_validate(body)
        # Claim the immutable input before competing for graph execution, including the first-registration race.
        with self.connect() as registration:
            normalized = self.register(registration, body)
        with self.lock(invocation.runId) as conn, tracing_context(enabled=False):
            if conn is None: return {"runId": str(invocation.runId), "status": "RUNNING", "graphVersion": invocation.graphVersion}
            metadata = self.metadata(conn, invocation.runId)
            graph = self.graph(conn, provider=provider, request_id=request_id)
            config = {"configurable": {"thread_id": str(invocation.runId)}, "recursion_limit": 10, "callbacks": []}
            state = graph.get_state(config)
            if metadata["cancelled"] or state.interrupts or (state.values.get("result") and not state.next):
                return self._response(invocation.runId, state, metadata, conn)
            # No untracked background task: this request owns the lock through synchronous checkpoint commits.
            started = time.monotonic()
            for _ in graph.stream(None if state.values else {"input": normalized}, config, durability="sync"):
                if time.monotonic() - started > 45: raise TimeoutError("AGENT_GRAPH_TIMEOUT")
            conn.execute("UPDATE invocation SET updated_at=now() WHERE run_id=%s", (invocation.runId,))
            return self._response(invocation.runId, graph.get_state(config), metadata, conn)

    def read(self, run_id: UUID):
        with self.connect() as conn, tracing_context(enabled=False):
            metadata = self.metadata(conn, run_id)
            if metadata is None: return None
            graph = self.graph(conn)
            return self._response(run_id, graph.get_state(self.config(run_id)), metadata, conn)

    def graph(self, conn, provider=None, request_id=None):
        from .model_calls import PersistedProvider
        selected = provider if provider is not None else self.provider
        graph = build_graph(self.saver(conn), provider=PersistedProvider(conn, selected, request_id=request_id))
        graph.step_timeout = 25
        return graph

    @staticmethod
    def metadata(conn, run_id):
        return conn.execute("SELECT graph_version,decision,cancelled FROM invocation WHERE run_id=%s", (run_id,)).fetchone()

    @staticmethod
    def config(run_id):
        return {"configurable": {"thread_id": str(run_id)}, "recursion_limit": 10, "callbacks": []}

    def resume(self, run_id: UUID, command: Resume):
        normalized = command.model_dump(mode="json")
        with self.lock(run_id) as conn, tracing_context(enabled=False):
            if conn is None:
                current = self.read(run_id)
                if current is None: raise KeyError("RESOURCE_NOT_FOUND")
                return {"runId": str(run_id), "status": "RUNNING", "graphVersion": current["graphVersion"]}
            metadata = self.metadata(conn, run_id)
            if metadata is None: raise KeyError("RESOURCE_NOT_FOUND")
            if metadata["cancelled"] or (metadata["decision"] is not None and metadata["decision"] != normalized):
                raise InputConflict("DECISION_CONFLICT")
            graph = self.graph(conn); config = self.config(run_id); state = graph.get_state(config)
            if metadata["decision"] is None:
                if not state.interrupts: raise InputConflict("NOT_WAITING_FOR_REVIEW")
                conn.execute("UPDATE invocation SET decision=%s::jsonb,updated_at=now() WHERE run_id=%s", (json.dumps(normalized), run_id))
                metadata["decision"] = normalized
            # A crash after the decision commit or final checkpoint is safe to retry.
            if state.interrupts:
                graph.invoke(Command(resume={"decision": command.decision}), config, durability="sync")
            elif state.next:
                graph.invoke(None, config, durability="sync")
            return self._response(run_id, graph.get_state(config), metadata, conn)

    def cancel(self, run_id: UUID):
        with self.lock(run_id) as conn, tracing_context(enabled=False):
            if conn is None:
                current = self.read(run_id)
                if current is None: raise KeyError("RESOURCE_NOT_FOUND")
                return {"runId": str(run_id), "status": "RUNNING", "graphVersion": current["graphVersion"]}
            metadata = self.metadata(conn, run_id)
            if metadata is None: raise KeyError("RESOURCE_NOT_FOUND")
            if metadata["decision"] is not None: raise InputConflict("DECISION_ALREADY_RECORDED")
            graph = self.graph(conn); state = graph.get_state(self.config(run_id))
            if not metadata["cancelled"] and state.values.get("result") and not state.next:
                raise InputConflict("RUN_ALREADY_COMPLETED")
            conn.execute("UPDATE invocation SET cancelled=true,updated_at=now() WHERE run_id=%s", (run_id,))
            metadata["cancelled"] = True
            return self._response(run_id, state, metadata, conn)

    def model_request(self, run_id, conn=None):
        if conn is None:
            with self.connect() as opened: return self.model_request(run_id, opened)
        row = conn.execute("""SELECT request_id,outcome,error_code,http_status,elapsed_ms,returned_model,
            prompt_tokens,completion_tokens,total_tokens,cache_hit_tokens,cache_miss_tokens
            FROM model_call WHERE run_id=%s AND outcome IS NOT NULL""", (run_id,)).fetchone()
        if row is None: return None
        usage = None
        if row["total_tokens"] is not None:
            usage = {"promptTokens": row["prompt_tokens"], "completionTokens": row["completion_tokens"],
                     "totalTokens": row["total_tokens"], "cacheHitTokens": row["cache_hit_tokens"],
                     "cacheMissTokens": row["cache_miss_tokens"]}
        return {"requestId": row["request_id"], "purpose": "DIAGNOSIS", "provider": "deepseek",
                "model": row["returned_model"] or "deepseek-flash", "outcome": row["outcome"],
                "errorCode": row["error_code"], "httpStatus": row["http_status"],
                "elapsedMs": row["elapsed_ms"], "usage": usage}

    def _response(self, run_id, state, metadata, conn=None):
        response = {"runId": str(run_id), "status": "RUNNING", "graphVersion": metadata["graph_version"]}
        if state.values.get("result"):
            response["result"] = state.values["result"]
            if state.interrupts: response["status"] = "WAITING_HUMAN"
            elif not state.next:
                response["status"] = {"APPROVED": "APPROVED_FOR_MANUAL", "REJECTED": "REJECTED"}.get(state.values.get("reviewDecision"), "COMPLETED")
        if metadata["cancelled"]: response["status"] = "CANCELLED"
        provider_request = self.model_request(run_id, conn)
        if provider_request is not None: response["providerRequest"] = provider_request
        return response
