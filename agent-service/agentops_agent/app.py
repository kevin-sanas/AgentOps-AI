import hmac
import json
import os
import re
from contextlib import asynccontextmanager
from uuid import UUID
from fastapi import FastAPI, Request, HTTPException
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from .models import Invocation, Resume, Cancel
from .store import Store, InputConflict
from .provider import DeepSeekProvider, ModelOutcomeUnknown
from .provider_admin import DeepSeekAdminGateway


def create_app(store: Store, token: str, *, provider_transport=None, managed_provider_factory=None):
    if len(token.encode()) < 32: raise ValueError("INTERNAL_TOKEN_TOO_SHORT")

    @asynccontextmanager
    async def lifespan(app):
        store.setup()
        yield

    app = FastAPI(title="Dawnwatch internal diagnosis", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    @app.middleware("http")
    async def protect(request: Request, call_next):
        authorization = request.headers.get("authorization", "")
        supplied = authorization[7:] if authorization.startswith("Bearer ") and len(authorization) <= 1024 else ""
        if not hmac.compare_digest(supplied.encode(), token.encode()):
            return JSONResponse({"code": "UNAUTHENTICATED"}, status_code=401)
        request_id = request.headers.get("x-request-id", "")
        if not re.fullmatch(r"[A-Za-z0-9._:-]{1,80}", request_id):
            return JSONResponse({"code": "INVALID_REQUEST_ID"}, status_code=400)
        # Count streamed bytes as well as declared length; reject before JSON parsing.
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 65536: return JSONResponse({"code": "BODY_TOO_LARGE"}, status_code=413)
        request._body = bytes(body)
        response = await call_next(request)
        response.headers["X-Request-Id"] = request_id
        return response

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        return JSONResponse({"code": "VALIDATION_FAILED"}, status_code=400)

    @app.exception_handler(HTTPException)
    async def http_error(request, exc):
        code = exc.detail if isinstance(exc.detail, str) and re.fullmatch(r"[A-Z0-9_]{1,80}", exc.detail) else "REQUEST_REJECTED"
        return JSONResponse({"code": code}, status_code=exc.status_code)

    def model_credential(request: Request):
        credential = request.headers.get("x-dawnwatch-model-credential", "")
        if not credential:
            return None
        if len(credential.encode()) > 512 or "\r" in credential or "\n" in credential:
            raise HTTPException(400, "MODEL_CREDENTIAL_INVALID")
        return credential

    @app.post("/internal/v1/providers/deepseek/test")
    def test_provider(request: Request):
        credential = model_credential(request)
        if credential is None: return JSONResponse({"code": "MODEL_CREDENTIAL_REQUIRED"}, status_code=400)
        try:
            return DeepSeekAdminGateway(transport=provider_transport).test_connection(
                credential, request.headers["x-request-id"])
        finally:
            credential = None

    @app.get("/internal/v1/providers/deepseek/balance")
    def provider_balance(request: Request):
        credential = model_credential(request)
        if credential is None: return JSONResponse({"code": "MODEL_CREDENTIAL_REQUIRED"}, status_code=400)
        try:
            return DeepSeekAdminGateway(transport=provider_transport).balance(
                credential, request.headers["x-request-id"])
        finally:
            credential = None

    @app.post("/internal/v1/runs/{run_id}/invoke")
    def invoke(run_id: UUID, body: Invocation, request: Request):
        if run_id != body.runId: raise HTTPException(400, "RUN_ID_MISMATCH")
        credential = model_credential(request)
        provider = None
        try:
            if credential is not None:
                if body.modelName != "deepseek-flash": raise ValueError("MODEL_CONFIGURATION_MISMATCH")
                provider = (managed_provider_factory(credential) if managed_provider_factory is not None
                            else DeepSeekProvider("deepseek-flash", credential, transport=provider_transport))
            result = store.invoke(body.model_dump(mode="json"), provider=provider,
                                  request_id=request.headers["x-request-id"])
            return JSONResponse(result, status_code=202 if result["status"] == "RUNNING" else 200)
        except ModelOutcomeUnknown:
            return JSONResponse(invoke_error(run_id, "MODEL_OUTCOME_UNKNOWN"), status_code=409)
        except InputConflict:
            return JSONResponse(invoke_error(run_id, "INPUT_CONFLICT"), status_code=409)
        except (ValidationError, ValueError):
            return JSONResponse(invoke_error(run_id, "AGENT_SCHEMA_INVALID"), status_code=400)
        except TimeoutError:
            return JSONResponse(invoke_error(run_id, "AGENT_GRAPH_TIMEOUT"), status_code=503)
        except Exception:
            # No DSN, payload or traceback in the HTTP response.
            return JSONResponse(invoke_error(run_id, "DEPENDENCY_UNAVAILABLE"), status_code=503)
        finally:
            if provider is not None: provider.key = ""
            credential = None

    def invoke_error(run_id, code):
        body = {"code": code}
        reader = getattr(store, "model_request", None)
        if reader is not None:
            try: metadata = reader(run_id)
            except Exception: metadata = None
            if metadata is not None: body["providerRequest"] = metadata
        return body

    @app.get("/internal/v1/runs/{run_id}")
    def read(run_id: UUID):
        try:
            result = store.read(run_id)
            return JSONResponse(result, status_code=200) if result else JSONResponse({"code": "RESOURCE_NOT_FOUND"}, status_code=404)
        except Exception:
            return JSONResponse({"code": "DEPENDENCY_UNAVAILABLE"}, status_code=503)

    def transition(action):
        try:
            result = action()
            return JSONResponse(result, status_code=202 if result["status"] == "RUNNING" else 200)
        except KeyError:
            return JSONResponse({"code": "RESOURCE_NOT_FOUND"}, status_code=404)
        except InputConflict:
            return JSONResponse({"code": "DECISION_CONFLICT"}, status_code=409)
        except (ValidationError, ValueError):
            return JSONResponse({"code": "AGENT_SCHEMA_INVALID"}, status_code=400)
        except Exception:
            return JSONResponse({"code": "DEPENDENCY_UNAVAILABLE"}, status_code=503)

    @app.post("/internal/v1/runs/{run_id}/resume")
    def resume(run_id: UUID, body: Resume):
        return transition(lambda: store.resume(run_id, body))

    @app.post("/internal/v1/runs/{run_id}/cancel")
    def cancel(run_id: UUID, body: Cancel):
        return transition(lambda: store.cancel(run_id))
    return app


def from_environment():
    # Disable tracing even if a parent environment enabled it for a different project.
    os.environ["LANGSMITH_TRACING"] = "false"
    os.environ["LANGCHAIN_TRACING_V2"] = "false"
    os.environ["LANGCHAIN_TRACING"] = "false"
    # Runtime model credentials are supplied per request by the Java configuration store.
    # Legacy model environment variables are deliberately ignored.
    return create_app(Store(os.environ["AGENT_CHECKPOINT_DSN"], provider=None), os.environ["AGENT_INTERNAL_TOKEN"])
