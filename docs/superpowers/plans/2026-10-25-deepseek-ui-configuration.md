# DeepSeek UI Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让管理员在 Dawnwatch 页面中安全持久化 DeepSeek Key、无推理费用地测试连接、查询余额，并追踪本系统每次模型相关请求及 Token 用量。

**Architecture:** 业务 PostgreSQL 保存 AES-256-GCM 密文和不可变调用台账，Java 后端负责权限、加密、审计和聚合，Python agent-service 继续独占 DeepSeek HTTP 适配与结构护栏。Java 只在管理员测试/余额查询或诊断 `INVOKE` 时短暂解密 Key，经受内部令牌保护的请求传给 Python；Python 禁用环境代理、保持 TLS 校验且不自动重试。正常应用重启后读取同一业务库与稳定 `SESSION_TOKEN_PEPPER`，隔离 demo 退出仍销毁临时数据。

**Tech Stack:** Java 21、Spring Boot 4.1、JDBC/Flyway、PostgreSQL 17、Python 3/httpx/FastAPI/LangGraph、Vue 3/TypeScript/Vite、Node test runner。

**Spec:** `docs/superpowers/specs/2026-10-25-deepseek-ui-configuration-design.md`

## Global Constraints

- DeepSeek 固定使用官方 `https://api.deepseek.com`、`deepseek-flash`、TLS 校验开启、`trust_env=False`、不跟随重定向、无自动付费重试。
- Key 不得进入 Git、日志、审计 metadata、URL、前端持久存储、outbox、业务诊断输入、checkpoint 结果或 `.cache`。
- 只有 `OPS_ADMIN` 可查看脱敏配置状态、测试、查询余额、保存、删除和查看用量。
- 连接测试使用 `GET /models`，余额使用 `GET /user/balance`；两者无推理 Token，最多一次 HTTP 请求。
- 用量只代表本 Dawnwatch 实例；失败前未获得完整响应时 Token 必须为未知而不是零。
- DeepSeek 凭据不得通过非回环明文 HTTP 发送；远程 agent-service 必须使用 HTTPS。
- 默认 mock 自动化和既有人工审批、只读诊断、风险护栏保持有效。
- 启动、环境变量、迁移、模型配置与验收变化必须同步 `IDEA启动与验收指南.md` 和 README。

## Review Focus

- `SESSION_TOKEN_PEPPER` 变化或密文/nonce 被篡改时，配置读取应返回安全错误且不得覆盖密文；Task 1 加入篡改和错误 pepper 测试。
- 管理员测试一个新 Key 失败时，已保存的旧 Key 必须仍可使用；Task 3 加入候选 Key 不落库测试。
- Java→Python 响应丢失时，诊断调用不得自动再次收费且 Token 显示未知；Task 4 加入结果不确定与 checkpoint 对账测试。
- 同一诊断的 outbox 重复交付不得重复计数或重复请求模型；Task 4 加入 runId 唯一台账和 reconcile 测试。
- 前端刷新余额失败后不得继续把旧值展示为当前余额；Task 5 加入状态归约测试。

---

### Task 1: 加密配置与调用台账数据库边界

**Files:**
- Create: `db/migration/V7__model_configuration_and_usage.sql`
- Create: `backend/src/main/java/dev/agentops/model/ModelSecretCipher.java`
- Create: `backend/src/main/java/dev/agentops/model/ModelConfigurationStore.java`
- Create: `backend/src/main/java/dev/agentops/model/ModelUsageStore.java`
- Create: `backend/src/test/java/dev/agentops/ModelConfigurationStoreTest.java`

**Interfaces:**
- Produces: `ModelSecretCipher.encrypt(String)->EncryptedSecret` and `decrypt(EncryptedSecret)->String`; `ModelConfigurationStore.status()`, `credential()`, `save(...)`, `delete(...)`; `ModelUsageStore.begin(...)`, `complete(...)`, `summary(...)`, `list(...)`.
- Database: singleton `model_provider_config`; immutable-by-ID `model_provider_request` with purpose `DIAGNOSIS|CONNECTION_TEST|BALANCE_QUERY`, optional `agent_run_id`/`incident_id`/`actor_id`, result and nullable token columns.

- [ ] **Step 1: Write failing persistence and crypto tests** covering AES-GCM round trip, random nonce, different ciphertext for the same Key, wrong pepper/tampered ciphertext rejection, Key absence from `toString`, singleton version conflict, and request ledger nullable usage.
- [ ] **Step 2: Run `./scripts/backend.ps1 '-Dtest=ModelConfigurationStoreTest' test`** and verify failure because V7/classes do not exist.
- [ ] **Step 3: Add V7 migration** with constraints, foreign keys, time indexes, one configuration row, non-negative token counts, and diagnosis run uniqueness.
- [ ] **Step 4: Implement `ModelSecretCipher`** using a SHA-256 domain-separated key derived from `SecuritySettings.pepper()`, AES/GCM/NoPadding, 12-byte `SecureRandom` nonce, and fixed provider/model/row-ID AAD.
- [ ] **Step 5: Implement the two stores** with records that redact secrets in `toString`, optimistic version checks, server-side pagination and allowed time windows.
- [ ] **Step 6: Re-run the targeted test** and expect PASS.
- [ ] **Step 7: Commit** `feat: persist encrypted model configuration and usage ledger`.

### Task 2: Python 管理型 DeepSeek 网关

**Files:**
- Create: `agent-service/agentops_agent/provider_admin.py`
- Create: `agent-service/tests/test_provider_admin.py`
- Modify: `agent-service/agentops_agent/provider.py`
- Modify: `agent-service/agentops_agent/app.py`
- Modify: `agent-service/agentops_agent/store.py`
- Modify: `agent-service/agentops_agent/model_calls.py`
- Modify: `agent-service/tests/test_provider.py`
- Modify: `agent-service/tests/test_postgres.py`

**Interfaces:**
- Produces: internal `POST /internal/v1/providers/deepseek/test`, `GET /internal/v1/providers/deepseek/balance`, and per-`INVOKE` credential header `X-Dawnwatch-Model-Credential`.
- Produces safe envelopes `{requestId,purpose,outcome,errorCode,httpStatus,elapsedMs,model,usage}`; balance adds `isAvailable` and validated currency rows.
- `Store.invoke(body, provider=None)` accepts an invocation-scoped provider; `PersistedProvider` checkpoints safe request outcome/usage without storing credentials.

- [ ] **Step 1: Write failing httpx MockTransport tests** for `/models` success/model missing/401/429/5xx/TLS/timeout/redirect/oversize and `/user/balance` multi-currency/insufficient/invalid decimals/oversize; assert one request and `trust_env=False` path.
- [ ] **Step 2: Write failing app/store tests** proving missing/oversize credential rejection, credential not present in response/checkpoint, per-invoke provider selection, official usage capture, and repeat invoke reuses checkpoint without a second external request.
- [ ] **Step 3: Run `./scripts/agent.ps1 Test`** and verify the new tests fail for missing endpoints/interfaces.
- [ ] **Step 4: Implement `provider_admin.py`** with bounded one-request GET clients, 10-second admin timeout, 64 KiB response limit, Decimal validation and stable safe error mapping.
- [ ] **Step 5: Extend provider metadata capture** so accepted chat responses expose official `prompt_tokens`, `completion_tokens`, `total_tokens`, cache hit/miss and returned model without placing metadata in the human diagnosis report.
- [ ] **Step 6: Extend app/store/model journal** for managed credentials, exact request attempt state, safe error response, and at-most-one model call per run.
- [ ] **Step 7: Re-run `./scripts/agent.ps1 Test`** and expect the full Python suite PASS.
- [ ] **Step 8: Commit** `feat: add managed DeepSeek provider gateway`.

### Task 3: 管理员配置、连接测试与余额 API

**Files:**
- Create: `backend/src/main/java/dev/agentops/model/ModelProviderClient.java`
- Create: `backend/src/main/java/dev/agentops/model/ModelConfigurationService.java`
- Create: `backend/src/main/java/dev/agentops/model/ModelConfigurationController.java`
- Create: `backend/src/main/java/dev/agentops/model/ModelUsageController.java`
- Create: `backend/src/test/java/dev/agentops/ModelConfigurationApiTest.java`
- Create: `backend/src/test/java/dev/agentops/ModelUsageApiTest.java`
- Modify: `backend/src/main/java/dev/agentops/agent/AgentClient.java`
- Modify: `backend/src/main/java/dev/agentops/shared/ApiExceptionHandler.java`

**Interfaces:**
- Produces admin endpoints `GET|PUT|DELETE /api/v1/admin/model-config`, `POST /api/v1/admin/model-config/test`, `GET /api/v1/admin/model-config/balance`.
- Produces admin endpoints `GET /api/v1/admin/model-usage/summary` and `GET /api/v1/admin/model-usage/requests` with bounded date ranges and pagination.
- `ModelProviderClient.test(String key, String requestId)` and `balance(String key, String requestId)` call Task 2; no candidate credential is persisted.
- Status response contains `configured,provider,model,keySuffix,version,updatedAt,updatedBy,lastTest` and never contains encrypted/plain Key.

- [ ] **Step 1: Write failing API tests** for unauthenticated/observer/operator rejection, admin redacted status, save/update/version conflict/delete, invalid length, candidate test preserving old config, saved-key test, balance no-cache response, usage time-range/pagination/role isolation, audit without secrets, and audit rollback.
- [ ] **Step 2: Write failing client tests** proving credentials are only sent to loopback HTTP or HTTPS, never on GET/reconcile/resume/cancel, body/error size limits and stable TLS/auth/rate/balance mappings.
- [ ] **Step 3: Run `./scripts/backend.ps1 '-Dtest=ModelConfigurationApiTest,AgentClientTest' test`** and verify expected failures.
- [ ] **Step 4: Implement configuration and usage controllers/service/client** with `OPS_ADMIN`, bean validation, bounded date ranges, idempotency/version checks, credential zeroing-by-scope, `Cache-Control: no-store`, and metadata-only audit.
- [ ] **Step 5: Add localized stable API errors** for required/invalid credential, insufficient balance, TLS, timeout, rate limit, provider unavailable, invalid response and version conflict.
- [ ] **Step 6: Re-run targeted Java tests** and expect PASS.
- [ ] **Step 7: Commit** `feat: expose secure model administration APIs`.

### Task 4: 动态诊断、错误可见性与精确用量对账

**Files:**
- Modify: `backend/src/main/java/dev/agentops/agent/AgentService.java`
- Modify: `backend/src/main/java/dev/agentops/agent/AgentClient.java`
- Modify: `backend/src/main/java/dev/agentops/agent/AgentDispatcher.java`
- Modify: `backend/src/main/java/dev/agentops/agent/AgentOutbox.java`
- Modify: `backend/src/main/java/dev/agentops/agent/AgentContracts.java`
- Create: `backend/src/test/java/dev/agentops/ManagedDeepSeekIntegrationTest.java`
- Modify: `backend/src/test/java/dev/agentops/AgentApiTest.java`
- Modify: `backend/src/test/java/dev/agentops/AgentIntegrationTest.java`

**Interfaces:**
- Consumes Task 1 `credential()`/usage stores and Task 2 safe provider metadata.
- Diagnosis snapshots `deepseek-flash` when configured, otherwise preserves explicit mock behavior; the Key is fetched only for `INVOKE` and never stored in task data.
- Produces stable run errors such as `MODEL_TLS_VERIFY_FAILED` and usage rows keyed to run/incident/actor.

- [ ] **Step 1: Write failing integration tests** for configured DeepSeek snapshot, unconfigured mock, Key absence from outbox/checkpoint/audit/API, exact usage persistence, source associations, safe TLS error on run page, and no credential on later workflow phases.
- [ ] **Step 2: Add failing retry/reconcile tests** for one external call across duplicate delivery, Java response loss, checkpoint result reuse, outcome unknown and nullable Token totals.
- [ ] **Step 3: Run the targeted integration tests** and verify failures reflect missing dynamic path/metadata.
- [ ] **Step 4: Implement dynamic model selection and dispatch credential scope**; keep existing approval and model-outcome-unknown invariants.
- [ ] **Step 5: Upsert exact usage metadata transactionally** when a safe Python envelope arrives, and map provider failure categories to run error codes without raw provider text.
- [ ] **Step 6: Re-run targeted tests**, then `./scripts/backend.ps1 verify`; expect PASS.
- [ ] **Step 7: Commit** `feat: route diagnoses through saved DeepSeek configuration`.

### Task 5: 模型配置、余额和用量前端

**Files:**
- Create: `frontend/src/pages/ModelSettingsPage.vue`
- Create: `frontend/src/model-admin.ts`
- Create: `frontend/tests/model-admin.test.mjs`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/types.ts`
- Modify: `frontend/src/router.ts`
- Modify: `frontend/src/components/SidebarNav.vue`
- Modify: `frontend/src/components/AppIcon.vue`
- Modify: `frontend/src/styles.css`
- Modify: `frontend/tests/navigation.test.mjs`

**Interfaces:**
- Consumes Task 3 admin APIs and Task 4 usage/run associations.
- `model-admin.ts` produces pure format/state helpers for balance, usage, stale-result clearing and error presentation.

- [ ] **Step 1: Write failing pure tests** for admin-only route/navigation metadata, Key redaction, clearing password after save, stale balance removal on refresh failure, unknown-vs-zero Token display, multi-currency rows and usage source links.
- [ ] **Step 2: Run `npm --prefix frontend test`** and verify failures for missing helpers/navigation.
- [ ] **Step 3: Implement the page** with password input, explicit test/save/delete controls, status text, no-color-only states, balance refresh, time filters, summary and paginated request table.
- [ ] **Step 4: Add route and “模型配置” under the AI diagnosis sidebar group for `OPS_ADMIN` only**, including keyboard focus, loading/empty/error states and narrow layout.
- [ ] **Step 5: Re-run `npm --prefix frontend test` and `npm --prefix frontend run build`**; expect PASS.
- [ ] **Step 6: Commit** `feat: add model configuration and usage workspace`.

### Task 6: 启动方式、隔离演示和用户文档

**Files:**
- Modify: `scripts/agent.ps1`
- Modify: `scripts/demo.ps1`
- Modify: `scripts/demo-live.test.mjs`
- Modify: `backend/src/test/java/dev/agentops/DemoRuntime.java`
- Modify: `backend/src/test/java/dev/agentops/PythonFixture.java`
- Modify: `compose.yaml`
- Modify: `.env.example`
- Modify: `README.md`
- Modify: `IDEA启动与验收指南.md`

**Interfaces:**
- Normal startup requires checkpoint DSN/internal token but no `AGENT_LLM_API_KEY`; page configuration activates DeepSeek.
- Isolated demo prints temporary login credentials, accepts page-entered Key, enforces the existing one-inference-call session limit, and deletes its config with the temporary database on exit.

- [ ] **Step 1: Write failing script/demo tests** proving `-DeepSeek -NoInput` no longer prompts for Key, page-managed mode starts, credentials still print, one inference call remains enforced, and cleanup removes temporary config.
- [ ] **Step 2: Run the targeted Node/Java demo tests** and verify expected failures.
- [ ] **Step 3: Update scripts, fixture and Compose** to start managed provider mode without model Key environment variables; retain mock mode and direct/no-proxy behavior.
- [ ] **Step 4: Update README and the dated IDEA guide** with V7, page configuration, connection test, balance, usage semantics, normal restart persistence, demo exception, TLS troubleshooting and acceptance steps.
- [ ] **Step 5: Run script parsing/tests and documentation link/search checks**; expect PASS and no real model request.
- [ ] **Step 6: Commit** `docs: document page-managed DeepSeek setup`.

### Task 7: Full verification and handoff

**Files:**
- Modify only files needed to fix regressions found by the following checks.

**Interfaces:**
- Produces a clean, locally committed branch ready for user acceptance; no remote push without explicit authorization.

- [ ] **Step 1: Run `./scripts/agent.ps1 Test`**; expect all Python tests PASS.
- [ ] **Step 2: Run `./scripts/backend.ps1 verify`**; expect all Java/Flyway/integration tests PASS.
- [ ] **Step 3: Run `npm --prefix frontend test` and `npm --prefix frontend run build`**; expect PASS.
- [ ] **Step 4: Run the script test suite** including demo/recovery tests; expect PASS with no real provider call.
- [ ] **Step 5: Run secret scans and `git diff --check`**; expect no fixture Key outside tests, no actual secret patterns, no whitespace errors and only intended files.
- [ ] **Step 6: Review the full diff against the spec**, verify startup guide date/README progress, and commit any final corrections.
- [ ] **Step 7: Hand off exact normal-start and page acceptance steps**, explicitly noting that a user-clicked real connection/balance request was not run by automation.
