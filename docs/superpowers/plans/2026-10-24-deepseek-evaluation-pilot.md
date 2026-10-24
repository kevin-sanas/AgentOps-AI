# Controlled evaluation pilot and human review workflow

Goal: make the next evaluation stage runnable with a bounded five-case live pilot and an offline review package; no paid request before separate human scope/budget approval.

Approved scope: user asked to continue development and will accept the result. Existing30 labels remain drafts; this is not approval of human annotations or API spending. Reuse existing Python, OneRequestProvider, Invocation and result guards. No new dependencies, migrations, business UI changes or runtime service restarts.

## Tasks

- [x] TDD: add plan/run pilot tests. The plan accepts only the unmodified original synthetic dataset, selects at most5 distinct known cases, reservesUS$0.01 per request within maxUS$0.05 and binds dataset/selection/budget with a plan hash. Dry-run planning needs no Key. Run requires explicit hash approval, local hidden Key and an exclusive persistent started marker; never resume/retry a used directory. Stop on provider/guard failure, missingusage or insufficient remaining reserve. Preserve safe actual usage and accepted reports, not Key or raw errors. Default cases: http-503, request-timeout, flapping, no-evidence, injected-instructions.
- [x] TDD: add offline review-package and CSV import tests. Bind report hashes automatically, preserve unchanged human judgments, reset changed-report judgments, keep draft annotation state. Output readable report excerpts as literal text and a UTF8 CSV. Import validates identities/hashes/explicit human values; no automatic annotation approval or quality rating. Malformed/rejected outputs must not leak secrets or become scores.
- [x] Add focused tests to Python/CI, run full Python and real offline plan/review/import/score acceptance with clearly marked fixture reports, independent review; sync guide/README/model/testing docs and local Git commit. Existing mock/provider/contracts and user data remain unchanged.
- [x] After artifacts/preflight are concrete, request separate approval for at most5 official direct DeepSeek requests, no retry, total budgetUS$0.05, synthetic evidence only. If not approved/Key not entered, deliver runnable offline acceptance and leave live status unrun. Never infer paid approval or human labels from “continue development”.

## Review focus

Exactly one run per plan directory across processes; failure cannot restart a paid batch. Dataset tampering must be caught before provider creation. Missing usage cannot be treated as free. Unreviewed/partial pilot cannot yield30-casePASS. CSV cannot carry old hash judgments or unsafe spreadsheet formulas. Default production factories and model proxy/TLS settings stay unchanged.

## Ledger

Base21ee229, branchcodex/deepseek-evaluation-pilot. Prices checked from official pricing page on2026-10-24: peak uncached inputUS$0.30/M, outputUS$1.20/M; reservation is conservative estimated accounting, not a provider invoice or account-level spending limit.

Final verification: Python137 passed (27 new), offline CLI prepare/plan/review/import/score passed with explicitly simulated3 reports and zero human judgments. Independent read-only review found no blocking/important issues. Additional empty-dataset and CSV-label-type defects were reproduced RED and fixed; final full suite green. No actual model requests, no draft annotations promoted. Live approval is pending.

User separately approved the prepared five-case plan, max5 requests and totalUS$0.05, no automatic retry. Exact run command supplied for hidden local Key entry. Last read found no started marker or receipt: live requests remain0; waiting for user execution, not inferred as completed.
