# DeepSeek Timeout Revalidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Revalidate the updated DeepSeek risk instructions against exactly one original synthetic `request-timeout` case, with one request at most and a safe review artifact.

**Architecture:** Reuse the existing immutable 30-case dataset and one-shot pilot runner. Prepare a new single-use directory offline, bind the selected case and USD 0.01 budget into the plan hash, and require hidden local Key entry plus explicit human approval before the direct official request. Keep the previous stopped batch immutable.

**Tech Stack:** Python 3.13, httpx, Pydantic, existing Dawnwatch evaluation pilot and review-package CLIs, GitHub Actions.

**Spec:** `docs/10-Agent设计与评测.md` sections “受控试评测与人工审阅包” and “风险拒绝的脱敏定位”.

## Global Constraints

- The selected scenario is exactly `request-timeout` from the original synthetic dataset.
- At most one official DeepSeek request; no retry, resume, fallback or automatic repair.
- Direct official HTTPS with TLS verification; DeepSeek does not use Clash.
- Budget estimate is capped at USD 0.01 for this plan and is not an account-level spending limit or invoice.
- No API Key in commands, files, logs, Git, chat or browser.
- Rejected report text is discarded; only bounded risk diagnostics and usage receipts may persist.
- Human review remains pending until the user explicitly supplies ratings.

## Review Focus

- A reused or concurrently started directory must be rejected before another paid request.
- A changed dataset, selection, budget or plan hash must be rejected before provider creation.
- Missing usage, provider failure or guard rejection must stop after the first attempt.
- A guard rejection must preserve safe diagnostic fields without retaining report text.
- One accepted report must not be presented as a complete model-quality benchmark.

---

### Task 1: Establish the verified remote baseline

**Files:**
- No source changes.

**Interfaces:**
- Consumes: commit `f5dcc03` on `codex/deepseek-risk-diagnostics`.
- Produces: a GitHub branch and current CI result for the exact risk-diagnostics revision.

- [x] **Step 1: Run the complete Python regression.**

  Run: `.\scripts\agent.ps1 Test`

  Expected: 147 tests pass and no DeepSeek request is made.

- [x] **Step 2: Push only after the user confirms whether the renamed `Dawnwatch` remote replaces the originally named `AgentOps-AI` remote.**

- [x] **Step 3: Read the GitHub Actions result for the pushed revision and preserve actual pass/fail evidence.**

### Task 2: Freeze the one-case paid plan offline

**Files:**
- Create (Git-ignored): `.cache/model-evaluation/timeout-revalidation-20261025/plan.json`
- Create (Git-ignored): `.cache/model-evaluation/timeout-revalidation-20261025/dataset.json`

**Interfaces:**
- Consumes: `evaluate_live.py plan`, original synthetic dataset, scenario `request-timeout`, budget `.01`.
- Produces: immutable plan hash and a never-started one-shot directory.

- [x] **Step 1: Generate the plan without a Key or network call.**

  Run: `.\agent-service\.venv\Scripts\python.exe .\agent-service\evaluate_live.py plan --dataset .cache/model-evaluation/pilot-20261024-inputs/dataset.json --output .cache/model-evaluation/timeout-revalidation-20261025 --scenario request-timeout --budget-usd .01`

- [x] **Step 2: Verify the plan is one request, USD 0.01, no retry, original dataset-bound, and has no `started.json` or receipt.**

- [x] **Step 3: Request explicit approval for this exact paid scope before giving the hidden-Key run command.**

### Task 3: Run once and prepare human review

**Files:**
- Create (Git-ignored after user execution): safe receipt, accepted response if any, and review package under the same plan directory.
- Update after evidence exists: README progress, `IDEA启动与验收指南.md`, `docs/10-Agent设计与评测.md`, `docs/13-测试矩阵与阶段门.md`, `CODEX_EXECUTION.md`, and one formal verification JSON.

**Interfaces:**
- Consumes: the approved plan hash and a Key entered only in the user’s local interactive terminal.
- Produces: one safe outcome and, only if accepted, a report-hash-bound human review row.

- [x] **Step 1: User runs the exact one-shot command once and enters the Key through the hidden prompt.**
- [x] **Step 2: Verify request count, usage, model alias, outcome and safe risk diagnostics; never reconstruct or retain rejected text.**
- [x] **Step 3: If accepted, generate the offline review package with ratings still blank; if rejected, record only the safe receipt.**
- [x] **Step 4: Run the relevant regression and documentation checks, update the startup guide because the acceptance record changed, then commit locally.**

## Execution Ledger

- Ruling: continue in the current clean `codex/deepseek-risk-diagnostics` checkout rather than create another IDEA-visible worktree — the user asked to start from this project and the branch is already dedicated and clean — cost if wrong: isolation relies on this feature branch instead of a second checkout.
- Task 1 Step 1 complete: 147 passed in 10.09s on 2026-10-25; no model call.
- Task 1 Step 2 blocked by remote-identity safety check: current `origin` is `kevin-sanas/Dawnwatch`, while the historical user-specified repository was `kevin-sanas/AgentOps-AI`; explicit confirmation is required and no workaround is permitted.
- Task 2 Steps 1–2 complete: plan `80950de1a16b2bdfb4eeccee546c6a2d0d5e4900576d810e2a7dbc2a9dc904e9`, dataset `0626b8e0e0cbeab1e7a2b812b8ebe6a70aa60fefbd2154edab310d4f3667f650`; directory contains only `dataset.json` and `plan.json`, with no started marker, receipts, responses or credential-shaped content.
- User confirmed `kevin-sanas/Dawnwatch` as the renamed target and approved exactly one direct DeepSeek request, no retry, budget estimate up to USD 0.01.
- Task 1 Steps 2–3 complete: pushed `3fe1667581c213a031e9a35606d2964912dacfcb`; GitHub Actions run `40167443106` completed with `verify`, `frontend` and `compose` all successful.
- Task 3 Steps 1–3 complete: exactly one request, one accepted report, no retry; deepseek-flash usage 1469 input/802 output/2271 total, 5765ms, peak uncached estimate USD 0.0014031, no unknown potential charge. Offline validation and credential-shaped-content scan passed; review package binds reportHash `81d0da34ee7e88001299a1669a24af45fa80fbef3710874f03b3f0149438e587`, with zero human judgments and INCOMPLETE score.
- Final review fix pass: corrected README to attribute CI only to baseline commit `3fe1667` and to describe this individual report as accepted rather than the scenario as stably remediated; clarified the root-level `score.json` path in the startup guide. Human prose changes require link/path checks rather than a new behavior test.
- Task 3 Step 4 complete before commit: local Python 147/147 and baseline CI three jobs passed; live receipt/report hashes revalidated, formal JSON assertions and referenced paths passed, review findings absent, `git diff --check` and changed-file credential scan passed. Runtime code was unchanged after those regressions.
