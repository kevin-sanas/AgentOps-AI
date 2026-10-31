# Offline diagnosis quality evaluation implementation plan

**Goal:** Prepare 30 original synthetic diagnosis inputs and offline, human-reviewed scoring without any paid request.

**Architecture:** Preserve the existing 30 adversarial contract tests. Add a separate valid-input quality dataset, a Python export/score CLI using the existing Invocation and output guards, and draft human-review sheets bound to input/report hashes. No provider, database, browser or runtime changes.

**Tech Stack:** Existing Python3.13, Pydantic, pytest; no new dependencies.

**Spec:** User approved the next-stage outline in chat; docs/10-Agent设计与评测.md evaluation targets. Native implementation in this session; separate final read-only review.

## Constraints and review focus

- No DeepSeek requests, Key reading, proxy changes or automatic retry. Bulk calls need separate scope/budget approval.
- All 30 quality cases must be valid Invocation inputs with synthetic evidence, expected observations, permitted hypotheses and unknowns. Annotations start pending human review.
- Never present mock outputs, structural guard acceptance or a partial sample as real-model accuracy. Human judgments cannot be inferred by keyword matching.
- Bind reviews to exact input/report hashes. Missing/duplicate/foreign cases and stale judgments must not inflate scores.
- Preserve old assertions and user services/data. Update the existing guide, README and model/test docs; at most one new plan document.

## Task 1: Dataset and preparation

Files: agent-service/evaluation/scenarios.json, agent-service/quality_evaluation.py, agent-service/evaluate.py, agent-service/tests/test_quality_evaluation.py.

Interfaces: build_dataset(captured_at=None) -> dict; canonical_hash(value) -> str; prepare(output: Path) -> None. Export dataset.json and reviews.json, with frozen valid invocations, expectations and pending human labels. Refuse overwriting an existing directory.

- [x] Add tests for 30 distinct reproducible valid inputs, synthetic-only mode, varied observations, pending labels and no-overwrite export; observe RED.
- [x] Add the dataset and implementation, preserving all old fixtures; verify GREEN.

## Task 2: Offline scoring and integration

Interfaces: score(dataset: dict, responses: dict, reviews: dict) -> dict. Responses bind scenarioId/inputHash/report, optional model usage. Reviews require explicit annotation approval/reviewer and report hash plus human quality judgments. CLI score reads explicit files and writes aggregate safe results only.

- [x] Add tests for complete/partial/unreviewed samples, foreign/duplicate cases, stale hashes, forged citations, missing human judgments, safety failures and no networking; observe RED.
- [x] Implement structural validation plus human-label precision, at-least-one relevant evidence recall, fact/inference score, uncertainty and unsafe recommendation measures. No aggregate PASS without all 30 covered/reviewed and targets met; actual tool execution remains unmeasured.
- [x] Add the new tests to scripts/agent.ps1 and CI; run focused/full Python regression and a real offline CLI export/score. No business/runtime changes require a Java/frontend rerun.
- [x] Verify pushed 6518146 CI separately; perform independent review, sync startup/README/model/testing docs and save local feature commit. No main merge or paid evaluation.

## Ledger

- Baseline: 6518146 pushed to codex/deepseek-live-workbench; CI40086656738 running. Work proceeds on codex/deepseek-quality-prep.
- Ruling: authored labels remain DRAFT, not human ground truth; this round exports review sheets rather than claiming human annotation is complete. Cost if wrong: user must correct draft expectations before any meaningful quality result.
- Python 110 passed, including 15 new offline integrity/CLI tests. Initial missing-module RED and missing readable review export RED observed. A test-helper aliasing bug was corrected without changing existing assertions.
- The initial remote run passed verify/frontend but failed recovery fixture migrations; stderr was suppressed. Reproduced socket-only bootstrap readiness race using a fake Docker lifecycle, two new tests RED→GREEN after waiting for final TCP and retaining migration diagnostics. Actual root cause of the old hidden database error remains unproven; new remote confirmation is required.
- Node 12 passed in authorized environment; initial restricted-loopback EACCES was an environment failure. Python restricted run hung in existing async tests, stopped only that run; authorized rerun passed. No model calls.
- Independent whole-change review: no blocking/important findings, diff check passed. No deferred findings or extra model calls. New remote CI still pending.
- Source commit6102190 pushed. Remote CI40087951346 verify/frontend/compose all success; Python110, Java130 with no failures/errors/skips. Actual isolated custom dump/restore into two new databases passed. No paid evaluation, main merge or user service changes.
