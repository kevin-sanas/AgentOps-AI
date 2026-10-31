# Risk rejection diagnostics and prompt alignment

Goal: make future risk rejections locally diagnosable without retaining rejected report text, and align DeepSeek instructions with the existing text policy. User said the accepted report was viewed and asked to continue; this does not supply quality ratings or a new paid-call budget.

Base9b59ea2; branchcodex/deepseek-risk-diagnostics. Original pilot remains2 requests,1 accepted/1 rejected; raw rejected text is absent, so its specific wording and whether matching was conservative remain unknown.

## Scope

- Preserve the exact existing RISKY regex, risk decisions, secret filter, error code and ValueError compatibility. No automatic HIGH correction, relaxed guard, changed old assertion, API schema or tool execution.
- Add a fixed-code ValueError subtype carrying only bounded suggestion index, whitelisted observed/required risk and operation category. No text, arbitrary exception values, Key, hashes/excerpts of rejected text or host names.
- Let existing validation_details export safe fields to the one-shot check, demo and pilot receipts. Old plain errors remain code-only; never fabricate fields for historical calls.
- DeepSeek prompt includes the current risk text pattern and explains negated/historical/hypothetical mentions; matching nextSteps text requiresHIGH or a genuinely read-only rephrasing without those terms. HIGH still never authorizes execution. Keep request/response/time/token/proxy/TLS limits.

## Tasks

- [x] Write tests first for index/category/value diagnostics, secret filtering precedence, unchanged HIGH/LOW and conservative historical/negative decisions, legacy errors, one-request simulated receipts and pilot stop behavior; observe RED.
- [x] Add minimal diagnostics and prompt changes; full Python regression including actual adapter MockTransport payload limit; targeted Java/Python persistence/HTTP regression if applicable. No real provider calls.
- [x] Generate a clearly synthetic diagnostic preview, independent read-only review, sync guide/README/model/test/execution docs; local Git commit, no push/merge or real-model revalidation claim.

## Evidence and limits

The observable defect is that safe receipts currently retain only the risk error code. A prompt/rule semantic mismatch is visible in source and reproducible offline, but it is not proof of the historical paid rejection's exact cause. Offline prompt checks prove request content, not future model compliance. Existing partial pilot and all human labels/ratings remain unchanged.

Completed: RED9fail/1pass, Python147pass; Java targeted15pass with nestedPG12, no failures/errors/skips. RISKY/SECRETS AST matches baseline, original assertions unchanged, old pilot receipts unchanged2. Independent read-only review found no blocking/important findings. New real-model calls0; no runtime restart, automatic ratings or paid revalidation. Startup guide14.7 synchronized.
