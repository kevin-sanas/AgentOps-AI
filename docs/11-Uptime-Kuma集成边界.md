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

## G5 实现与真实版本核验（2026-10-21）

实现 `UptimeKumaPushAdapter`、V6配置/投递/attempt表、管理员API和目标详情面板。默认关闭，只允许部署唯一origin，独立DNS池+全部地址校验+IP固定；不允许模型传地址。表内只保存受限env引用，不保存token。smoke异步排队并发送固定测试UP，成功后可启用该映射；多检查只投递最新当前配置、未过期的有效样本，不代表事故聚合状态。

使用官方 `louislam/uptime-kuma:2.3.0@sha256:a8e5e69ce185d5425fcbf2ab14ddf808df5651fb2747a46a17cecf8ed5fd1af2` 实际验证POST up/down返回200/ok=true，无效token返回404。在该镜像的 `server/routers/api-router.js` 中，整个Push处理的异常均进入404分支；因此404使用 `KUMA_PUSH_REJECTED` 有界重试，401/403才标记 `KUMA_TOKEN_REJECTED` 停止重试。远端正文不写日志或响应。

HTTP绝对预算3秒、响应64KiB、不跟随重定向；租约15秒、最多5次尝试。重复heartbeat仍可能发生，不能宣称远端exactly-once。轮询和领取都检查新样本，避免新UP送达后又领取旧DOWN。真实Kuma暂停注入已验证超时/PENDING、核心新诊断继续、恢复后同投递第4次送达；前一次404分类错误由失败回归测试修复。证据见 `verification/g5-validation-2026-10-21.json`。

