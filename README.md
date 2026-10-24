# Dawnwatch

陪值班人员守到天亮的 AI 运维协作平台。

*An AI operations companion for on-call teams, through the night.*

[![application-verify](https://github.com/kevin-sanas/Dawnwatch/actions/workflows/backend.yml/badge.svg?branch=codex%2Fdawnwatch-rename)](https://github.com/kevin-sanas/Dawnwatch/actions/workflows/backend.yml?query=branch%3Acodex%2Fdawnwatch-rename)

当前徽章对应改名分支；主分支尚未合并本阶段。

阶段 1 已切换品牌与仓库链接，兼容的技术包名、数据库及历史证据保留；Compose 升级前请阅读[数据卷兼容说明](docs/12-本地部署运维与CI-CD.md)。核心叙事是 Agent 可靠性、证据校验与人工掌控；后续阶段依次整理设计系统、改善界面、完善演示与开展真实模型评测。

> 当前为 G5 + Dark-first UI/UX：后台工作页签、左侧子菜单、显著处理入口、主题下拉框、真实图表与诊断证据工作台；在 G4 监控、诊断和人审闭环上增加可选 Kuma Push、完整 Compose 和隔离恢复演练。默认 mock、Kuma 关闭；真实模型效果与容量/性能仍待验证，远端CI按提交记录。实际证据见阶段测试文档。

G6新增一条核心自动验收命令：` .\scripts\acceptance.ps1`。使用独立临时数据库、随机端口和Python mock，串起故障阈值、事故、人审、回填与恢复，生成本次HTML/JSON报告；不需要填写新的数据库密码或token。启动与用法见专属指南第12节。首次GitHub CI已核实通过（39c1948），新功能分支仍需单独验证，不能沿用旧提交的绿灯。

**自己操作验收：** 在IDEA终端运行 ` .\scripts\demo.ps1`，按终端显示的网址和临时账号登录。脚本自动准备独立数据库、Java/Python、前端、服务目标和手册；输入 `down` / `up` 控制合成故障及恢复，输入 `quit` 结束。界面明确标注演示样本和mock，最长两小时，退出后清除临时数据库及凭据。无需Docker、hosts修改或填写新密钥；已有开发依赖要求和完整验收步骤见专属指南第13节。自动检查此入口用 ` .\scripts\demo.ps1 -Check`。

Windows PowerShell 5.1与PowerShell 7均可使用；中文验收入口保持UTF-8 BOM，JSON按UTF-8读取。兼容回归在Windows上调用自带5.1解析两个入口，在Linux上明确跳过该专属检查。

DeepSeek官方API适配已提供：Python设置 `AGENT_LLM_PROVIDER=deepseek` 和本机API Key，Java/Python的 `AGENT_MODEL_NAME` 保持一致。接入及真实调用验收见专属指南第14节。2026-10-24本轮隔离工作台已通过一次真实直连验收：模型报告经Python与Java校验，完成检查点人审恢复、批准、线下“未执行”回填及合成恢复后事故关闭。仅调用1次 `deepseek-flash`，实际usage为1919输入/1029输出tokens；不将这一个合成场景写成模型质量或性能成绩。此前3次前置检查失败的原始记录继续保留。

首次接通可以在本机运行 ` .\agent-service\.venv\Scripts\python.exe .\agent-service\deepseek_check.py`，安全输入Key后仅请求一次，保存脱敏结果；无需数据库配置。具体范围、费用确认与日常工作台接入区别见专属指南第14.1.1节。三次原始脱敏结果与限制见[真实接通记录](docs/verification/deepseek-live-2026-10-24.json)，后续调用须重新确认，不自动重跑。

模型网络保持直连，不为DeepSeek配置Clash代理；Clash仅用于其他网站。当前连接失败原因尚未确定，不将“没有使用代理”视为故障原因。

完整页面链路新增显式入口 ` .\scripts\demo.ps1 -DeepSeek -NoInput`：隐藏输入一次Key，自动准备隔离服务与临时数据库，页面标明真实模型，每进程会话硬限制1次模型请求；原mock入口不变。用法与本轮预算见[专属指南第14.1.2节](IDEA启动与验收指南.md#1412-隔离工作台页面发起一次真实诊断)，实际结果见[真实工作台验收记录](docs/verification/deepseek-workbench-2026-10-24.json)。本地250项回归、类型/构建、核心16步及默认mock演示7步通过；6518146首次远端CI的后端/前端通过、隔离恢复演练失败，后续TCP就绪修正及复验见[质量准备记录](docs/verification/deepseek-quality-prep-2026-10-24.json)。30场景真实质量评测未运行。

**真实模型质量评测准备（2026-10-24）：** 新增独立30场景标注草稿与离线 `agent-service/evaluate.py` 工具，保留原30条护栏契约不变。无需Key、数据库或运行服务，导出可读标注清单并核对离线报告；未取得完整响应、人工标注和逐报告评分时只能得到 `INCOMPLETE`。输入/报告hash防止旧评分串用，导入文件也不自动认证为真实模型成绩。用法见[专属指南14.5节](IDEA启动与验收指南.md#145-离线准备30场景质量评测不调用模型)。源码6102190的[远端CI三作业全部通过](https://github.com/kevin-sanas/Dawnwatch/actions/runs/40087951346)，含修正后的实际隔离恢复演练。本轮无新增模型请求，批量付费评测另行确认。

**五场景受控试评测与人工审阅：** `evaluate_live.py plan` 离线冻结原始合成数据与预算，显式 `run --approve-plan` 才隐藏输入Key并直连官方接口；每批最多5次、单目录一次性、失败/用量未知停止且无重试。真实调用前另确认范围与预算。离线 `evaluate.py review` / `import-reviews` 自动绑定报告hash并导出/导入人评CSV，保留待办和人工决定，不自动填分。137项Python回归及3条明确模拟报告的离线命令链路通过。用法与真实执行状态见[指南14.6节](IDEA启动与验收指南.md#146-受控五场景试评测与csv审阅2026-10-24)及[本轮记录](docs/verification/deepseek-evaluation-pilot-2026-10-24.json)。

本批经单独授权实际调用2次：持续503报告通过本地校验，探针超时报告因 `AGENT_RISK_UNDERESTIMATED` 被拒，批次按约停止，其余3场景未调用。共4561 tokens，峰时未命中估算US$0.0029568（非账单）；没有重试或放宽护栏。被拒原文未保存，不能确定具体句子或排除保守匹配。实际1份接受报告已生成待人评包；完整质量结果仍INCOMPLETE，不是30场景通过率。

风险拒绝现已补充脱敏定位：后续回执显示建议索引、当前/要求风险值与操作类别，DeepSeek提示明确现有文本规则对历史、否定及假设性提及也适用。原正则和拒绝条件未改，不自动升风险值或保留拒绝原文。147项Python、15项Java定向回归通过，本轮无新增真实调用；旧失败的具体原因仍未知，新提示的真实效果待单独验证。验收方法见[指南14.7节](IDEA启动与验收指南.md#147-风险拒绝的脱敏定位2026-10-24)及[风险定位记录](docs/verification/deepseek-risk-diagnostics-2026-10-24.json)。

**在你的电脑上启动项目，请直接看 [IDEA 启动与验收指南](IDEA启动与验收指南.md)。** 该指南随启动配置和项目验收流程同步维护。

**项目完整信息与四类图：** [项目详细说明](项目详细说明.md)；[离线图文版](docs/project-overview/项目详细说明.html)含功能结构、技术架构、数据库ER和系统流程图，可在浏览器打开并放大。

## 当前进度与方向

先建立 Java 业务事实源和权限边界，再实现检查/事故闭环，随后接入只读诊断与人工流程，最后验证可选 Kuma 和完整 Compose。阶段计划与实际执行记录见 [CODEX_EXECUTION.md](CODEX_EXECUTION.md)，依赖核验见 [15](docs/15-开源许可证与接口核验.md)，测试结果见 [13](docs/13-测试矩阵与阶段门.md)。

已实现：

- 不透明 bearer 会话、HMAC-SHA256 + pepper、默认 30 分钟过期、退出撤销；密码仅存 bcrypt hash。
- 四种独立角色、用户创建/停用/角色变更、服务目标 ACL；角色与授权变更对已有 token 生效。
- 创建请求的幂等指纹使用带用途隔离的 HMAC，避免为密码增加廉价的无密钥校验摘要；最后一个活跃管理员受到保护。
- 目标登记/查询/授权、用户和目标创建的事务幂等、审计与统一 Problem JSON 错误。
- 公网 HTTPS 默认策略；HTTP 逐目标审批，私网 CIDR 同时受部署网段上限约束。每次探测重新验证全部 DNS 地址并固定连接 IP，保留原域名 TLS 校验；不使用代理、重定向、认证或 cookie。
- GET/HEAD 检查、64 KiB 响应上限、绝对超时；数据库租约、全局/域名配额、结果历史和过期结果拒绝。
- 同目标唯一活动事故、冻结检查快照、连续失败/恢复阈值；确认、排查、观察、解决、关闭以及审计时间线。
- Vue3/TypeScript + Tailwind/shadcn-vue、Pinia、Lucide与ECharts；六区分组子菜单、可关闭工作页签、深浅主题下拉、全局搜索/时间范围、服务端筛选排序分页、事件/诊断页首下一步操作和就地人审；返回保留列表状态，重新登录续接详情，会话仅内存。设计规范见 [16](docs/16-界面设计规范.md)。
- 独立内部服务 token、公共存活检查、Flyway V1–V6、完整应用容器与 CI 配置。
- 运行手册草稿、乐观锁、发布不可变版本/段落 UUID 与 SHA-256；停用不破坏旧引用。
- AgentRun + 事务 Outbox、租约/有限重试/DEAD 明确重试、Python LangGraph + 独立 PostgreSQL checkpoint；同 runId 恢复，Java 二次核验引用。
- 报告区分观测、推断和未知，提供稳定手册引用；默认 mock，可显式配置模型 HTTP 接口，无运维写工具。
- 持久化人工审阅、批准/拒绝/过期和线下结果回填；模型调用记录支持结果复用，不确定结果停止重发。

检查新建后默认禁用，管理员需明确启用。事故人工处理需要 `ON_CALL` 及目标 `can_operate`；管理员也需显式获得这两项权限。活动事故期间冻结目标网络和检查配置，暂停检查会中断恢复观察。

## 构建与测试

需要 Java 21、Maven 3.9 GA 和 Python 3.13 项目虚拟环境；Windows x64 和 Linux x64 的测试使用固定 PostgreSQL 17.11 独立临时实例，不要求已有数据库或 Docker，也不连接其他项目服务。首次构建需要访问 Maven Central / PyPI。

Windows PowerShell：

```powershell
.\scripts\agent.ps1 Install -Dev
.\scripts\agent.ps1 Test
.\scripts\backend.ps1 verify
```

脚本优先使用项目 `.tools/java21` 和 `.tools/maven`，否则使用当前 `JAVA_HOME` 与 PATH 中的 Maven；只在当前进程内切换 Java，不修改系统设置。Maven 缓存放 `.cache/m2`，测试数据与解压后二进制放 `backend/target/tmp`，测试完成后关闭并清理临时数据实例。

已有 Java 21/Maven 的其他环境：

```sh
python3.13 -m venv agent-service/.venv
agent-service/.venv/bin/python -m pip install --require-hashes --only-binary=:all: -r agent-service/requirements-dev.lock
cd agent-service && .venv/bin/python -m pytest tests/test_graph.py tests/test_review.py tests/test_provider.py tests/test_scenarios.py -q && cd ..
mkdir -p backend/target/tmp
mvn -B -ntp -f backend/pom.xml verify
```

原始测试报告：`backend/target/surefire-reports/`；可执行 JAR：`backend/target/agentops-backend-0.1.0-SNAPSHOT.jar`。宿主机默认 Java 17 会被构建检查拒绝，不作为运行基线。

## 本地启动

**已有 IDEA 后端时：** 保留原运行配置，确认数据库属于本项目并在升级前备份；首次运行新代码会执行尚未应用的迁移至 V6。IDEA 设置 `PORT=18080`；完整步骤见根目录启动指南。本轮开发没有重启你的 IDEA 服务或迁移你的数据库。

在项目根的 PowerShell 中启动工作台（Node 22.12+，推荐本轮使用的 24.22.0）：

```powershell
cd frontend
npm ci --ignore-scripts --no-fund --no-audit
npm run dev
```

打开 [工作台](http://127.0.0.1:5174)，使用已有管理员账号登录。默认 5174 避开本机 5173 的其他项目。开发服务器只绑定本机，默认代理 `/api` 到 `http://127.0.0.1:18080`；后端端口不同时，在启动前设置 `$env:BACKEND_API_ORIGIN='http://127.0.0.1:你的端口'`。前端不需要数据库密码、pepper 或内部 token。

先创建服务目标，再创建并启用检查。默认仅公网 HTTPS；私网服务要在后端设置 `MONITOR_ALLOWED_PRIVATE_CIDRS`，并在目标表单中批准该范围内的 CIDR 和理由。禁止填写 `localhost`、IP literal 或 metadata 地址。

`npm run build` 同时执行类型检查并输出 `frontend/dist`。前端生产镜像由 `frontend/Dockerfile` 构建并由Compose启动；生产需同源反向代理 `/api`、SPA history fallback和HTTPS。`npm run preview`仅预览静态产物，不提供开发API代理。

当前 `compose.yaml` 包含业务 PostgreSQL、独立 checkpoint PostgreSQL、Java、Python、Vue 静态服务，以及 `--profile kuma` 可选服务。镜像固定版本和摘要。不要同时占用已有 IDEA 的端口；默认数据库使用独立 Compose 数据卷。

1. 将 `.env.example` 复制为 `.env`，在本地自行设置数据库密码、两个彼此不同且至少 32 字符的随机 secret、checkpoint owner/application 两个不同密码，以及至少 12 字符、至多 72 UTF-8 字节的管理员密码。启用诊断时设置 `AGENT_ENABLED=true`，保持 mock；不要提交 `.env`。
2. 按数据卷兼容说明核对卷归属、前缀及旧项目停止状态，再执行 `docker compose up --build`。只会在空用户表且明确配置 bootstrap 凭据时建立首个 `OPS_ADMIN`；已有账户不会重置。
3. API 默认 [http://127.0.0.1:18080](http://127.0.0.1:18080)，公共存活检查 [health](http://127.0.0.1:18080/actuator/health)。数据库没有宿主机端口。
4. `POST /api/v1/auth/login` 获取 token，仅由客户端内存保存；后续请求使用 `Authorization: Bearer ...`，退出时调用 `POST /api/v1/auth/logout`。

也可在明确配置的新 PostgreSQL 数据库上设置 `DB_URL/DB_USER/DB_PASSWORD`、`SESSION_TOKEN_PEPPER/AGENT_INTERNAL_TOKEN` 和 bootstrap 环境变量，再运行可执行 JAR；启动会执行 Flyway，只允许用于已确认属于本项目的数据库。

## 已实现 API

外部前缀 `/api/v1`，时间使用 UTC，分页为 `page/size`，`size` 最大 100。创建用户和目标必须带 `Idempotency-Key`；相同用户/操作/键和相同请求重放返回同一资源，不同请求返回 409。

| API | 权限与用途 |
|---|---|
| `POST /auth/login`、`GET /me`、`POST /auth/logout` | 登录、当前身份、撤销当前会话 |
| `GET/POST /admin/users` | OPS_ADMIN 查看/建立用户；响应不包含密码 hash |
| `PUT /admin/users/{id}/roles`、`PUT .../active` | OPS_ADMIN 管理明确角色列表/停用用户，变更审计 |
| `GET/POST /targets`、`GET /targets/{id}` | 管理员登记/配置视图，其他角色仅看到已授权目标 |
| `PUT /targets/{id}/access/{userId}` | OPS_ADMIN 授予目标读取/操作权限，要求理由 |
| `GET /targets/{id}/access` | OPS_ADMIN 分页查看授权；可按 userId 查询 |
| `GET /overview` | 当前用户可见目标的健康统计；过期/无数据不显示为正常 |
| `GET/PUT /targets/{id}/network-policy` | OPS_ADMIN 查看/原子修改 URL、HTTP/CIDR 审批；版本冲突 409 |
| `GET/POST /targets/{id}/checks`、`PUT .../checks/{checkId}` | 查看/新建/版本化配置，写入限管理员 |
| `PUT .../checks/{checkId}/state`、`GET .../checks/history` | 启用/暂停/禁用与分页结果摘要 |
| `GET /incidents`、`GET /incidents/{id}`、`GET .../events` | 按目标 ACL 过滤的事故与时间线 |
| `POST /incidents/{id}/transitions` | ON_CALL + 目标操作授权，版本和理由必填 |
| `GET /audit-events?actorId=...&subjectId=...` | AUDITOR 查看操作元数据，不返回目标正文或秘密 |
| `GET /internal/v1/health` | 独立内部 token；用户会话不能访问 |

OPS_ADMIN 不自动拥有 ON_CALL/AUDITOR。bootstrap 管理员需要显式授予相应角色和目标操作权限，才能承担相应职责。创建检查、手册、诊断必须带 `Idempotency-Key`；具体请求体及人工审阅/线下回填、Kuma 管理 API 见 docs/07。

## G3 本地只读诊断

以下保留 G3 的基础配置说明；当前 G4 的完整启动与验收以 [专属指南](IDEA启动与验收指南.md) 和本文末尾 G4 章节为准。

三个服务：Java 18080、Vue 5174、Python 8090。原账号继续使用，无新增默认登录密码。Python 不持有用户密码、session pepper 或 Java 数据库凭据。

1. 由数据库管理员为 Python **单独创建数据库与受限角色**，不要把业务库 URL 或 postgres 超级用户交给 Python。以下为手工示例，替换密码后在确认的本项目实例执行；本轮未执行到你的数据库：

   ```sql
   CREATE ROLE agentops_checkpoint LOGIN PASSWORD '替换为独立强密码' NOSUPERUSER NOCREATEDB NOCREATEROLE;
   CREATE DATABASE agentops_checkpoint OWNER agentops_checkpoint;
   ```

   不授予该角色业务库表权限。Python 首次启动在独立库创建 `agent_checkpoint` schema、LangGraph checkpoint 表及调用指纹表；拒绝 superuser/CREATEDB/CREATEROLE 角色。正式部署还应限制该角色的数据库 CONNECT 范围。
2. 项目根 PowerShell 安装锁定依赖，设置**当前终端**变量，启动 Python：

   ```powershell
   .\scripts\agent.ps1 Install
   $env:AGENT_CHECKPOINT_DSN='postgresql://agentops_checkpoint:替换为URL编码后的密码@127.0.0.1:5432/agentops_checkpoint'
   $env:AGENT_INTERNAL_TOKEN='与IDEA运行配置中完全相同的内部token'
   .\scripts\agent.ps1 Run
   ```

   默认只绑定 127.0.0.1，内部接口需要 token 和 `X-Request-Id`，浏览器不能直接使用；不加载根 `.env`，也不保存 DSN。不要将实际 token/DSN 提交到仓库。
3. IDEA 原运行配置保留数据库及两个 secret，另加 `PORT=18080`、`AGENT_ENABLED=true`、`AGENT_SERVICE_ORIGIN=http://127.0.0.1:8090`。备份本项目数据库后由你重启 Java 应用迁移至当前 V6。默认 `AGENT_ENABLED=false` 表示暂停分发；发起的任务会持久化排队，直到启用。
4. 工作台“运行手册”选择故障目标 → 新建草稿 → 填理由发布 → 事故详情“发起诊断” → 报告查看 6 节点、观测/推断/未知及引用。值班角色 + 目标操作授权才能发起；运行手册写入仅管理员。手册按 Markdown 标题分段最多 5 段、每段 6,000 字；草稿与发布版分别保存。

网络/服务短暂错误有限退避重试，最多 5 次后进入 DEAD；符合重试条件时，管理员修复配置后填写理由“重新加入队列”，相同 runId 恢复 checkpoint。格式/引用错误不自动重试。G4 已实现可配置 provider、人工审批与线下结果回填；模型结果不确定时禁止同任务重发，详见 G4 章节。30 场景人工标注评测和性能基准尚未执行。

## 项目目标

做一个可本地演示的轻量运维协作平台：Java/Spring Boot 管理服务目录、健康检查、事故和审计；Python/FastAPI + LangGraph 执行受控的只读诊断图；Vue 3 提供工作台；Uptime Kuma 作为可选的推送监控接收端。

Agent 只整理已有证据、检索授权运行手册并生成诊断/行动建议。MVP 不执行 shell、SSH、SQL 写入、kubectl 或自动修复。

## 阅读顺序

先读 [Codex 执行指南](CODEX_EXECUTION.md)、[章程与 PRD](docs/01-项目章程与PRD.md)，再读角色需求、[状态机](docs/03-领域模型与状态机.md)、[架构](docs/05-架构与技术选型.md)、[SQL](docs/06-数据模型ERD与SQL.md)、[API](docs/07-API契约.md)、[Agent 设计](docs/10-Agent设计与评测.md) 和 [阶段门](docs/13-测试矩阵与阶段门.md)。

## 文档目录

| 文件 | 内容 |
|---|---|
| [IDEA 启动与验收指南](IDEA启动与验收指南.md) | 你的 Windows/中文 IDEA 配置、每日启动、故障排查与当前阶段验收 |
| [01](docs/01-项目章程与PRD.md) | 项目章程、产品问题、MVP 和非目标 |
| [02](docs/02-角色用例与需求.md) | 角色、用例、功能/NFR |
| [03](docs/03-领域模型与状态机.md) | 服务、检查、事故、Agent run 状态机 |
| [04](docs/04-原创范围与模块边界.md) | 原创范围、服务职责和模块结构 |
| [05](docs/05-架构与技术选型.md) | 技术基线、拓扑与取舍 |
| [06](docs/06-数据模型ERD与SQL.md) | Mermaid ERD、PostgreSQL 表/索引建议 |
| [07](docs/07-API契约.md) | 前端 API、内部 Agent API 和示例 |
| [08](docs/08-异步消息与幂等.md) | Outbox、worker、幂等与失败恢复 |
| [09](docs/09-安全隐私与权限.md) | RBAC、目标探测 SSRF 防护、Agent 安全 |
| [10](docs/10-Agent设计与评测.md) | LangGraph 工作流、提示边界、评测协议 |
| [11](docs/11-Uptime-Kuma集成边界.md) | Push Monitor 集成、已知上游边界 |
| [12](docs/12-本地部署运维与CI-CD.md) | Compose、本地配置、运维、流水线 |
| [13](docs/13-测试矩阵与阶段门.md) | 阶段门、验收矩阵、拟定性能目标 |
| [14](docs/14-迭代发布交接与简历.md) | 迭代、发布、交接、演示和简历 |
| [15](docs/15-开源许可证与接口核验.md) | 上游仓库、许可证、版本/API 核验 |
| [16](docs/16-界面设计规范.md) | 设计Tokens、共享组件、主题和交互规范 |

`docs/verification/` 保留正式测试与依赖资料，截图精简为13张代表图；旧截图可从Git历史恢复。清理范围和历史证据说明见[测试矩阵的资料保留策略](docs/13-测试矩阵与阶段门.md)。

## 核心约束

- 每个 Agent 工具仅能读取预先注册的服务、检查结果和已授权运行手册。
- MVP 不存在可写运维工具；高影响行动只生成建议，由人在线下执行。
- Kuma Push Token 是秘密，禁止进入日志、前端、提示、审计 metadata。
- Uptime Kuma 不作为 Dawnwatch 的数据源或事实库；集成关闭/故障时 Dawnwatch 核心流程继续。
- 上游核验快照截至 2026-10-20；开发时重新核对稳定版本、许可证和接口契约。

## G4：人工审阅与线下结果回填（2026-10-21）

新任务使用 `readonly-review-v2`，默认仍为 `mock`。旧 G3 的 `readonly-mock-v1` 任务和检查点继续兼容。新增 V5 迁移；本次开发没有升级你的现有数据库，也没有重启 IDEA 服务。使用新代码前，请先备份本项目数据库，再由你重启后端应用 V5；Python 也需重启加载新版代码。端口和原账号不变：Java 18080、前端 5174、Python 8090。

验收路径：事故详情 → 发起诊断 → 等待人工审阅。值班且有目标操作权限的账号填写理由，选择“批准线下处理”或“拒绝建议”。批准先显示“决定已记录，待同步”，随后显示“待线下处理”；平台不执行命令。用户自行处理后回填“已执行／未执行／执行失败”和说明，任务结束，事故状态保持原流程。拒绝不进入回填；24小时未决的审阅过期取消。系统关闭分发时会暂停后台同步，重新启用 `AGENT_ENABLED=true` 后继续。

默认 mock 可以完整验收上述闭环。启用DeepSeek官方服务按专属指南第14节使用 `deepseek` 模式；其他支持严格JSON Schema的Chat Completions兼容服务使用下表配置：

| 配置 | Java IDEA 运行环境 | Python 启动终端 |
|---|---|---|
| `AGENT_MODEL_NAME` | 实际模型标识 | 与 Java 完全相同 |
| `AGENT_LLM_PROVIDER` | 不需要 | `openai-compatible` |
| `AGENT_LLM_BASE_URL` | 不需要 | HTTPS API 前缀，例如服务商提供的 `/v1` 地址 |
| `AGENT_LLM_API_KEY` | 不需要 | 该服务的密钥，仅服务端保存 |

本机模型只有明确设置 `AGENT_ALLOW_LOCAL_MODEL_HTTP=true` 才允许 HTTP，且仅限 localhost/127.0.0.1/::1。不跟随重定向，不使用系统代理；单次模型调用总预算25秒、响应64KiB。没有配置时保持 mock，不会自动调用外部服务。真实模型的费用、可用模型名称和业务数据出站需要你自行决定；本次未调用任何付费或外部真实模型。

模型请求前先持久化调用记录，已验证的结果先写入独立checkpoint库，再交给图继续。进程在图检查点提交前崩溃时可复用原模型结果。如果响应超时、进程退出或数据库故障导致调用是否完成不确定，任务返回 `AGENT_MODEL_OUTCOME_UNKNOWN`，停止自动重试，管理员也不能强制将同一任务重发。先核对模型服务的调用记录，需要重新诊断时再从事故详情明确发起新任务。已知的连接失败或HTTP 429/5xx仍按有界策略重试；不宣称与远端服务能组成跨系统的 exactly-once 事务。

开发检查：`scripts/agent.ps1 Test`（图、HTTP适配和30个合成场景），`scripts/backend.ps1 verify`（含独立临时PG与Java→Python闭环），`npm --prefix frontend run build`。合成回归不等于人工评分、真实模型准确率或性能基准。

## G5：可选 Kuma 与容器交付

管理员在“目标详情 → Kuma 推送”配置已预建 Monitor 的映射，先测试通过再启用。后端只保存环境变量 secret reference；实际 token、请求 URL 和远端正文不进入 API、审计或投递记录。部署 origin、HTTP/私网 CIDR 单独批准；Kuma 使用独立 DNS 池，重新校验全部地址并固定连接 IP，拒绝重定向。

后台独立轮询已提交的检查样本，不参与核心检查/事故事务。按集成串行租约和检查样本唯一键处理，领取时再次验证最新有效样本；UNKNOWN/过期样本不推送。临时故障有界重试，旧待发送样本被新状态替代；Kuma Push 是至少一次投递，不宣称远端 exactly-once。

完整启动、可选 Kuma 的变量与验收见 [专属启动指南第 8 节](IDEA启动与验收指南.md#8-g5-新增kuma-与-compose按需使用)。`scripts/recovery-drill.ps1` 只在无网络临时容器验证合成备份恢复，不操作用户库。日常停止 Compose 保留数据卷，不使用 `down --volumes`。
