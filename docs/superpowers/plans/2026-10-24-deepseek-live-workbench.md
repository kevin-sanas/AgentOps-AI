# DeepSeek direct live workbench implementation plan

**Goal:** Validate one real DeepSeek diagnosis through the isolated Java/Python/checkpoint/UI workflow with one hidden Key entry.

**Architecture:** Keep the mock demo as the default. The explicit PowerShell `-DeepSeek` switch selects a development-only Python factory through the existing fixture; the factory uses the existing adapter, Store and guards, with a process-wide, thread-safe one-request limit. Temporary credentials and databases keep their existing two-hour/parent-process cleanup.

**Tech stack:** Existing PowerShell 5.1/7, Java21, Python3.13, Vue3 and locked dependencies; no new dependencies.

**Spec:** Approved chat plan and budget on2026-10-24; docs/10-Agent设计与评测.md; current IDEA启动与验收指南.md.

## Constraints

- Direct official HTTPS only; no proxy, TLS bypass, changes to target policies, DB migrations, roles or existing guards.
- No Key in CLI arguments, files, reports, browser or Git. Hidden local input; only owned child-process environment holds the Key.
- One outbound model request per session, including across concurrent or repeated diagnoses. No paid automatic retry. Maximum three user-authorized sessions this round, total budgetUS$0.05; uncertain outcomes stop.
- Clearly distinguish synthetic checks, real model mode, received response and accepted diagnosis. No claimed accuracy benchmark.
- Keep all existing mock assertions intact and add real-mode tests. Startup guide and README must be synchronized.

## Tasks

- [x] Add `agent-service/deepseek_demo.py`: factory and `SessionProvider`, consuming only fixture DSN/token/Key/session directory; fixed direct adapter, request limit, sanitized call metadata. Add `tests/test_deepseek_demo.py` first; verify RED then GREEN, including concurrency/failure/repeated-call limits and secret-free records.
- [x] Extend `PythonFixture` and `DemoRuntime` only under `backend/src/test`: explicit live factory/model, manifest mode, original synthetic runbook and active fault; preserve default mock. Add focused mode-selection integration coverage.
- [x] Extend `scripts/demo.ps1 -DeepSeek`: hidden Key prompt, child-only environment transfer/restoration, explicit prohibition of `-Check` in live mode. Keep BOM and normal mock entry unchanged. Pass public model mode through demo-vite and DemoNotice.
- [x] Run Python, Java, frontend, target/session/report regressions and build; independent review. No automatic real-model requests in tests or CI.
- [x] Have user launch explicit live session with hidden local Key input; verify at most three authorized sessions via real UI, accepted frozen report, human decision, manual report and recovery. Preserve actual metrics and all failures. Leave usable session only when requested/needed for user acceptance. First session passed with exactly one request; no additional session needed.
- [x] Update guide, README, model/testing docs and execution log; save a separate local commit. No main merge, production deployment, real-system repair or quality benchmark.

## Risk and rollback

Direct networking or model output may fail; the single-request guard still consumes the session allowance and prevents duplicate paid attempts. A received HTTP response alone is not acceptance. Stop/restart only the owned temporary session; keep daily services untouched. Revert the feature commit to roll back code, without changing existing data or old evidence.
