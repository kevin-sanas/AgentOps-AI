"""Text policy is an additional gate; execution tools do not exist in this service."""
import re

RISKY = re.compile(r"\b(delete|drop|truncate|restart|reboot|shutdown|kill|kubectl|chmod|sudo|rm\s+-)\b|删除|清空|重启|停机|停服|扩容|缩容|回滚|修改配置", re.I)
SECRETS = re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)|\b(?:sk-[A-Za-z0-9_-]{12,}|Bearer\s+[A-Za-z0-9._~+/-]{12,})|(?:password|passwd|api[_-]?key|access[_-]?token|secret)\s*[:=]\s*[^\s,;\"']{4,}", re.I | re.S)

RISK_POLICY_ID = 'operation-text-high-v1'
RISK_CATEGORIES = frozenset({'DELETE','RESTART','STOP','SCALE','ROLLBACK','CONFIGURATION_CHANGE','PRIVILEGED_COMMAND','OTHER'})


class RiskUnderestimated(ValueError):
    """Fixed-code error with safe metadata only; rejected text is never attached."""
    def __init__(self, step_index, risk, category):
        super().__init__('AGENT_RISK_UNDERESTIMATED')
        self.step_index = step_index if type(step_index) is int and 0 <= step_index < 10 else None
        self.observed_risk = risk if isinstance(risk,str) and risk in {'LOW','MEDIUM','HIGH'} else 'UNKNOWN'
        self.operation_category = category if isinstance(category,str) and category in RISK_CATEGORIES else 'OTHER'

    def diagnostic(self):
        return {'policyId':RISK_POLICY_ID,'stepIndex':self.step_index,'observedRisk':self.observed_risk,
                'requiredRisk':'HIGH','operationCategory':self.operation_category}


def operation_category(match):
    token = match.casefold().strip()
    groups = {
        'DELETE': {'delete','drop','truncate','删除','清空'},
        'RESTART': {'restart','reboot','重启'},
        'STOP': {'shutdown','kill','停机','停服'},
        'SCALE': {'扩容','缩容'},
        'ROLLBACK': {'回滚'},
        'CONFIGURATION_CHANGE': {'修改配置'},
        'PRIVILEGED_COMMAND': {'kubectl','chmod','sudo'},
    }
    if token.startswith('rm'): return 'DELETE'
    return next((category for category, terms in groups.items() if token in terms),'OTHER')


def redact(text: str) -> str:
    return SECRETS.sub("[REDACTED]", text)


def require_safe_output(report: dict):
    texts = [report.get("summary", ""), *report.get("uncertainties", [])]
    texts += [h.get("text", "") for h in report.get("hypotheses", [])]
    texts += [s.get("text", "") for s in report.get("nextSteps", [])]
    if any(SECRETS.search(t) for t in texts): raise ValueError("AGENT_SENSITIVE_OUTPUT")
    for index, step in enumerate(report.get("nextSteps", [])):
        match = RISKY.search(step.get("text", ""))
        if match and step.get("risk") != "HIGH":
            raise RiskUnderestimated(index, step.get("risk"), operation_category(match.group()))
