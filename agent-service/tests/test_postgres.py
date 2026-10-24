"""Real PostgreSQL tests; Java AgentIntegrationTest provisions the disposable database and role."""
import hashlib
import json
import os
from concurrent.futures import ThreadPoolExecutor
from uuid import UUID, uuid4
import psycopg
import pytest
from fastapi.testclient import TestClient
from test_graph import payload


@pytest.fixture()
def service():
    if not os.environ.get("AGENT_TEST_DSN"):
        pytest.skip("Run scripts/backend.ps1 verify for isolated PostgreSQL integration")
    from agentops_agent.store import Store
    store = Store(os.environ["AGENT_TEST_DSN"])
    store.setup()
    return store


def test_postgres_http_auth_limits_idempotency_and_restart(service):
    from agentops_agent.app import create_app
    token = "fixture-" + str(uuid4())
    body = payload(); path = "/internal/v1/runs/" + body["runId"]
    headers = {"Authorization": "Bearer " + token, "X-Request-Id": str(uuid4())}
    with TestClient(create_app(service, token)) as client:
        assert client.get(path).status_code == 401
        assert client.get(path, headers={"Authorization": "Bearer " + token}).status_code == 400
        assert client.get(path, headers=headers).status_code == 404
        bad = body | {"secret": "ignored?"}
        assert client.post(path + "/invoke", headers=headers, json=bad).status_code == 400
        assert client.post(path + "/invoke", headers=headers, content="x" * 65537).status_code == 413
        result = client.post(path + "/invoke", headers=headers, json=body)
        assert result.status_code == 200, result.text
        report = result.json(); assert report["status"] == "COMPLETED"
        assert report["result"]["modelName"] == "mock"
        assert client.post(path + "/invoke", headers=headers, json=body).json() == report
        assert client.post(path + "/cancel", headers=headers, json={}).status_code == 409
        assert client.get(path, headers=headers).json() == report
        changed = payload() | {"runId": body["runId"]}
        assert client.post(path + "/invoke", headers=headers, json=changed).status_code == 409
    # New Store and app, no process-local cache. State must come from persisted checkpoints.
    from agentops_agent.store import Store
    restarted = Store(os.environ["AGENT_TEST_DSN"])
    with TestClient(create_app(restarted, token)) as client:
        assert client.get(path, headers=headers).json()["result"] == report["result"]


def test_postgres_checkpoint_resume_and_concurrent_advisory_lock(service):
    from agentops_agent.graph import build_graph
    body = payload()
    # A committed node checkpoint represents a process that stopped after context.
    with service.lock(UUID(body["runId"])) as conn:
        service.register(conn, body)
        graph = build_graph(service.saver(conn), interrupt_after=["context"])
        config = {"configurable": {"thread_id": body["runId"]}}
        graph.invoke({"input": body}, config)
        assert graph.get_state(config).next == ("retrieval",)
        with ThreadPoolExecutor(1) as pool:
            running = pool.submit(service.invoke, body).result(timeout=5)
            assert running["status"] == "RUNNING"
            changed = payload() | {"runId": body["runId"]}
            from agentops_agent.store import InputConflict
            with pytest.raises(InputConflict): pool.submit(service.invoke, changed).result(timeout=5)
    result = service.invoke(body)
    assert result["status"] == "COMPLETED"
    assert result["result"]["steps"] == ["validate", "context", "retrieval", "analyze", "guard", "finalize"]
    assert service.read(UUID(body["runId"]))["result"] == result["result"]


def test_postgres_checkpoint_role_is_not_a_business_database_superuser(service):
    with service.connect() as conn:
        user = conn.execute("SELECT rolsuper,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname=current_user").fetchone()
        assert not user["rolsuper"] and not user["rolcreatedb"] and not user["rolcreaterole"]
        assert conn.execute("SELECT current_schema() s").fetchone()["s"] == "agent_checkpoint"
        assert conn.execute("SELECT to_regclass('public.app_user') value").fetchone()["value"] is None
    # Even when connecting to the separate Java database, the role has no SELECT permission.
    with psycopg.connect(os.environ["AGENT_TEST_BUSINESS_DSN"], autocommit=True) as conn:
        with pytest.raises(psycopg.errors.InsufficientPrivilege): conn.execute("SELECT * FROM public.app_user")
    from agentops_agent.store import Store
    with pytest.raises(ValueError, match="CHECKPOINT_DATABASE_MUST_BE_SEPARATE"):
        Store(os.environ["AGENT_TEST_BUSINESS_DSN"]).setup()


@pytest.mark.parametrize("decision", ["APPROVED", "REJECTED"])
def test_review_survives_restart_and_decision_is_immutable(service, decision):
    from agentops_agent.app import create_app
    from agentops_agent.store import Store
    body = payload() | {"graphVersion": "readonly-review-v2", "modelName": "mock"}
    path = "/internal/v1/runs/" + body["runId"]
    token = "fixture-" + str(uuid4())
    headers = {"Authorization": "Bearer " + token, "X-Request-Id": str(uuid4())}
    with TestClient(create_app(service, token)) as client:
        waiting = client.post(path + "/invoke", headers=headers, json=body)
        assert waiting.status_code == 200, waiting.text
        assert waiting.json()["status"] == "WAITING_HUMAN"
        assert client.post(path + "/resume", headers=headers, json={"unexpected": True}).status_code == 400
        assert client.post(path + "/cancel", headers=headers, json={"unexpected": True}).status_code == 400
        frozen = waiting.json()["result"]
        assert client.post(path + "/invoke", headers=headers, json=body).json() == waiting.json()
    command = {"approvalRequestId": str(uuid4()), "decision": decision, "decisionHash": "a" * 64}
    with TestClient(create_app(Store(service.dsn), token)) as client:
        assert client.get(path, headers=headers).json()["status"] == "WAITING_HUMAN"
        resumed = client.post(path + "/resume", headers=headers, json=command)
        assert resumed.status_code == 200, resumed.text
        assert resumed.json()["status"] == ("APPROVED_FOR_MANUAL" if decision == "APPROVED" else "REJECTED")
        assert resumed.json()["result"] == frozen
        assert client.post(path + "/resume", headers=headers, json=command).json() == resumed.json()
        assert client.post(path + "/resume", headers=headers, json=command | {"decisionHash": "b" * 64}).status_code == 409
        assert client.post(path + "/cancel", headers=headers, json={}).status_code == 409
    assert Store(service.dsn).read(UUID(body["runId"])) == resumed.json()


def test_cancel_is_durable_and_prevents_future_resume(service):
    from agentops_agent.app import create_app
    from agentops_agent.store import Store
    body = payload() | {"graphVersion": "readonly-review-v2"}
    path = "/internal/v1/runs/" + body["runId"]
    headers = {"Authorization": "Bearer " + "c" * 40, "X-Request-Id": str(uuid4())}
    with TestClient(create_app(service, "c" * 40)) as client:
        assert client.post(path + "/invoke", headers=headers, json=body).json()["status"] == "WAITING_HUMAN"
        assert client.post(path + "/cancel", headers=headers, json={}).json()["status"] == "CANCELLED"
    with TestClient(create_app(Store(service.dsn), "c" * 40)) as client:
        assert client.post(path + "/invoke", headers=headers, json=body).json()["status"] == "CANCELLED"
        assert client.post(path + "/cancel", headers=headers, json={}).json()["status"] == "CANCELLED"
        command = {"approvalRequestId": str(uuid4()), "decision": "APPROVED", "decisionHash": "a" * 64}
        assert client.post(path + "/resume", headers=headers, json=command).status_code == 409


def test_decision_commit_before_graph_resume_recovers_without_calling_model_again(service):
    from langgraph.checkpoint.memory import InMemorySaver
    from agentops_agent.graph import build_graph
    from agentops_agent.models import Resume
    from agentops_agent.store import Store
    legacy = payload()
    report = build_graph(InMemorySaver()).invoke({"input": legacy}, {"configurable": {"thread_id": legacy["runId"]}})["result"]
    body = legacy | {"graphVersion": "readonly-review-v2", "modelName": "fixture-model"}
    report.update(graphVersion=body["graphVersion"], modelName=body["modelName"])
    class CountingProvider:
        model = "fixture-model"
        calls = 0
        def analyze(self, invocation):
            self.calls += 1
            return report
    provider = CountingProvider(); configured = Store(service.dsn, provider=provider)
    first = configured.invoke(body)
    assert first["status"] == "WAITING_HUMAN" and provider.calls == 1
    assert configured.invoke(body) == first and provider.calls == 1
    command = {"approvalRequestId": str(uuid4()), "decision": "APPROVED", "decisionHash": "d" * 64}
    with service.connect() as conn:
        conn.execute("UPDATE invocation SET decision=%s::jsonb WHERE run_id=%s", (json.dumps(command), body["runId"]))
    # Simulate restart after the metadata commit, without even retaining model configuration.
    restarted = Store(service.dsn)
    resumed = restarted.resume(UUID(body["runId"]), Resume.model_validate(command))
    assert resumed["status"] == "APPROVED_FOR_MANUAL"
    assert resumed["result"] == first["result"] and provider.calls == 1
    assert restarted.resume(UUID(body["runId"]), Resume.model_validate(command)) == resumed


def test_deepseek_report_and_approval_reuse_one_validated_response(service):
    import httpx
    from test_review import review_payload
    from test_provider import answer
    from agentops_agent.provider import DeepSeekProvider
    from agentops_agent.models import Resume
    from agentops_agent.store import Store
    body = review_payload() | {"modelName": "deepseek-flash"}
    calls = []
    def handle(request):
        calls.append(request.url)
        assert json.loads(request.content)["response_format"] == {"type": "json_object"}
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(answer(body))}}]})
    configured = Store(service.dsn, provider=DeepSeekProvider("deepseek-flash", "fixture-key", transport=httpx.MockTransport(handle)))
    first = configured.invoke(body)
    assert first["status"] == "WAITING_HUMAN" and first["result"]["modelName"] == "deepseek-flash"
    assert configured.invoke(body) == first and len(calls) == 1
    with service.connect() as conn:
        record = conn.execute("SELECT model_name,status FROM model_call WHERE run_id=%s", (body["runId"],)).fetchone()
    assert record == {"model_name": "deepseek-flash", "status": "COMPLETED"}
    command = Resume(approvalRequestId=uuid4(), decision="APPROVED", decisionHash="a" * 64)
    resumed = Store(service.dsn).resume(UUID(body["runId"]), command)
    assert resumed["status"] == "APPROVED_FOR_MANUAL" and resumed["result"] == first["result"]
    assert len(calls) == 1


def test_legacy_hash_from_g3_is_still_replayable(service):
    from agentops_agent.models import Invocation
    body = payload()
    original = Invocation.model_validate(body).model_dump(mode="json", exclude={"modelName"})
    digest = hashlib.sha256(json.dumps(original, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
    with service.connect() as conn:
        conn.execute("INSERT INTO invocation(run_id,input_hash) VALUES(%s,%s)", (body["runId"], digest))
    result = service.invoke(body)
    assert result["status"] == "COMPLETED" and result["graphVersion"] == "readonly-mock-v1"


@pytest.mark.parametrize("window", ["request_start", "checkpoint", "result_commit"])
def test_model_result_or_ambiguous_marker_prevents_automatic_second_call(service, monkeypatch, window):
    from langgraph.checkpoint.memory import InMemorySaver
    from langgraph.checkpoint.postgres import PostgresSaver
    from agentops_agent.graph import build_graph
    from agentops_agent.model_calls import PersistedProvider
    from agentops_agent.provider import ModelOutcomeUnknown
    from agentops_agent.store import Store
    legacy = payload()
    report = build_graph(InMemorySaver()).invoke({"input": legacy}, {"configurable": {"thread_id": legacy["runId"]}})["result"]
    body = legacy | {"graphVersion": "readonly-review-v2", "modelName": "fixture-model"}
    report.update(graphVersion=body["graphVersion"], modelName=body["modelName"])
    class CountingProvider:
        model = "fixture-model"
        calls = 0
        def analyze(self, invocation):
            with service.connect() as conn:
                assert conn.execute("SELECT status FROM model_call WHERE run_id=%s", (body["runId"],)).fetchone()["status"] == "STARTED"
            self.calls += 1
            return report
    provider = CountingProvider(); configured = Store(service.dsn, provider=provider)
    if window == "request_start":
        # Process exits after STARTED committed, before the HTTP request was sent.
        with service.lock(UUID(body["runId"])) as conn:
            normalized = configured.register(conn, body)
            paused = build_graph(service.saver(conn), interrupt_after=["retrieval"])
            paused.invoke({"input": normalized}, {"configurable": {"thread_id": body["runId"]}}, durability="sync")
            conn.execute("INSERT INTO model_call(run_id,model_name,status) VALUES(%s,'fixture-model','STARTED')", (body["runId"],))
    class FaultSaver(PostgresSaver):
        def put(self, *args, **kwargs):
            if provider.calls: raise RuntimeError("synthetic checkpoint commit failure")
            return super().put(*args, **kwargs)
        def put_writes(self, *args, **kwargs):
            if provider.calls: raise RuntimeError("synthetic checkpoint writes failure")
            return super().put_writes(*args, **kwargs)
    with monkeypatch.context() as fault:
        if window == "checkpoint": fault.setattr(configured, "saver", lambda conn: FaultSaver(conn))
        elif window == "result_commit":
            def fail_commit(self, result): raise RuntimeError("synthetic result commit failure")
            fault.setattr(PersistedProvider, "_store_result", fail_commit)
        with pytest.raises(RuntimeError): configured.invoke(body)
    expected_calls = 0 if window == "request_start" else 1
    assert provider.calls == expected_calls
    restarted = Store(service.dsn, provider=provider)
    if window == "checkpoint":
        assert restarted.invoke(body)["status"] == "WAITING_HUMAN"
    else:
        with pytest.raises(ModelOutcomeUnknown): restarted.invoke(body)
        from agentops_agent.app import create_app
        token = "fixture-" + str(uuid4()); headers = {"Authorization": "Bearer " + token, "X-Request-Id": str(uuid4())}
        with TestClient(create_app(restarted, token)) as client:
            response = client.post("/internal/v1/runs/" + body["runId"] + "/invoke", json=body, headers=headers)
            assert response.status_code == 409 and response.json()["code"] == "MODEL_OUTCOME_UNKNOWN"
    assert provider.calls == expected_calls
