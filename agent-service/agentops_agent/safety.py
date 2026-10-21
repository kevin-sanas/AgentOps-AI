"""Text policy is an additional gate; execution tools do not exist in this service."""
import re

RISKY = re.compile(r"\b(delete|drop|truncate|restart|reboot|shutdown|kill|kubectl|chmod|sudo|rm\s+-)\b|删除|清空|重启|停机|停服|扩容|缩容|回滚|修改配置", re.I)
SECRETS = re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)|\b(?:sk-[A-Za-z0-9_-]{12,}|Bearer\s+[A-Za-z0-9._~+/-]{12,})|(?:password|passwd|api[_-]?key|access[_-]?token|secret)\s*[:=]\s*[^\s,;\"']{4,}", re.I | re.S)


def redact(text: str) -> str:
    return SECRETS.sub("[REDACTED]", text)


def require_safe_output(report: dict):
    texts = [report.get("summary", ""), *report.get("uncertainties", [])]
    texts += [h.get("text", "") for h in report.get("hypotheses", [])]
    texts += [s.get("text", "") for s in report.get("nextSteps", [])]
    if any(SECRETS.search(t) for t in texts): raise ValueError("AGENT_SENSITIVE_OUTPUT")
    for step in report.get("nextSteps", []):
        if RISKY.search(step.get("text", "")) and step.get("risk") != "HIGH":
            raise ValueError("AGENT_RISK_UNDERESTIMATED")
