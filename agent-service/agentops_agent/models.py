from datetime import datetime, timedelta
from hashlib import sha256
from typing import Literal
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field, AwareDatetime, model_validator

GRAPH_VERSION = "readonly-review-v2"
NODES = ["validate", "context", "retrieval", "analyze", "guard", "finalize"]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class Resume(StrictModel):
    approvalRequestId: UUID
    decision: Literal["APPROVED", "REJECTED"]
    decisionHash: str = Field(pattern=r"^[a-f0-9]{64}$")


class Cancel(StrictModel):
    pass


class Incident(StrictModel):
    id: UUID
    targetId: UUID
    status: Literal["OPEN", "ACKNOWLEDGED", "INVESTIGATING", "MONITORING", "RESOLVED", "CLOSED"]
    openedAt: AwareDatetime


class Check(StrictModel):
    runId: UUID
    outcome: Literal["UP", "DOWN", "UNKNOWN"]
    statusCode: int | None = Field(default=None, ge=100, le=599)
    latencyMs: int | None = Field(default=None, ge=0)
    completedAt: AwareDatetime
    errorCode: str | None = Field(default=None, max_length=80, pattern=r"^[A-Z0-9_]+$")


class Section(StrictModel):
    runbookId: UUID
    version: int = Field(ge=1)
    sectionId: UUID
    text: str = Field(min_length=1, max_length=6000)
    hash: str = Field(pattern=r"^[a-f0-9]{64}$")

    @model_validator(mode="after")
    def valid_hash(self):
        if sha256(self.text.encode("utf-8")).hexdigest() != self.hash:
            raise ValueError("SECTION_HASH_MISMATCH")
        return self


class Constraints(StrictModel):
    tools: list[Literal["get_check_history", "get_published_runbook"]] = Field(min_length=2, max_length=2)
    maxEvidence: Literal[20]


class Invocation(StrictModel):
    runId: UUID
    graphVersion: Literal["readonly-mock-v1", "readonly-review-v2"]
    modelName: str = Field(default="mock", min_length=1, max_length=80, pattern=r"^[A-Za-z0-9._:/-]+$")
    incident: Incident
    checks: list[Check] = Field(max_length=20)
    runbooks: list[Section] = Field(max_length=5)
    constraints: Constraints
    capturedAt: AwareDatetime

    @model_validator(mode="after")
    def authorized_window(self):
        if self.graphVersion == "readonly-mock-v1" and self.modelName != "mock":
            raise ValueError("LEGACY_GRAPH_REQUIRES_MOCK")
        if set(self.constraints.tools) != {"get_check_history", "get_published_runbook"}:
            raise ValueError("INVALID_TOOLS")
        if len({c.runId for c in self.checks}) != len(self.checks) or len({s.sectionId for s in self.runbooks}) != len(self.runbooks):
            raise ValueError("DUPLICATE_EVIDENCE")
        for check in self.checks:
            if not self.capturedAt - timedelta(minutes=30) <= check.completedAt <= self.capturedAt:
                raise ValueError("EVIDENCE_WINDOW")
        return self


class Hypothesis(StrictModel):
    text: str = Field(min_length=1, max_length=2000)
    confidence: float = Field(ge=0, le=1)
    evidenceIds: list[str] = Field(min_length=1, max_length=25)


class NextStep(StrictModel):
    text: str = Field(min_length=1, max_length=2000)
    risk: Literal["LOW", "MEDIUM", "HIGH"]
    requiresHumanReview: Literal[True]


class Citation(StrictModel):
    kind: Literal["CHECK_RUN", "RUNBOOK_SECTION"]
    id: UUID
    time: AwareDatetime | None = None
    version: int | None = Field(default=None, ge=1)
    hash: str | None = Field(default=None, pattern=r"^[a-f0-9]{64}$")


class Result(StrictModel):
    runId: UUID
    status: Literal["COMPLETED"]
    graphVersion: Literal["readonly-mock-v1", "readonly-review-v2"]
    modelName: str = Field(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9._:/-]+$")
    summary: str = Field(min_length=1, max_length=4000)
    hypotheses: list[Hypothesis] = Field(max_length=10)
    evidenceIds: list[str] = Field(max_length=25)
    nextSteps: list[NextStep] = Field(max_length=10)
    citations: list[Citation] = Field(max_length=25)
    uncertainties: list[str] = Field(min_length=1, max_length=10)
    steps: list[Literal["validate", "context", "retrieval", "analyze", "guard", "finalize"]] = Field(min_length=6, max_length=6)
