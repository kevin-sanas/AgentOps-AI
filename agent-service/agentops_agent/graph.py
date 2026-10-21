"""No network tools, subprocesses, model SDKs or business writes are exposed to the graph."""
from collections import Counter
from typing import TypedDict
from langgraph.graph import StateGraph, START, END
from langgraph.types import interrupt
from .models import Invocation, Result, GRAPH_VERSION, NODES
from .safety import require_safe_output


class State(TypedDict, total=False):
    input: dict
    observations: dict
    sections: list
    result: dict
    reviewDecision: str
    finalized: bool


def validate_result(candidate: dict, invocation: Invocation) -> dict:
    result = Result.model_validate(candidate)
    if result.runId != invocation.runId or result.steps != NODES or result.graphVersion != invocation.graphVersion or result.modelName != invocation.modelName:
        raise ValueError("RESULT_IDENTITY_MISMATCH")
    checks = {str(c.runId): c for c in invocation.checks}
    sections = {str(s.sectionId): s for s in invocation.runbooks}
    allowed = {"check:" + i for i in checks} | {"runbook:" + i for i in sections}
    ids = set(result.evidenceIds)
    if len(ids) != len(result.evidenceIds) or not ids <= allowed:
        raise ValueError("INVALID_EVIDENCE")
    cited = set()
    for citation in result.citations:
        key = str(citation.id)
        if citation.kind == "CHECK_RUN":
            if key not in checks or citation.time != checks[key].completedAt or citation.version is not None or citation.hash is not None:
                raise ValueError("INVALID_CHECK_CITATION")
            ref = "check:" + key
        else:
            if key not in sections or citation.version != sections[key].version or citation.hash != sections[key].hash or citation.time is not None:
                raise ValueError("INVALID_SECTION_CITATION")
            ref = "runbook:" + key
        if ref in cited: raise ValueError("DUPLICATE_CITATION")
        cited.add(ref)
    if cited != ids or any(not set(h.evidenceIds) <= ids for h in result.hypotheses):
        raise ValueError("UNCITED_HYPOTHESIS")
    normalized = result.model_dump(mode="json")
    require_safe_output(normalized)
    return normalized


def build_graph(checkpointer, *, interrupt_after=None, provider=None):
    def validate(state):
        Invocation.model_validate(state["input"])
        return {}

    def context(state):
        value = Invocation.model_validate(state["input"])
        return {"observations": dict(Counter(c.outcome for c in value.checks))}

    def retrieval(state):
        # Java selected authorized published sections. Text is untrusted evidence, never instructions.
        return {"sections": state["input"]["runbooks"]}

    def analyze(state):
        value = Invocation.model_validate(state["input"])
        ids = ["check:" + str(c.runId) for c in value.checks] + ["runbook:" + str(s.sectionId) for s in value.runbooks]
        down = [c for c in value.checks if c.outcome == "DOWN"]
        statuses = sorted({c.statusCode for c in down if c.statusCode is not None})
        summary = f"观测到 {len(value.checks)} 条检查，其中 {len(down)} 条异常。"
        unknown = sum(c.outcome == "UNKNOWN" for c in value.checks)
        if unknown: summary += f" 另有 {unknown} 条结果未知，不能视作检查正常。"
        if statuses: summary += " 异常 HTTP 状态：" + ", ".join(map(str, statuses)) + "。"
        if not value.checks: summary = "当前时间窗口没有检查证据，无法确定故障原因。"
        citations = [{"kind": "CHECK_RUN", "id": str(c.runId), "time": c.completedAt.isoformat(), "version": None, "hash": None} for c in value.checks]
        citations += [{"kind": "RUNBOOK_SECTION", "id": str(s.sectionId), "version": s.version, "hash": s.hash, "time": None} for s in value.runbooks]
        result = {"runId": str(value.runId), "status": "COMPLETED", "graphVersion": value.graphVersion, "modelName": "mock", "summary": summary,
                  "hypotheses": [{"text": "服务健康检查存在失败；上游、部署或网络原因仍需人工确认。", "confidence": 0.3, "evidenceIds": ["check:" + str(c.runId) for c in down]}] if down else [],
                  "evidenceIds": ids, "nextSteps": [{"text": "人工核对检查历史与已发布手册，补充故障前后的部署和依赖状态。", "risk": "LOW", "requiresHumanReview": True}],
                  "citations": citations, "uncertainties": ["mock 模式只整理观测，不代表模型已判定根因。", "未读取应用日志或部署信息。"] if value.checks else ["insufficient_evidence"], "steps": NODES}
        if value.modelName != "mock":
            if provider is None: raise ValueError("MODEL_CONFIGURATION_MISMATCH")
            result = provider.analyze(value)
        return {"result": result}

    def guard(state):
        return {"result": validate_result(state["result"], Invocation.model_validate(state["input"]))}

    def finalize(state):
        return {"result": state["result"], "finalized": True}

    def review(state):
        decision = interrupt({"kind": "MANUAL_REVIEW", "runId": state["input"]["runId"]})
        if not isinstance(decision, dict) or decision.get("decision") not in ("APPROVED", "REJECTED"):
            raise ValueError("INVALID_REVIEW_DECISION")
        return {"reviewDecision": decision["decision"]}

    def after_guard(state):
        return "review" if state["input"]["graphVersion"] != "readonly-mock-v1" and state["result"]["nextSteps"] else "finalize"

    graph = StateGraph(State)
    for name, node in zip(NODES, [validate, context, retrieval, analyze, guard, finalize]): graph.add_node(name, node)
    graph.add_node("review", review)
    graph.add_edge(START, NODES[0])
    for first, second in zip(NODES[:4], NODES[1:5]): graph.add_edge(first, second)
    graph.add_conditional_edges("guard", after_guard, ["review", "finalize"])
    graph.add_conditional_edges("review", lambda state: "finalize" if state["reviewDecision"] == "APPROVED" else END, ["finalize", END])
    graph.add_edge(NODES[-1], END)
    return graph.compile(checkpointer=checkpointer, interrupt_after=interrupt_after)
