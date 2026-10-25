"""Bounded, one-request DeepSeek administration calls with safe result envelopes."""
import asyncio
from decimal import Decimal, InvalidOperation
import json
import re
import ssl
from time import monotonic

import httpx


class AdminResponseError(ValueError):
    def __init__(self, code, http_status):
        super().__init__(code)
        self.http_status = http_status


class DeepSeekAdminGateway:
    ORIGIN = "https://api.deepseek.com"
    MODEL = "deepseek-flash"
    MAX_RESPONSE = 65536

    def __init__(self, *, transport=None, timeout=10):
        if timeout <= 0 or timeout > 10: raise ValueError("INVALID_ADMIN_TIMEOUT")
        self.transport = transport
        self.timeout = timeout

    def test_connection(self, key, request_id):
        started = monotonic()
        base = self._base(request_id, "CONNECTION_TEST")
        try:
            status, data = asyncio.run(self._get("/models", key))
            if status != 200: return self._failure(base, self._status_error(status), status, started)
            models = data.get("data") if isinstance(data, dict) else None
            if not isinstance(models, list) or len(models) > 500:
                return self._failure(base, "MODEL_RESPONSE_INVALID", status, started)
            ids = {item.get("id") for item in models if isinstance(item, dict) and isinstance(item.get("id"), str)}
            if self.MODEL not in ids:
                return self._failure(base, "MODEL_REQUIRED_MODEL_UNAVAILABLE", status, started)
            return base | {"outcome": "SUCCEEDED", "errorCode": None, "httpStatus": status,
                           "elapsedMs": self._elapsed(started)}
        except Exception as error:
            return self._exception(base, error, started)

    def balance(self, key, request_id):
        started = monotonic()
        base = self._base(request_id, "BALANCE_QUERY") | {"isAvailable": None, "balances": []}
        try:
            status, data = asyncio.run(self._get("/user/balance", key))
            if status != 200: return self._failure(base, self._status_error(status), status, started)
            available = data.get("is_available") if isinstance(data, dict) else None
            rows = data.get("balance_infos") if isinstance(data, dict) else None
            if type(available) is not bool or not isinstance(rows, list) or not 0 < len(rows) <= 20:
                return self._failure(base, "MODEL_RESPONSE_INVALID", status, started)
            try: balances = [self._balance_row(row) for row in rows]
            except ValueError: return self._failure(base, "MODEL_RESPONSE_INVALID", status, started)
            return base | {"outcome": "SUCCEEDED", "errorCode": None, "httpStatus": status,
                           "elapsedMs": self._elapsed(started), "isAvailable": available, "balances": balances}
        except Exception as error:
            return self._exception(base, error, started)

    async def _get(self, path, key):
        async with asyncio.timeout(self.timeout):
            async with httpx.AsyncClient(timeout=self.timeout, follow_redirects=False, trust_env=False,
                                         transport=self.transport) as client:
                async with client.stream("GET", self.ORIGIN + path,
                                         headers={"Authorization": "Bearer " + key}) as response:
                    status = response.status_code
                    if status != 200: return status, None
                    content = bytearray()
                    async for chunk in response.aiter_bytes():
                        content.extend(chunk)
                        if len(content) > self.MAX_RESPONSE: raise AdminResponseError("MODEL_RESPONSE_TOO_LARGE", status)
                    try:
                        return status, json.loads(content)
                    except (UnicodeDecodeError, json.JSONDecodeError):
                        raise AdminResponseError("MODEL_RESPONSE_INVALID", status) from None

    @staticmethod
    def _balance_row(row):
        if not isinstance(row, dict) or not re.fullmatch(r"[A-Z]{3}", str(row.get("currency", ""))):
            raise ValueError("MODEL_RESPONSE_INVALID")
        mapped = {"currency": row["currency"]}
        for source, target in (("total_balance", "totalBalance"), ("granted_balance", "grantedBalance"),
                               ("topped_up_balance", "toppedUpBalance")):
            value = row.get(source)
            if not isinstance(value, str): raise ValueError("MODEL_RESPONSE_INVALID")
            try: decimal = Decimal(value)
            except InvalidOperation: raise ValueError("MODEL_RESPONSE_INVALID") from None
            if not decimal.is_finite() or decimal < 0: raise ValueError("MODEL_RESPONSE_INVALID")
            mapped[target] = value
        return mapped

    def _exception(self, base, error, started):
        code, status = "MODEL_RESPONSE_INVALID", getattr(error, "http_status", None)
        if isinstance(error, ValueError) and error.args and error.args[0] in {
            "MODEL_RESPONSE_TOO_LARGE", "MODEL_RESPONSE_INVALID"
        }:
            code = error.args[0]
        elif self._has_cause(error, ssl.SSLCertVerificationError):
            code = "MODEL_TLS_VERIFY_FAILED"
        elif isinstance(error, (httpx.TimeoutException, TimeoutError)):
            code = "MODEL_TIMEOUT"
        elif isinstance(error, httpx.ConnectError):
            code = "MODEL_CONNECT_FAILED"
        elif isinstance(error, httpx.HTTPError):
            code = "MODEL_PROVIDER_UNAVAILABLE"
        return self._failure(base, code, status, started)

    @staticmethod
    def _has_cause(error, kind):
        seen = set()
        while error is not None and id(error) not in seen:
            if isinstance(error, kind): return True
            seen.add(id(error))
            error = error.__cause__ or error.__context__
        return False

    @staticmethod
    def _status_error(status):
        if status in (301, 302, 303, 307, 308): return "MODEL_REDIRECT_REJECTED"
        if status in (401, 403): return "MODEL_AUTHENTICATION_FAILED"
        if status == 429: return "MODEL_RATE_LIMITED"
        if status >= 500: return "MODEL_PROVIDER_UNAVAILABLE"
        return "MODEL_REQUEST_REJECTED"

    @classmethod
    def _base(cls, request_id, purpose):
        return {"requestId": request_id, "purpose": purpose, "provider": "deepseek", "model": cls.MODEL,
                "outcome": "FAILED", "errorCode": None, "httpStatus": None, "elapsedMs": 0, "usage": None}

    @staticmethod
    def _failure(base, code, status, started):
        return base | {"outcome": "FAILED", "errorCode": code, "httpStatus": status,
                       "elapsedMs": DeepSeekAdminGateway._elapsed(started)}

    @staticmethod
    def _elapsed(started):
        return max(0, round((monotonic() - started) * 1000))
