# 12｜本地部署、运维与 CI/CD

## 本地运行

完整 MVP 的 Compose 必需服务为 `postgres`、`backend`、`agent-service`、`frontend`。诊断可走无需外部密钥的 mock provider；实际检查只打本机 fixture 测试站点。Kuma 放到 `--profile kuma` 可选 profile，便于展示推送边界。建议 8 GiB RAM；Docling 未使用，不下载模型。

当前 G5 启动路径：复制 `.env.example`，设置业务库密码、checkpoint owner/application 两个密码、会话 pepper/内部密钥及 bootstrap 凭据；设置 `AGENT_ENABLED=true`，默认 mock、Kuma 关闭，执行 `docker compose up --build -d --wait`。详细中文 IDEA/Compose 步骤见根目录 `IDEA启动与验收指南.md`。业务和 checkpoint PostgreSQL 各自独立数据卷与网络；Python/数据库不映射宿主机端口，后端18080、前端5174仅绑定loopback。不要与同端口 IDEA 进程同时启动。

2026-10-21 已在独立 `agentops-g5-verify` 项目实际构建三种应用镜像，启动两种 PostgreSQL、Java、Python、前端与 Kuma，验证前端同源登录、诊断/重启/人审、真实 Push 和故障恢复；测试端口18184/5184/3301，未操作用户服务。证据见 `verification/g5-validation-2026-10-21.json`。bootstrap 仍仅针对空用户表，不重置已有账号。

## 配置

| 变量 | 用途 | 敏感 |
|---|---|---|
| `DB_URL/DB_USER/DB_PASSWORD` | PostgreSQL 连接 | 密码是 |
| `SESSION_TOKEN_PEPPER` | 用户不透明 bearer token 的 hash pepper | 是 |
| `AGENT_INTERNAL_TOKEN` | Java↔Python 服务认证 | 是 |
| `AGENT_LLM_PROVIDER` | 默认 `mock`；仅 Agent Service 读取 | 否 |
| `AGENT_LLM_BASE_URL/AGENT_LLM_API_KEY/AGENT_MODEL_NAME` | Python 模型配置；model name 与 Java 相同 | API key 是 |
| `AGENT_CHECKPOINT_DSN` | IDEA 配套 Python 的独立 checkpoint 连接；schema 固定 agent_checkpoint | 含密码，是 |
| `CHECKPOINT_OWNER_PASSWORD/CHECKPOINT_PASSWORD` | Compose 独立集群管理角色/受限应用角色密码；DSN 由入口编码组装 | 是 |
| `CHECK_GLOBAL_QPS/CHECK_TIMEOUT_MS` | 探测保护阈值 | 否 |
| `KUMA_ENABLED/KUMA_BASE_URL` | 默认关闭；部署唯一允许 origin | 不含 token |
| `KUMA_ALLOW_HTTP/KUMA_ALLOWED_PRIVATE_CIDRS` | Kuma 专用的 HTTP/私网批准，不放宽目标探测策略 | 否 |
| `KUMA_PUSH_TOKEN_*` | 环境变量 secret；配置 API 仅提交 env:KUMA_PUSH_TOKEN_* 引用 | 是 |

不得提交 `.env`、真实 token、服务密码、生产 URL、真实运维日志或供应商响应数据。

还支持 `SESSION_DURATION`（默认 `PT30M`，正数且不超过 24 小时）、`BOOTSTRAP_ADMIN_USERNAME/BOOTSTRAP_ADMIN_PASSWORD`（仅空库初始化）和 Compose 的 `BACKEND_PORT/FRONTEND_PORT`（默认18080/5174）。IDEA 后端使用 `PORT=18080`。两个服务端秘密必须彼此不同且至少32字符；bootstrap密码至少12字符、不超过72 UTF-8字节。`CHECK_GLOBAL_QPS/CHECK_TIMEOUT_MS` 仍为规格观察项，不是运行变量；实际保护规则见下方 G2 补充。

## 运维手册

- **DB 不可用**：停止写操作/领取新 job；不返回“成功”；Outbox 租约过期后恢复重试。
- **Agent provider 不可用**：检查历史和事故界面继续工作；run 显示 FAILED/可重试，保留已采集事实。
- **探测误报**：暂停对应 check，检查 DNS/IP 策略和采集时段，修改配置后生成新版本；不删除历史记录。
- **Kuma 不可用/token 失效**：禁用 adapter 或轮换 secret；查看 IntegrationDelivery；主事故不受影响。
- **备份恢复**：停止 Java/Python 写入后，配套备份业务库及完整 checkpoint 库，保留匹配代码和 secret。恢复到两个新库，校验 Flyway、incident/agentRun/checkpoint 引用与模型调用记录；checkpoint 库所有者仍应是受限应用角色，否则 schema 初始化可能被 CREATE 权限拒绝。缺失/错配 checkpoint 的自动修复和 RECOVERY_REQUIRED 专门状态尚未实现，不得盲目重发模型任务。
- **关机**：停止领取新事件、等待短任务；剩余任务靠租约恢复。

## CI/CD

流水线：后端编译/单元和迁移集成测试；Python lint/type/schemas/graph fixture；前端 lint/typecheck/build；OpenAPI/内部 DTO 契约；依赖/SBOM/许可证与秘密扫描；Compose 构建；本地 E2E smoke。部署只到演示环境；本包不授权生产发布。镜像使用固定版本或 digest。


## 2026-10-20 G2 运行补充

Vue 中文工作台在 frontend。IDEA/JAR 配套开发模式：根目录执行 `cd frontend`、首次 `npm ci --ignore-scripts --no-fund --no-audit`、`npm run dev` 后打开 http://127.0.0.1:5174。仅本机绑定，默认代理 `/api` 到18080，可用 BACKEND_API_ORIGIN指定其他本机后端。前端不配置数据库/服务端密钥。`npm run build` 类型检查并生成 dist；preview不提供开发 API代理。G5已加入Nginx同源静态服务/history fallback，正式HTTPS入口仍由部署方配置。

新增实际配置为 MONITORING_ENABLED（默认 true）和 MONITOR_ALLOWED_PRIVATE_CIDRS（默认空，逗号分隔 RFC1918/ULA 网段）；Compose 已传入。CHECK_GLOBAL_QPS/CHECK_TIMEOUT_MS 仍是规格观察项，当前上限固定为 4 次领取/秒、4 个全局槽；timeout 在检查定义中配置，不读取那两个预期变量。新建检查默认禁用，不会因为登记目标自动探测。多个检查共享同目标 60 秒间隔和域名并发配额。

升级前备份项目数据库；V3 为增量迁移，本轮仅在独立临时数据库执行，没有迁移用户数据库。已有账号不会由 bootstrap 重置。G2 的 V3 还未向用户数据库发布，开发中修订过同文件；发布后禁止修改已应用迁移，后续变更必须另增 V4。

事故处理中若有活动事故，先保留检查配置快照。暂停失败检查会中断恢复，不等于问题解决；INVESTIGATING 后全部检查恢复达阈值才进入观察，观察期仍继续检查。数据库异常调度退避 15 秒，未完成任务等 60 秒租约到期重领；关闭时停止新领取并等待最多 12 秒。日志只记录任务 ID 和异常类别，详细目标正文不保存。

CI 已配置 Ubuntu Java21 迁移/API/HTTP/TLS 测试和 Node24 前端安装/类型检查/构建/npm audit。本地 Windows 已运行；远端 CI、容器 build/up、部署、防火墙与压力测试尚未执行。不要将 workflow 文件存在当作实跑证据。

## 可控 HTTP 目标：人工故障恢复验收

`scripts/demo-target.mjs` 使用 Node 内置模块，无新增 npm 依赖。它只提供 GET/HEAD `/health`，默认返回 200；只能从启动终端输入 `down` 切为 503、输入 `up` 恢复 200、输入 `quit` 停止。没有网络控制接口，不修改目标 URL、检查配置或数据库。脚本只允许绑定本机拥有的 RFC1918 IPv4 地址，固定标准 HTTP 80，端口冲突时退出，不终止已有服务。

在项目根运行（把占位符替换为本机实际私网 IPv4，不能填 127.0.0.1 或 0.0.0.0）：

```powershell
node scripts/demo-target.mjs --host '<本机私网IPv4>'
```

验收前提：项目禁止 IP literal 和 loopback 目标，因此还需要一个解析到该私网 IPv4 的域名。可使用用户已有的内部 DNS；公共映射域名（例如 sslip.io）会向第三方 DNS 提交包含私网地址的查询，需要用户知情允许。本次准备过程中该外部查询被自动审批拒绝，未执行；不要将辅助脚本测试通过等同于已完成目标登记。

IDEA 后端的 MONITOR_ALLOWED_PRIVATE_CIDRS 仅增加本机该地址 `/32`（保留已有合法范围），重启后生效；目标登记也填写同一 `/32`、勾选批准 HTTP 并填写“本地故障恢复验收”理由。域名健康地址使用 `http://<已验证域名>/health`。新建 GET 检查，间隔60秒、超时3000ms、预期200、失败阈值3、恢复阈值2，再手动启用。只为此测试服务授权，不能用 0.0.0.0/0 或扩大保留地址访问。

先观察一次 UP，再在测试服务终端输入 down；等待连续三次503后应只有一条活动事故。使用 ON_CALL + 目标 can_operate 的账号确认并开始排查；保持检查启用，在测试服务终端输入 up。连续两次成功进入 MONITORING，再各成功达到恢复阈值后自动 RESOLVED，最后人工关闭。验收过程中不改 URL/期望码绕过活动事故配置冻结。

辅助脚本独立验证命令：`node --test scripts/demo-target.test.mjs`，4项覆盖实际200/503/200、HEAD、HTTP控制拒绝及本机私网绑定限制；随机loopback端口的测试服务结束即关闭，不使用用户数据。

## G3 宿主机运行与恢复

详细逐步启动见README“G3本地只读诊断”。Python使用独立数据库/受限角色，默认127.0.0.1:8090；Java新增AGENT_ENABLED、AGENT_SERVICE_ORIGIN，内部token与Python相同。默认不开启Java分发，排队事实保留。当前Vue默认5174，避免5173其他项目冲突。IDEA用户数据库升级V4必须先由用户备份并重启，本轮未执行。

Outbox每3秒唤醒，最多2并发；失败退避2/10/60/300秒加抖动，5次后DEAD。修复内部服务/配置后在报告页由管理员填写理由重试。崩溃时租约过期接管，同runId查询或invoke，Python从已提交checkpoint恢复。Python持久化库应与Java业务库分别备份，恢复时保持相匹配的runId/图版本；不要将示例fixture当作生产恢复脚本。

scripts/agent.ps1 Install -Dev安装锁定测试环境，Test运行图单元契约；scripts/backend.ps1 verify会另外创建独立临时PG并验证真实Python/受限角色/检查点，测试不会使用AGENT_CHECKPOINT_DSN。Linux CI配置包含Python3.13和hash锁安装，但尚未远端实跑。完整Compose、生产网络隔离和备份恢复演练仍待G5。

## G5 实际运行与恢复（2026-10-21）

完整 Compose 已本地实跑。frontend 通过独立 edge 网络发布本机端口，app/data/checkpoint 内部网络隔离；所有应用镜像使用非root用户，Python仅内网8090。Kuma固定2.3.0及digest，以SQLite首次初始化，用户与Monitor仍由操作者在Kuma界面创建；产品不创建Monitor、不走Socket.IO管理接口。

管理员配置Kuma origin与env secret reference，异步smoke通过后才允许启用。部署开关关闭时无网络投递；每集成串行租约、最多5次、有界3秒HTTP+1秒DNS。新的有效检查替代旧待发送样本，领取时再次检查；UNKNOWN/过期不推送。Kuma有独立DNS池，不占用核心解析容量。401/403终止；Kuma路由把多种异常返回404，因此404按有界策略重试，不能直接等同token失效。投递是至少一次，远端超时后可能出现重复heartbeat。

`scripts/recovery-drill.ps1` 为可复验的无网络临时PG17.11演练：V1–V6、合成事故与过期Outbox租约、配对marker，自定义格式备份后恢复到新库，结束只清理本轮容器。本轮另外对实际Compose测试业务库/checkpoint库停止写入后做配对dump/新库restore，保留1事故、3诊断、3审批、1手册版本、5检查样本、3投递；恢复后的真实LangGraph WAITING_HUMAN可resume至APPROVED_FOR_MANUAL，模型journal条数不变。这不代表已恢复任何用户数据库或已完成生产灾备。

CI新增隔离Compose构建/启动/健康与恢复drill job；远端GitHub执行尚未发生。容器冷启动首次遇到BuildKit认证网络超时，预拉官方固定摘要镜像后构建成功，另在当前命令内用经典构建器复验；未更改Docker全局设置。应用环境变更需要 `up -d` 重建，`restart` 不会加载新 `.env`。
