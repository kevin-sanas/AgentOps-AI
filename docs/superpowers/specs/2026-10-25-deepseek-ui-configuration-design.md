# DeepSeek 页面配置与连接测试设计

日期：2026-10-25  
状态：待用户确认

## 1. 意图

管理员应当能够在 Dawnwatch 页面中完成 DeepSeek API Key 的配置、连接测试、更新和删除，无需在 IDEA、终端、环境变量或启动脚本中输入模型 Key。保存后的配置在正常项目重启后继续有效，诊断任务立即使用当前已保存配置。

当前页面只显示统一的 `AGENT_HTTP_400`，掩盖了真实的 `TLS_VERIFY_FAILED`。本次同时让管理员和诊断任务页面看到安全、可操作的失败分类，但绝不展示证书原文、请求体、API Key 或堆栈。

## 2. 用户体验

在左侧“AI 故障诊断”下增加仅管理员可见的“模型配置”子页面。

页面包含：

- 当前提供商：DeepSeek（本阶段固定）。
- 当前模型：`deepseek-flash`（本阶段固定）。
- 配置状态、Key 尾四位、更新时间和最近一次测试结果；不提供完整 Key 的读取接口。
- 账户余额卡片显示 DeepSeek 返回的“可调用”状态，并按币种展示总余额、充值余额和未过期赠金余额；只在管理员点击“刷新余额”时查询，不后台轮询。
- “本系统用量”区域按时间范围汇总 Dawnwatch 自己发出的外部请求次数、成功/失败次数、输入 Token、输出 Token、总 Token及缓存命中/未命中 Token，并提供可分页的调用明细。
- 密码型 API Key 输入框，粘贴后可测试，保存成功后立即清空。
- “测试连接”使用输入框中的新 Key；输入框为空时测试已保存 Key。
- “保存配置”仅保存格式有效的 Key；保存和连接测试是两个明确动作，测试失败不会自动覆盖原有可用配置。
- “删除配置”需要确认，删除后新诊断不可发起真实模型调用，历史诊断和审计记录不变。
- Loading、成功、未配置、认证失败、TLS 失败、网络超时、限流及服务端异常均有独立文本状态，不只依赖颜色。

连接测试调用 DeepSeek 官方 `GET https://api.deepseek.com/models`，验证 TLS、网络、Bearer Key 及 `deepseek-flash` 是否出现在返回列表。它不执行模型推理、不创建诊断任务，也不产生模型 Token。请求保持官方 HTTPS 直连、启用 TLS 校验、禁用环境代理、无自动重试。

余额刷新调用 DeepSeek 官方 `GET https://api.deepseek.com/user/balance`。页面按接口原值展示 `CNY` 或 `USD` 的 `total_balance`、`topped_up_balance`、`granted_balance`，不自行换算币种、不推算未来费用。余额仅保留在当前页面内；数据库只保存最近查询时间和成功/失败分类，不保存余额数值，避免把可能快速变化的财务数据误当成实时数据。

用量统计仅代表本 Dawnwatch 实例实际发出的请求。真实诊断以 DeepSeek 响应中的 `usage` 为准，记录 `prompt_tokens`、`completion_tokens`、`total_tokens`、`prompt_cache_hit_tokens` 和 `prompt_cache_miss_tokens`。连接测试和余额查询计入请求次数，但 Token 标记为“不适用”而不是伪造为 0。失败发生在获得完整模型响应之前时，Token 标记为“未知”；同一 API Key 在其他程序或其他 Dawnwatch 实例中的调用不属于本系统统计。

每条调用明细包含用途（故障诊断、连接测试、余额刷新）、提供商、模型、触发用户、发起/完成时间、结果分类和请求编号。故障诊断还关联事故 ID 与诊断任务 ID，页面可直接返回相应记录。列表默认最近 24 小时，可选择最近 7 天、30 天或自定义范围；使用服务端分页，不把全部明细加载到浏览器。

## 3. 权限与审计

- 查询脱敏状态、测试、保存和删除均要求 `OPS_ADMIN`。
- 观察员、值班人员和未登录用户不能读取配置状态或调用配置接口。
- 审计记录配置创建、更新、删除、连接测试及余额查询结果，只记录提供商、模型、安全错误分类、操作者和时间，不记录 Key、请求头、密文、证书详情、余额数值或服务商响应正文。模型调用用量写入专用不可变台账，不把 Token 明细重复放入审计 metadata。
- 前端不把 Key 放入 Pinia、localStorage、sessionStorage、URL、查询参数或错误文本。

## 4. 持久化与加密

新增单例模型配置表，保存提供商、模型、AES-256-GCM 密文、随机 nonce、Key 尾四位、版本、更新时间、更新人及最近测试的安全摘要。

新增模型请求台账表，每次对 DeepSeek 发出 HTTP 请求前创建记录，完成后仅允许补写结果、耗时和官方返回的 Token 用量。用途限定为 `DIAGNOSIS`、`CONNECTION_TEST`、`BALANCE_QUERY`；可选关联诊断任务、事故和操作者。台账不保存提示词、响应正文、API Key、完整外部错误正文或余额金额。当前阶段不自动删除台账，由后续明确的数据保留策略处理。

加密密钥不新增用户配置动作：后端从现有 `SESSION_TOKEN_PEPPER` 通过带固定用途标签的 SHA-256 派生独立 256 位模型凭据密钥。每次保存生成新的 96 位随机 nonce，并把提供商、模型和表记录 ID 作为 GCM 附加认证数据。数据库泄露本身不能恢复 API Key；`SESSION_TOKEN_PEPPER` 必须像现在一样在重启间保持稳定，否则后端返回“凭据无法解密”，不静默覆盖数据。

Key 永不写入 Git、文档、日志、审计 metadata、诊断输入、outbox、checkpoint 或 `.cache`。Java 中解密值只在连接测试或一次诊断交付期间短暂存在；Python 接收后只构造当次提供商对象并在调用结束时清空引用。

正常项目使用业务 PostgreSQL 持久化，因此重启后继续有效。`demo.ps1` 的隔离验收数据库按既有安全约定在退出时销毁；它不是持久化生产环境，配置不会跨独立 demo 会话保留。验收持久化行为应使用正常项目启动方式。

## 5. 服务接口

新增管理员接口：

- `GET /api/v1/admin/model-config`：返回脱敏状态。
- `PUT /api/v1/admin/model-config`：保存或替换 Key，要求幂等键和版本控制。
- `POST /api/v1/admin/model-config/test`：测试请求体中的候选 Key，或在无候选 Key 时测试已保存 Key。
- `GET /api/v1/admin/model-config/balance`：使用已保存 Key 主动查询当前余额；响应明确标注查询时间且禁止缓存。
- `GET /api/v1/admin/model-usage/summary`：按允许的时间范围返回本系统请求与 Token 汇总。
- `GET /api/v1/admin/model-usage/requests`：返回分页调用明细和业务关联，不返回请求/响应内容。
- `DELETE /api/v1/admin/model-config`：按版本删除，需要幂等键。

所有响应使用既有 `ApiError` 格式和请求编号。候选 Key 限制长度和字符，响应不回传输入。连接测试最长 10 秒、响应体上限 64 KiB、不跟随重定向、不重试。

安全错误码至少包括：`MODEL_CONFIG_REQUIRED`、`MODEL_CREDENTIAL_INVALID`、`MODEL_INSUFFICIENT_BALANCE`、`MODEL_TLS_VERIFY_FAILED`、`MODEL_CONNECT_TIMEOUT`、`MODEL_RATE_LIMITED`、`MODEL_PROVIDER_UNAVAILABLE`、`MODEL_RESPONSE_INVALID` 和 `MODEL_CONFIG_VERSION_CONFLICT`。

## 6. 诊断链路

发起诊断前，Java 后端确认存在可解密的 DeepSeek 配置，并把不可变的 provider/model 快照写入诊断记录；不把 Key 写入任务数据。

只有在交付 `INVOKE` 阶段时，Java 才从配置服务取得解密 Key，通过现有受内部令牌保护的 Java→Python 请求以专用敏感请求头传递。若 agent service 使用非回环 HTTP 地址，禁止发送凭据；远程地址必须是 HTTPS。Python 中间件读取后立即从通用请求处理路径隔离，按该次请求构造 `DeepSeekProvider`。查询、人工审批、恢复和取消不再传 Key。

Python 返回结构化、安全的服务商失败分类；Java 将其映射为稳定业务错误码和诊断页面说明。TLS 校验保持开启，Clash 及系统代理不用于 DeepSeek。

## 7. 兼容性与启动行为

- 保留 `mock` 模式用于自动测试和无付费演示。
- 正常真实模型模式不再要求 `AGENT_LLM_API_KEY` 环境变量。
- `AGENT_MODEL_NAME` 的运行时来源改为已保存配置；没有配置时 UI 明确引导管理员，不产生失败 outbox。
- 现有 `deepseek_check.py` 与付费评测入口仍可作为独立开发工具，但不再是日常产品配置入口。
- 更新 `IDEA启动与验收指南.md` 与 README，删除日常启动时输入 DeepSeek Key 的步骤，加入页面配置、无 Token 连接测试及持久化验收步骤。

## 8. 验证与验收

实现采用测试先行，至少覆盖：

- 数据库迁移、AES-GCM 往返、错误 pepper/篡改密文拒绝、Key 永不出现在 API/审计/日志。
- 管理员权限、非管理员拒绝、版本冲突、保存/更新/删除、候选 Key 测试不会覆盖旧配置。
- Python `/models` 连接测试的成功、401、TLS、超时、429、5xx、超大/非法响应，均为单次请求且禁用代理。
- Python `/user/balance` 的多币种成功响应、余额不足、401、TLS/网络失败、非法金额和超大响应；前端不把旧余额显示成最新余额。
- 用量台账在请求前落库、成功后使用官方响应用量、失败/结果未知不伪造 Token；汇总与分页按用途和业务关联正确统计。
- 并发诊断、服务重启和重复交付不会为同一次外部请求重复记账；每个实际 HTTP 请求都有唯一请求记录。
- 诊断交付按需取得 Key，outbox/checkpoint 不含 Key；非回环 HTTP 拒绝传递。
- 前端页面状态、密码框清空、键盘可访问性、错误文案和导航权限。
- 正常项目重启后仍显示已配置，并能再次执行无 Token 连接测试。

不在自动化测试或本次实现中发起真实 DeepSeek 请求。真实连接测试由用户在页面主动点击；任何后续真实模型推理仍沿用显式预算和单次调用约束。

## 9. 非目标

- 本阶段不支持多个提供商、多个 Key、团队级配置、自动轮换、自动充值、DeepSeek 账户级历史账单、费用预测或明文导出。
- 不声称能够统计同一 Key 在其他软件或其他 Dawnwatch 实例中的调用；账户余额与本系统 Token 台账是两种不同口径。
- 不关闭 TLS 校验，不导入未知证书，不接入 Clash，不把失败自动重试成新的付费请求。
- 不改变现有人工审批、只读诊断和高风险操作拦截规则。
