# 11｜Uptime Kuma 集成边界

## 目的和方向

Uptime Kuma 可为现有使用者显示 AgentOps 定期检查到的服务状态，并继续使用 Kuma 自己配置的通知。数据方向只有 **AgentOps → Kuma Push Monitor**。管理员在 Kuma UI 中预先创建 Push monitor，再将其 Push URL/token 以 secret reference 配置到对应 AgentOps `ServiceTarget`。

AgentOps 不从 Kuma 导入 monitor、不通过 Socket.IO 登录、不修改 Kuma 通知规则、不把 Kuma heartbeat/incident 当作自己的事实记录。AgentOps 自己的 `CheckRun`/`Incident` 才是本系统数据源。

## 上游接口核验

截至 2026-10-20：Uptime Kuma 仓库许可证为 MIT；官方 Wiki 的 Push endpoint 描述为 `/api/push/<pushToken>`，支持 status `up/down`、message 和 ping 等字段。但同一 Wiki 将相关 API 定义为 **内部 API**，明确说明第三方支持不保证、可能发生破坏性变更。Push monitor 是目前选用的最窄集成面，也仍需按选定 Kuma 版本做契约验证。

### 适配器契约

- 只使用人工提供的已创建 Push Monitor token。
- 以 `POST` 向 `{baseUrl}/api/push/{token}?status=up|down&msg={shortMessage}&ping={latencyMs}` 发送；请求中不传用户文本，message 使用固定脱敏模板，如 `target=orders-api; check=down; run=<short-id>`。
- token 在应用配置/数据库中按 secret reference 保存；HTTP client、反向代理、追踪和异常日志隐藏 URL path 中 token。
- 启用前先 smoke push；每次集成都固定 Kuma 的明确版本或镜像 digest；每次升级跑 up/down、错误 token、超时、响应错误契约测试。
- Push 失败只更新 `IntegrationDelivery` 并告警；不回滚检查结果、事故或人工时间线。
- 外部端点 allowlist 限制为管理员登记 Kuma base URL，且同样执行 DNS/IP/redirect 策略；不允许模型指定。

## 本版本支持与不支持

| 能力 | MVP |
|---|---|
| 向已创建 Push monitor 推送 UP/DOWN 和简短 ping | 支持，可关闭 |
| 从 Kuma 读取 heartbeat/事件或 monitor 列表 | 不支持 |
| 自动创建 monitor/改通知策略 | 不支持 |
| 通过 Socket.IO 内部协议调用 | 禁止 |
| Kuma 不在线时保留 AgentOps 结果并重试 push | 支持 |

如果未来要做双向数据同步，需要新增版本化 adapter、回放/去重、认证安全评估和明确契约；不能以“内部 API 可用”推断为稳定支持。

