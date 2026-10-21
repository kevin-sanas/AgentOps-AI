from uuid import uuid4
import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command
from test_graph import payload


def review_payload():
    body = payload(); body["graphVersion"] = "readonly-review-v2"; body["modelName"] = "mock"
    return body


def test_review_interrupt_requires_human_and_resume_never_reanalyzes():
    from agentops_agent.graph import build_graph
    saver = InMemorySaver(); graph = build_graph(saver); body = review_payload()
    config = {"configurable": {"thread_id": body["runId"]}}
    first = graph.invoke({"input": body}, config)
    assert first["__interrupt__"]
    assert graph.get_state(config).next == ("review",)
    saved = first["result"]
    restarted = build_graph(saver)
    resumed = restarted.invoke(Command(resume={"decision": "APPROVED"}), config)
    assert resumed["reviewDecision"] == "APPROVED"
    assert resumed["result"] == saved
    assert not restarted.get_state(config).next


def test_rejection_stops_before_finalize_and_leaves_original_evidence():
    from agentops_agent.graph import build_graph
    graph = build_graph(InMemorySaver()); body = review_payload()
    config = {"configurable": {"thread_id": body["runId"]}}
    first = graph.invoke({"input": body}, config)
    rejected = graph.invoke(Command(resume={"decision": "REJECTED"}), config)
    assert rejected["reviewDecision"] == "REJECTED"
    assert rejected["result"] == first["result"]
    assert not rejected.get("finalized", False)


def test_high_risk_cannot_disguise_itself_as_low_or_skip_review():
    from agentops_agent.graph import validate_result
    from agentops_agent.models import Invocation
    from agentops_agent.graph import build_graph
    body = payload(); config = {"configurable": {"thread_id": body["runId"]}}
    report = build_graph(InMemorySaver()).invoke({"input": body}, config)["result"]
    report["nextSteps"][0]["text"] = "delete production database"
    with pytest.raises(ValueError): validate_result(report, Invocation.model_validate(body))
