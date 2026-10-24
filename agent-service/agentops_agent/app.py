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
from .provider import ModelOutcomeUnknown


def create_app(store: Store, token: str):
    if len(token.encode()) < 32: raise ValueError("INTERNAL_TOKEN_TOO_SHORT")

    @asynccontextmanager
    async def lifespan(app):
        store.setup()
        yield

    app = FastAPI(title="AgentOps internal diagnosis", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

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

    @app.post("/internal/v1/runs/{run_id}/invoke")
    def invoke(run_id: UUID, body: Invocation):
        if run_id != body.runId: raise HTTPException(400, "RUN_ID_MISMATCH")
        try:
            result = store.invoke(body.model_dump(mode="json"))
            return JSONResponse(result, status_code=202 if result["status"] == "RUNNING" else 200)
        except ModelOutcomeUnknown:
            return JSONResponse({"code": "MODEL_OUTCOME_UNKNOWN"}, status_code=409)
        except InputConflict:
            return JSONResponse({"code": "INPUT_CONFLICT"}, status_code=409)
        except (ValidationError, ValueError):
            return JSONResponse({"code": "AGENT_SCHEMA_INVALID"}, status_code=400)
        except TimeoutError:
            return JSONResponse({"code": "AGENT_GRAPH_TIMEOUT"}, status_code=503)
        except Exception:
            # No DSN, payload or traceback in the HTTP response.
            return JSONResponse({"code": "DEPENDENCY_UNAVAILABLE"}, status_code=503)

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
    provider = None
    kind = os.environ.get("AGENT_LLM_PROVIDER", "mock")
    if kind == "openai-compatible":
        from .provider import CompatibleProvider
        provider = CompatibleProvider(os.environ["AGENT_LLM_BASE_URL"], os.environ["AGENT_MODEL_NAME"], os.environ["AGENT_LLM_API_KEY"],
                                      allow_local_http=os.environ.get("AGENT_ALLOW_LOCAL_MODEL_HTTP", "false").lower() == "true")
    elif kind == "deepseek":
        from .provider import DeepSeekProvider
        provider = DeepSeekProvider(os.environ["AGENT_MODEL_NAME"], os.environ["AGENT_LLM_API_KEY"])
    elif kind != "mock": raise ValueError("UNKNOWN_MODEL_PROVIDER")
    return create_app(Store(os.environ["AGENT_CHECKPOINT_DSN"], provider=provider), os.environ["AGENT_INTERNAL_TOKEN"])
