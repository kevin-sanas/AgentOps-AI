# 07｜API 契约

## 规则

外部 API `/api/v1`，JSON UTF-8，bearer token，RFC 9457 风格错误，时间 ISO-8601 UTC，分页 `page/size` 且最大 100。创建操作带 `Idempotency-Key`；事故修改带版本号/ETag。Agent 内部 API `/internal/v1` 仅服务网络访问，使用服务身份凭据和 request signature/token。

## 外部端点

| 方法/路径 | 权限 | 结果 |
|---|---|---|
| `POST /auth/login`, `GET /me`, `POST /auth/logout` | 匿名/登录 | 登录、当前用户和撤销当前 session |
| `PUT /admin/users/{userId}/roles` | OPS_ADMIN | 覆盖明确角色列表；变更需审计 |
| `GET /targets` / `POST /targets` | OBSERVER / OPS_ADMIN | 仅返回授权目标；管理员登记目标 |
| `PUT /targets/{id}/access/{userId}` | OPS_ADMIN | `{canRead,canOperate,reason}`；`canOperate` 必须同时 `canRead=true` |
| `PUT /targets/{id}/network-policy` | OPS_ADMIN | 提交逐目标 `allowedPrivateCidrs` 和理由；产生新版本与审计；metadata/loopback/link-local 永不允许 |
| `POST /targets/{id}/checks` | OPS_ADMIN | 新建检查定义 |
| `GET /targets/{id}/checks/history` | 目标可读 | 最近结果摘要，不返回请求秘密/响应正文 |
| `GET /incidents` / `GET /incidents/{id}` | 事故可读 | 列表和带权限过滤时间线 |
| `POST /incidents/{id}/transitions` | ON_CALL | ACK/INVESTIGATE/RESOLVE/CLOSE，带版本与原因 |
| `POST /incidents/{id}/agent-runs` | ON_CALL | `202 {runId,status:"QUEUED"}`；必须幂等 |
| `GET /agent-runs/{id}` | 发起者/ON_CALL/AUDITOR | 阶段、结构化结论、证据引用 |
| `POST /agent-runs/{id}/approval` | ON_CALL | `{decision:"APPROVED"|"REJECTED",reason}`；批准只进入人工处理阶段，不执行动作 |
| `POST /agent-runs/{id}/manual-action-report` | ON_CALL | `{outcome:"EXECUTED"|"NOT_EXECUTED"|"FAILED",note}`；记录实际由人在线下完成的结果 |
| `POST /targets/{id}/runbooks` / `POST .../publish` | OPS_ADMIN | 创建草稿/发布不可变版本和 section UUID |
| `GET /runbooks/{id}/versions/{version}` | 目标可读 | 返回稳定 section ID 与内容 |
| `POST /integrations/uptime-kuma` | OPS_ADMIN | 配置 base URL、target mapping、secret ref，不回显 token |
| `POST /integrations/uptime-kuma/{id}/test` | OPS_ADMIN | 对预建 Push Monitor 做一次脱敏 smoke push |
| `GET /integrations/uptime-kuma?targetId={id}` | OPS_ADMIN | 查询该目标映射，不返回secret reference/token |
| `PUT /integrations/uptime-kuma/{id}` | OPS_ADMIN | version + enabled + reason；smoke成功且部署启用后才能启用 |
| `GET /integrations/uptime-kuma/{id}/deliveries?page=0&size=20` | OPS_ADMIN | 分页投递状态/次数/HTTP码/稳定错误码，不包含请求URL或远端正文 |

G5 Kuma创建请求为 `{targetId,baseUrl,secretRef,reason}`，201返回默认停用的 `{id,targetId,baseUrl,enabled,smokePassed,version}`；同目标重复映射409。`secretRef`仅接受`env:KUMA_PUSH_TOKEN_*`且不回显。测试请求`{reason}`返回202和`{deliveryId,state:"PENDING"}`，不会在API事务内发送HTTP；成功smoke前启用返回409。状态修改`{version,enabled,reason}`，旧版本409，禁用使旧租约失效。错误码包括`KUMA_DISABLED/KUMA_SMOKE_REQUIRED/KUMA_ORIGIN_BLOCKED/KUMA_SECRET_REFERENCE_INVALID/KUMA_VERSION_CONFLICT`。

## Agent 内部 DTO

```json
{
  "runId":"a27842ad-9843-4805-963f-9f26dd036532",
  "incident":{"id":"…","targetId":"…","status":"INVESTIGATING","openedAt":"2026-10-20T02:00:00Z"},
  "checks":[{"runId":"…","outcome":"DOWN","statusCode":503,"latencyMs":120,"completedAt":"…","errorCode":null}],
  "runbooks":[{"runbookId":"…","version":2,"sectionId":"…","text":"先比较最近部署与上游健康状态……"}],
  "constraints":{"tools":["get_check_history","get_published_runbook"],"maxEvidence":20}
}
```

```json
{
  "runId":"a27842ad-9843-4805-963f-9f26dd036532",
  "status":"COMPLETED",
  "summary":"目标返回 503；连续 3 次失败均来自同一服务目标。",
  "hypotheses":[{"text":"上游依赖未就绪","confidence":0.68,"evidenceIds":["check:…","runbook:…"]}],
  "nextSteps":[{"text":"按手册核对部署版本与上游健康状态","risk":"LOW","requiresHumanReview":true}],
  "citations":[{"kind":"CHECK_RUN","id":"…","time":"…"},{"kind":"RUNBOOK_SECTION","id":"…","version":2}]
}
```

Python 输出经 Java schema/引用集合校验后才保存。AI 不能自选用户或资源 ID；内部 DTO 来自 Java 已授权查询。

## Agent 内部 HTTP 契约

所有路径仅在 Compose/服务网内开放。每个请求使用 `Authorization: Bearer <AGENT_INTERNAL_TOKEN>`、`X-Request-Id` 和 JSON；token 仅 Java/Python 服务端可读，比较常量时间。Java 的 AgentRun/Approval 表是业务事实来源；Python checkpoint 保存图的可恢复状态。Python 不主动回调 Java，Java 根据 runId 查询并写入终态。首次调用在 HTTP 请求内同步运行完整图，模型节点超时 25 秒、整次图预算 45 秒、HTTP 客户端超时 60 秒；不会使用易丢失的进程内 fire-and-forget 任务。进程崩溃后 Java 重试同一 runId，图从最后已提交 checkpoint 恢复；节点没有业务写副作用。

| 方法/路径 | 语义 | 成功响应 |
|---|---|---|
| `POST /internal/v1/runs/{runId}/invoke` | 创建/恢复同一 run；按 runId 幂等。持有数据库 advisory lock；重复并发调用返回当前状态 | `200 {runId,status:"WAITING_HUMAN"|"COMPLETED"|"FAILED",result?}`；重复并发调用返回 `202 {runId,status:"RUNNING"}` |
| `GET /internal/v1/runs/{runId}` | 查 checkpoint/当前图状态，不返回 secret | `200 {runId,status,updatedAt,result?}`；无记录 `404` |
| `POST /internal/v1/runs/{runId}/resume` | 以 checkpointer 恢复 WAITING_HUMAN；Body `{approvalRequestId,decision:"APPROVED"|"REJECTED",decisionHash}`，重复同决策幂等，冲突 `409` | `200 {runId,status:"APPROVED_FOR_MANUAL"|"REJECTED"}`；批准不执行工具 |
| `POST /internal/v1/runs/{runId}/cancel` | 取消尚未完成的图；已完成返回 `409` | `200 {runId,status:"CANCELLED"}` |

内部 HTTP 超时 60 秒；网关/网络中断后 Java `AgentRunReconciler` 每 3 秒查状态，若仍非终态则按相同 runId 再 invoke/resume。Postgres checkpointer 保存每个 LangGraph checkpoint，Python 进程崩溃后安全恢复。进程内只允许一个同 run 执行，advisory lock 防止重复模型调用。Java 最终仍需校验 graphVersion、schema 和 evidence IDs。人工审批通过后 Java 调 resume 并将业务状态置为 `APPROVED_FOR_MANUAL`；线下动作结果通过外部 `/api/v1/agent-runs/{id}/manual-action-report` 写 Java 数据库，不要求图执行任何写操作。审批请求创建 24 小时后未决则转 `EXPIRED`，AgentRun 转 `CANCELLED`；相同决策重放返回原结果，不同终态决策返回 409。

外部人工处理结果仅当 AgentRun 为 `APPROVED_FOR_MANUAL` 时接受；请求包含幂等键和 outcome/note，最多 2,000 字符。成功写入 `manual_action_report`、操作者、时间和 incident event，再将 AgentRun 置 `COMPLETED`。`NOT_EXECUTED`/`FAILED` 同样是人工回填，不伪装为已成功运维。

## 前端页面与状态

| 路由/页面 | 主要组件 | 必需状态 |
|---|---|---|
| `/overview` | 目标健康摘要、活动事故 | 无目标、过期数据、服务不可用 |
| `/targets`、`/targets/:id` | 目标配置、检查历史、网络策略 | URL 策略拒绝、暂停、检查超时 |
| `/incidents`、`/incidents/:id` | 事故队列、事件时间线、状态操作、启动诊断 | 空列表、并发冲突、恢复观察、无访问权 |
| `/agent-runs/:id` | 节点/状态、事实与假设、证据链接、批准/拒绝/回填 | 图运行中、等待人工、超时、已批准待线下、已拒绝 |
| `/runbooks` | 草稿编辑、版本、section 引用 | 发布/禁用、历史版本只读 |
| `/settings/integrations` | Kuma base URL、Push Monitor 映射、连通测试 | token 不回显、测试失败、暂停集成 |

审批按钮清晰说明“平台不会执行命令”；只有状态为 `APPROVED_FOR_MANUAL` 时显示人工结果回填表单。

## 错误码

`TARGET_URL_BLOCKED`、`TARGET_RATE_LIMITED`、`INCIDENT_VERSION_CONFLICT`、`AGENT_PROVIDER_TIMEOUT`、`AGENT_SCHEMA_INVALID`、`KUMA_PUSH_UNAVAILABLE`、`RUNBOOK_NOT_PUBLISHED`。错误响应包含 `traceId`，不包含内部 hostname、token 或堆栈。

## G1 已实现契约补充（2026-10-20）

- `POST /auth/login` 请求 `{username,password}`，成功 `{token,expiresAt}`；`GET /me` 返回 `{id,username,displayName,roles}`。`POST /auth/logout` 返回 204。
- `GET/POST /admin/users` 为 OPS_ADMIN 用户管理入口。创建请求 `{username,displayName,password,roles}`，用户名 3–80 个 ASCII 字母/数字/`_.-`，密码至少 12 字符、至多 72 UTF-8 字节，角色只能为四种已定义值；须 `Idempotency-Key`。响应不返回密码或 hash。
- `PUT /admin/users/{userId}/active` 请求 `{active,reason}`，停用会撤销全部用户会话。角色覆盖请求 `{roles,reason}`。角色和停用状态每次请求从数据库读取。
- `POST /targets` 请求 `{code,name,environment,baseUrl,ownerTeam?}`，code 为小写字母开头、至多 80 个小写字母/数字/连字符；成功 201 返回目标 DTO 和 Location。G1 仅接受公网 HTTPS 域名的标准 443 端口，不接受 query、fragment、userinfo、IP literal 和百分号编码。
- `GET /targets/{id}` 返回已授权目标；未授权与不存在统一 404。分页列表统一 `{items,total,page,size}`，page 从 0 开始。
- `GET /audit-events` 仅 AUDITOR，支持 actorId/subjectId 与分页。内部 `GET /internal/v1/health` 仅用于验证服务身份；更广的内部业务上下文 API 尚未开放。
- G1 新增稳定错误码 `UNAUTHENTICATED/INVALID_CREDENTIALS/FORBIDDEN/VALIDATION_FAILED/INVALID_PAGINATION/INVALID_ROLES/INVALID_PASSWORD_LENGTH/INVALID_TARGET_ACCESS/INVALID_IDEMPOTENCY_KEY/IDEMPOTENCY_CONFLICT/REQUEST_IN_PROGRESS/RESOURCE_NOT_FOUND/RESOURCE_CONFLICT/DEPENDENCY_UNAVAILABLE/INTERNAL_ERROR`。
- 每次请求由服务端生成 `X-Request-Id`，对应错误 `traceId` 与审计 `request_id`，不将外部 Header 当成可信审计标识。
- 不允许停用最后一个活跃 OPS_ADMIN 或移除其 OPS_ADMIN 角色；包括并发管理操作，冲突统一返回 `409 LAST_ADMIN_REQUIRED`。
- 幂等请求指纹使用带用途隔离的 HMAC-SHA256，包含密码的请求不会额外保存公开可计算的密码校验摘要。轮换 session pepper 会撤销已有 token，并使尚在 24 小时窗口的旧幂等键与新指纹冲突；轮换后客户端应使用新幂等键。

此表说明已实现子集，不代表上方 G2–G5 端点已经可用。


## 2026-10-20 G2 已实现契约

本节描述 G2 实际代码；G3 的 Agent/手册实现在后文补充，审批/Kuma 仍为后续规格。分页仍为 page/size；错误为 Problem JSON，code/traceId 可用于定位。前端表单中的理由均按纯文本显示。

- `POST /targets` 原五字段继续兼容；新增可选 `allowHttp`、`allowedPrivateCidrs`、`networkReason`，URL 与网络审批一次原子登记，避免创建占位目标。私网 CIDR 必须是部署允许网段的子网。
- `GET /targets/{id}/access?page=0&size=20&userId=可选UUID` 返回用户名与 canRead/canOperate；限 OPS_ADMIN。
- `GET /overview` 返回可见目标统计、最多 20 个目标和 generatedAt；无 ACTIVE 检查或最新证据超过两倍检查间隔时为 UNKNOWN。
- `GET/PUT /targets/{id}/network-policy` 限 OPS_ADMIN；PUT 请求 `{version,baseUrl,allowHttp,allowedPrivateCidrs,reason}`。version 为当前目标 configVersion，成功增加版本；409 TARGET_VERSION_CONFLICT 或 ACTIVE_INCIDENT_CONFIG_FROZEN 时刷新/处理事故后再提交。
- `GET/POST /targets/{id}/checks`；POST 使用 Idempotency-Key，配置如下，返回新建 DISABLED 检查。列表默认 20、最大 100。

```json
{"method":"GET","intervalSeconds":60,"timeoutMs":3000,"expectedStatuses":[200],"failThreshold":3,"recoverThreshold":2}
```

- `PUT /targets/{id}/checks/{checkId}` 请求 `{configVersion,config:上述配置,reason}`。配置或状态变更都增加 configVersion；陈旧版本 409 CHECK_VERSION_CONFLICT。method 仅 GET/HEAD，间隔 60–86400 秒，超时 100–10000 ms，状态码最多 20 个，阈值 1–20。
- `PUT .../checks/{checkId}/state` 请求 `{configVersion,state,reason}`；DISABLED→ACTIVE，ACTIVE→PAUSED/DISABLED，PAUSED→ACTIVE/DISABLED。配置写入限 OPS_ADMIN。活动事故期间不能修改配置、创建检查或激活快照外检查；暂停后恢复需重新积累成功次数。
- `GET .../checks/history?checkId=可选UUID&page=0&size=20` 返回执行 ID、调度/开始/完成时间、目标与检查版本、UP/DOWN/UNKNOWN、状态码、耗时、稳定错误类别、已校验 IP、重定向决策。无正文、cookie、认证头或敏感异常文本。
- `GET /incidents?targetId=可选UUID&status=可选状态&page=0&size=20`；`GET /incidents/{id}` 返回 `{incident,events,eventsTotal,canOperate}`；`GET .../events` 为分页时间线，按持久化事件序列倒序，保证同事务内事件顺序稳定。
- `POST /incidents/{id}/transitions` 请求 `{version,action,reason}`；action 为 ACK/INVESTIGATE/RESOLVE/CLOSE。要求当前 ON_CALL + 该目标 can_read/can_operate，且状态/版本合法，否则 403/409。RESOLVE 需要实际恢复证据。读取均按目标 ACL；跨权限目标返回 404。

同目标所有写入先锁目标；结果、计数、事故快照/事件和审计在同一事务。HTTP/DNS 策略阻断记 UNKNOWN；HTTP失败记 DOWN。人工状态保持完整：恢复不会跳过确认与排查。INVESTIGATING 满足全部冻结检查的恢复阈值后进入 MONITORING；每项再成功达到各自恢复阈值才自动 RESOLVED。

G2 验收补充：GET /incidents/pending-alerts?page=0&size=20 返回当前用户可读目标中未达事故阈值的监控异常（targetId/targetName/checkId/failureCount/failThreshold/statusCode/errorCode/lastCheckedAt/nextRunAt）。只包含启用目标/检查、当前配置版本、两倍间隔内的DOWN证据，且无同目标活动事故。恢复正常后清零，达到阈值后移入正式事故队列；页面每5秒更新，异常接口故障不遮挡正式事故列表。

## 2026-10-20 G3 已实现契约

运行手册：

- `GET /targets/{target}/runbooks` 按目标 ACL 分页，`POST` 限管理员，带 Idempotency-Key，请求 `{title,markdown}`，成功201。标题160字，正文24000字；正文从不当HTML执行。
- `GET /runbooks/{id}` 返回 `{id,targetId,title,markdown,revision,latestVersion,enabled,hasUnpublishedChanges}`，草稿信息；前端只向管理员显示编辑器。
- `PUT /runbooks/{id}/draft` 请求 `{revision,title,markdown,reason}`；`POST .../publish` 请求 `{revision,reason}`；`PUT .../state` 请求 `{revision,enabled,reason}`。三者限 OPS_ADMIN，理由500字，成功返回新修订；陈旧revision409 RUNBOOK_VERSION_CONFLICT。
- 发布按 Markdown 标题分段，最多5段、单段6000字，每版生成独立UUID与UTF-8 SHA-256。未修改内容重复发布409；禁用后不能发布/检索新诊断，历史版本仍可读。
- `GET /runbooks/{id}/versions/{version}` 返回 `{runbookId,version,title,markdown,publishedAt,sections:[{id,ordinal,text,hash}]}`，稳定只读。

只读诊断：

- `POST /incidents/{id}/agent-runs`，Body `{}`，Idempotency-Key 必填；ON_CALL且该目标can_read/can_operate，管理员身份不替代。202 `{runId,status}`；重放返回同run与其当前状态，且重新检查当前授权。
- `GET /incidents/{id}/agent-runs` 按页返回任务；`GET /agent-runs/{id}` 返回事实状态、图版本/modelName、脱敏错误、投递状态/尝试次数、6个节点、result及冻结的checkEvidence/runbookEvidence。只有发起者/ON_CALL/AUDITOR且当前目标可读可访问；不返回内部URL/token/request-body配置。
- `POST /agent-runs/{id}/retry`，管理员，`{reason}`，仅DEAD任务可重新排队，否则409 AGENT_NOT_RETRYABLE；保留原runId与checkpoint，重置投递计数，不新建诊断事实。
- 输入新增 `graphVersion:"readonly-mock-v1"`、`capturedAt`，手册段落新增 `hash`。最多20条最近30分钟检查、5段已启用的最新发布手册；UTF-8总量64KiB，不足预算时整段省略。
- Python内部invoke/GET已经实现：Bearer内部token、X-Request-Id、未知字段400、超过64KiB413；UUID须和body一致。同ID不同输入409；并发同ID202；最终200 COMPLETED及result。数据库故障/超时503，可有限重试。
- result 包含runId/status/graphVersion/modelName/summary/hypotheses/evidenceIds/nextSteps/citations/uncertainties/steps。mock无外部模型，nextSteps均LOW+requiresHumanReview=true。Java按冻结集合再次核验检查时间、段落版本/hash、假设引用和图版本，非法结果不作为完成报告保存。
- 当前只实现QUEUED/RUNNING/COMPLETED/FAILED。WAITING_HUMAN、resume/cancel、审批、manual-action-report仍属G4，**没有可调用实现**；不应按前文未来契约尝试这些操作。

G3评审补充：`GET /admin/agent-deliveries?page=0&size=20` 仅OPS_ADMIN，返回DEAD投递的runId/incidentId/targetId/targetName/status/deliveryState/attempts/errorCode/createdAt，不返回报告或冻结输入。纯管理员在事故队列底部可填写理由重试，不需要获取ON_CALL/AUDITOR报告权限。整次GET对账+POST恢复共用60秒HTTP截止时间，不能分别叠加预算。

## 2026-10-21 G4 已实现契约（覆盖前文 G3 阶段限制）

- 新任务图版本 `readonly-review-v2`，输入新增 `modelName` 并冻结到任务；默认mock。v1历史输入/检查点不重写。
- `GET /agent-runs/{id}` 新增 `approval:{id,version,status,outputHash,expiresAt,decidedBy,reason,decidedAt}|null` 和 `manualReport:{outcome,note,actorId,reportedAt}|null`。结果报告保持不变；外层任务状态表达流程进展。
- `POST /agent-runs/{id}/approval` Body `{approvalRequestId,version,decision:"APPROVED"|"REJECTED",reason}`，理由必填≤2000字。ON_CALL+当前目标操作权限；相同人/决定/理由/原版本重放200，不同终态或陈旧版本409。决策、审计、事件、RESUME投递同事务。先保存决定，再异步确认 Python；同步前 run 仍为 WAITING_HUMAN，不能回填。
- 批准同步后为APPROVED_FOR_MANUAL；拒绝同步后REJECTED，finalize节点SKIPPED。均不调用运维工具或再次调用模型。24小时未决请求EXPIRED，run为CANCELLED，finalize跳过并发出CANCEL；过期不能批准。
- `POST /agent-runs/{id}/manual-action-report`，ON_CALL+当前操作权限、Idempotency-Key，Body `{outcome:"EXECUTED"|"NOT_EXECUTED"|"FAILED",note}`，说明必填≤2000字。仅APPROVED_FOR_MANUAL可首次接受；同键同体200，不同体409。结果、审计、时间线与run COMPLETED原子保存；不自动解决事故。
- 内部 resume/cancel 已实现并复用Bearer、X-Request-Id、未知字段400与64KiB限制。resume按approvalRequestId/decision/decisionHash完整匹配幂等，不同决定409；取消可重放，已完成或已有决策409。终态回复携带原报告，Java再次核验身份、冻结引用和结果hash。
- Outbox增加INVOKE/RESUME/CANCEL阶段，沿用同run唯一行及90秒租约；60秒对账/发送共用截止，旧token不能提交。RESUME对账见WAITING仍须发送resume，不将旧等待误认为完成。DEAD重试保留phase、报告与已记录决定。
- Python模型调用增加持久化model_call记录。已保存的COMPLETED模型结果可在analyze checkpoint丢失后直接复用；遗留STARTED或响应完成不明时，invoke返回409 `{code:"MODEL_OUTCOME_UNKNOWN"}`。Java记录不可自动重试的 `AGENT_MODEL_OUTCOME_UNKNOWN`；管理员retry也409同码，不能隐式再次计费。用户核实服务商记录后可明确创建新run。明确连接失败/HTTP失败保持可恢复退避。

## UI 工作空间只读查询（2026-10-21）

新增 `/api/v1/workspace`，四种已登录角色可访问，逐条应用目标读取 ACL；保留原API与业务操作契约。无数据库迁移。

| GET路径 | 查询参数 | 返回 |
|---|---|---|
| `/search` | `q`，去空格2–100字符 | `{items:[{kind,id,title,subtitle,path}],perCategoryLimit:8}`，每类最多8项，跨全部可见记录查询 |
| `/targets` | q、sort、page、size | 原Target分页，q匹配名称/代码/团队 |
| `/incidents` | q、status、from、to、sort、page、size | Incident分页，q匹配服务/摘要/编号，时间按openedAt |
| `/runbooks` | q、targetId、sort、page、size | 手册元数据分页，含targetName，不返回全文；q匹配标题/服务 |
| `/agent-runs` | q、status、from、to、sort、page、size | 任务/审批状态元数据分页，时间按createdAt |
| `/activity` | from、to | `{from,to,bucket,points:[{time,count}],total}`，按UTC小时/日聚合全部可见事件首次发现时间 |

列表q最长100字符，`%`、`_`、`!`按字面查询；page从0开始，size默认20/上限100，计数与列表同过滤条件。sort仅允许各端点枚举：目标created_desc/created_asc/name_asc/name_desc；事件created_desc/created_asc/name_asc/severity；手册created_desc/name_asc/name_desc；任务created_desc/created_asc/name_asc。所有排序含id稳定次序。非法排序/状态/分页/搜索返回400。

from/to为ISO Instant，双边同时提供时要求from<to且最多31天；activity缺省最近24小时并总是验证双边。time边界包含。大于2天以日聚合，否则按小时；首尾桶是部分时间桶。全局搜索不受时间范围限制。图表由成功聚合响应补零桶，失败不补零。

任务查询/全局搜索额外要求ON_CALL或AUDITOR，或requestedBy为调用人，并仍须目标可读；纯OPS_ADMIN、OBSERVER不会借列表访问他人报告。写入、人审、幂等、审计与原有GET报告约束完全沿用。
