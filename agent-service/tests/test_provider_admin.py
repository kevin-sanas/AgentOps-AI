import json
import ssl
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from test_review import review_payload


def headers(key="fixture-deepseek-key"):
    return {
        "Authorization": "Bearer " + "internal-token-" + "x" * 32,
        "X-Request-Id": str(uuid4()),
        "X-Dawnwatch-Model-Credential": key,
    }


def test_connection_lists_required_model_once_without_exposing_key():
    from agentops_agent.provider_admin import DeepSeekAdminGateway
    calls = []

    def respond(request):
        calls.append(request)
        assert request.method == "GET"
        assert str(request.url) == "https://api.deepseek.com/models"
        assert request.headers["Authorization"] == "Bearer fixture-deepseek-key"
        return httpx.Response(200, json={"object": "list", "data": [{"id": "deepseek-flash", "object": "model"}]})

    result = DeepSeekAdminGateway(transport=httpx.MockTransport(respond)).test_connection(
        "fixture-deepseek-key", "request-1")
    assert result["outcome"] == "SUCCEEDED"
    assert result["requestId"] == "request-1"
    assert result["model"] == "deepseek-flash"
    assert result["usage"] is None
    assert len(calls) == 1
    assert "fixture-deepseek-key" not in json.dumps(result)


@pytest.mark.parametrize("case,code,status", [
    ("missing", "MODEL_REQUIRED_MODEL_UNAVAILABLE", 200),
    ("auth", "MODEL_AUTHENTICATION_FAILED", 401),
    ("rate", "MODEL_RATE_LIMITED", 429),
    ("server", "MODEL_PROVIDER_UNAVAILABLE", 503),
    ("redirect", "MODEL_REDIRECT_REJECTED", 302),
    ("oversize", "MODEL_RESPONSE_TOO_LARGE", 200),
    ("invalid", "MODEL_RESPONSE_INVALID", 200),
])
def test_connection_maps_provider_failures_without_retry(case, code, status):
    from agentops_agent.provider_admin import DeepSeekAdminGateway
    calls = []

    def respond(request):
        calls.append(request)
        if case == "auth": return httpx.Response(401)
        if case == "rate": return httpx.Response(429)
        if case == "server": return httpx.Response(503)
        if case == "redirect": return httpx.Response(302, headers={"location": "https://other.example/models"})
        if case == "oversize": return httpx.Response(200, content=b"x" * 65537)
        if case == "invalid": return httpx.Response(200, content=b"not-json")
        return httpx.Response(200, json={"data": [{"id": "deepseek-chat"}]})

    result = DeepSeekAdminGateway(transport=httpx.MockTransport(respond)).test_connection("fixture-key", "request-2")
    assert result["outcome"] == "FAILED"
    assert result["errorCode"] == code
    assert result["httpStatus"] == status
    assert len(calls) == 1


@pytest.mark.parametrize("case,code", [
    ("tls", "MODEL_TLS_VERIFY_FAILED"),
    ("timeout", "MODEL_TIMEOUT"),
    ("connect", "MODEL_CONNECT_FAILED"),
])
def test_connection_maps_transport_failures_without_exception_text(case, code):
    from agentops_agent.provider_admin import DeepSeekAdminGateway

    def respond(request):
        if case == "tls":
            try:
                raise ssl.SSLCertVerificationError("fixture sensitive certificate detail")
            except ssl.SSLCertVerificationError as cause:
                raise httpx.ConnectError("fixture sensitive connect detail", request=request) from cause
        if case == "timeout": raise httpx.ReadTimeout("fixture sensitive timeout", request=request)
        raise httpx.ConnectError("fixture sensitive connect detail", request=request)

    result = DeepSeekAdminGateway(transport=httpx.MockTransport(respond)).test_connection("fixture-key", "request-3")
    assert result["errorCode"] == code
    assert "sensitive" not in json.dumps(result)


def test_connection_reconnects_once_after_a_tls_verification_failure():
    """A transient TLS edge failure must not require a second operator click."""
    from agentops_agent.provider_admin import DeepSeekAdminGateway
    calls = []

    def respond(request):
        calls.append(request)
        if len(calls) == 1:
            try:
                raise ssl.SSLCertVerificationError("fixture certificate chain failure")
            except ssl.SSLCertVerificationError as cause:
                raise httpx.ConnectError("fixture TLS failure", request=request) from cause
        return httpx.Response(200, json={
            "object": "list", "data": [{"id": "deepseek-flash", "object": "model"}],
        })

    result = DeepSeekAdminGateway(transport=httpx.MockTransport(respond)).test_connection(
        "fixture-key", "request-connection-reconnect")
    assert result["outcome"] == "SUCCEEDED"
    assert len(calls) == 2


def test_connection_stops_after_one_tls_reconnect_and_keeps_the_safe_error():
    """A persistently invalid certificate must still fail closed after two strict handshakes."""
    from agentops_agent.provider_admin import DeepSeekAdminGateway
    calls = []

    def respond(request):
        calls.append(request)
        try:
            raise ssl.SSLCertVerificationError("fixture certificate chain failure")
        except ssl.SSLCertVerificationError as cause:
            raise httpx.ConnectError("fixture TLS failure", request=request) from cause

    result = DeepSeekAdminGateway(transport=httpx.MockTransport(respond)).test_connection(
        "fixture-key", "request-connection-fail-closed")
    assert result["outcome"] == "FAILED"
    assert result["errorCode"] == "MODEL_TLS_VERIFY_FAILED"
    assert len(calls) == 2


def test_balance_validates_multi_currency_decimal_rows():
    from agentops_agent.provider_admin import DeepSeekAdminGateway

    def respond(request):
        assert str(request.url) == "https://api.deepseek.com/user/balance"
        return httpx.Response(200, json={
            "is_available": True,
            "balance_infos": [
                {"currency": "CNY", "total_balance": "12.34", "granted_balance": "2.34", "topped_up_balance": "10.00"},
                {"currency": "USD", "total_balance": "1.50", "granted_balance": "0.00", "topped_up_balance": "1.50"},
            ],
        })

    result = DeepSeekAdminGateway(transport=httpx.MockTransport(respond)).balance("fixture-key", "request-4")
    assert result["outcome"] == "SUCCEEDED"
    assert result["isAvailable"] is True
    assert result["balances"][0] == {
        "currency": "CNY", "totalBalance": "12.34", "grantedBalance": "2.34", "toppedUpBalance": "10.00"
    }
    assert result["usage"] is None


def test_balance_reconnects_once_after_a_tls_verification_failure():
    """A bad edge certificate must not force the operator to click refresh manually."""
    from agentops_agent.provider_admin import DeepSeekAdminGateway
    calls = []

    def respond(request):
        calls.append(request)
        if len(calls) == 1:
            try:
                raise ssl.SSLCertVerificationError("fixture certificate chain failure")
            except ssl.SSLCertVerificationError as cause:
                raise httpx.ConnectError("fixture TLS failure", request=request) from cause
        return httpx.Response(200, json={
            "is_available": True,
            "balance_infos": [{
                "currency": "CNY", "total_balance": "12.34",
                "granted_balance": "2.34", "topped_up_balance": "10.00",
            }],
        })

    result = DeepSeekAdminGateway(transport=httpx.MockTransport(respond)).balance("fixture-key", "request-reconnect")
    assert result["outcome"] == "SUCCEEDED"
    assert result["balances"][0]["totalBalance"] == "12.34"
    assert len(calls) == 2


def test_balance_stops_after_one_tls_reconnect_and_keeps_the_safe_error():
    """A persistently invalid certificate must still fail closed after the bounded reconnect."""
    from agentops_agent.provider_admin import DeepSeekAdminGateway
    calls = []

    def respond(request):
        calls.append(request)
        try:
            raise ssl.SSLCertVerificationError("fixture certificate chain failure")
        except ssl.SSLCertVerificationError as cause:
            raise httpx.ConnectError("fixture TLS failure", request=request) from cause

    result = DeepSeekAdminGateway(transport=httpx.MockTransport(respond)).balance("fixture-key", "request-fail-closed")
    assert result["outcome"] == "FAILED"
    assert result["errorCode"] == "MODEL_TLS_VERIFY_FAILED"
    assert len(calls) == 2


@pytest.mark.parametrize("value", ["NaN", "Infinity", "-1.00", "not-a-number"])
def test_balance_rejects_invalid_decimal_values(value):
    from agentops_agent.provider_admin import DeepSeekAdminGateway
    response = {"is_available": False, "balance_infos": [{
        "currency": "CNY", "total_balance": value, "granted_balance": "0", "topped_up_balance": "0"
    }]}
    gateway = DeepSeekAdminGateway(transport=httpx.MockTransport(lambda request: httpx.Response(200, json=response)))
    assert gateway.balance("fixture-key", "request-5")["errorCode"] == "MODEL_RESPONSE_INVALID"


class FakeStore:
    def __init__(self):
        self.provider = None
        self.request_id = None

    def setup(self): pass

    def invoke(self, body, provider=None, request_id=None):
        self.provider = provider
        self.request_id = request_id
        return {"runId": body["runId"], "status": "RUNNING", "graphVersion": body["graphVersion"]}


def test_internal_admin_endpoints_require_bounded_credential_and_return_safe_envelopes():
    from agentops_agent.app import create_app
    token = "internal-token-" + "x" * 32
    transport = httpx.MockTransport(lambda request: httpx.Response(200, json={
        "data": [{"id": "deepseek-flash"}]
    }))
    with TestClient(create_app(FakeStore(), token, provider_transport=transport)) as client:
        base = {"Authorization": "Bearer " + token, "X-Request-Id": str(uuid4())}
        assert client.post("/internal/v1/providers/deepseek/test", headers=base).json()["code"] == "MODEL_CREDENTIAL_REQUIRED"
        too_long = base | {"X-Dawnwatch-Model-Credential": "x" * 513}
        assert client.post("/internal/v1/providers/deepseek/test", headers=too_long).json()["code"] == "MODEL_CREDENTIAL_INVALID"
        key = "fixture-deepseek-key"
        response = client.post("/internal/v1/providers/deepseek/test", headers=base | {"X-Dawnwatch-Model-Credential": key})
        assert response.status_code == 200 and response.json()["outcome"] == "SUCCEEDED"
        assert key not in response.text


def test_invoke_builds_an_invocation_scoped_provider_and_clears_its_key():
    from agentops_agent.app import create_app
    token = "internal-token-" + "x" * 32
    store = FakeStore()
    body = review_payload() | {"modelName": "deepseek-flash"}
    request_id = str(uuid4())
    key = "fixture-deepseek-key"
    request_headers = {
        "Authorization": "Bearer " + token,
        "X-Request-Id": request_id,
        "X-Dawnwatch-Model-Credential": key,
    }
    with TestClient(create_app(store, token)) as client:
        response = client.post("/internal/v1/runs/" + body["runId"] + "/invoke", headers=request_headers, json=body)
    assert response.status_code == 202
    assert store.request_id == request_id
    assert store.provider.model == "deepseek-flash"
    assert store.provider.key == ""
    assert key not in response.text
