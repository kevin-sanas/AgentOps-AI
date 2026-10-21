# Codex 执行指南

## 交付目标

在独立空仓库 `agentops-platform` 中实现本包定义的可运行 MVP。本文档包本身不是应用代码或已部署系统。

## 建议结构

```text
agentops-platform/
  backend/                 # Spring Boot 模块化单体
  agent-service/           # FastAPI + LangGraph
  frontend/                # Vue 3 + TypeScript
  db/migration/            # Flyway SQL
  deploy/                  # Compose/容器配置
  docs/                    # 本工程文档及实测记录
  eval/                    # 虚构、标注、版本化诊断案例
  .github/workflows/
```

## 阶段顺序

1. G0：复核依赖 GA 版本、许可、安全公告和 Uptime Kuma Push 接口边界，形成锁文件/镜像 digest。
2. G1：实现 PostgreSQL 迁移、登录/RBAC、服务目录、审计、健康检查端点。
3. G2：注册 HTTP 检查、调度、结果历史、事故创建/确认/解决和前端页面。
4. G3：实现运行手册、AgentRun 数据、Outbox 与 FastAPI/LangGraph 内部契约；只读工具先使用 fixture。
5. G4：加入真实只读检查历史/运行手册上下文、结构化诊断、人工审批状态和评测集。
6. G5：加入 Kuma Push adapter、Compose 可选 profile、CI、故障恢复与安全测试。
7. G6：按验收矩阵记录真实检查、演示和发布资料。

每门的入口和通过标准见 `docs/13-测试矩阵与阶段门.md`。任何“已通过”都要有实际运行记录；拟定目标要与实测值分开。

## 实现规则

- Spring Boot 是身份、权限、服务、检查、事故和审批的事实来源；Agent Service 不直连公开业务 API。
- Agent Service 只允许内部服务认证；浏览器不能直接访问 Agent 内部端点。
- 每个 LangGraph 节点只拿最小必要的数据；工具按注册资源 ID 访问，不接受 LLM 提供任意 URL/主机/命令。
- 不实现 shell、SSH、kubectl、SQL 写操作和自动修复。将来新增写工具须另行设计权限、审批、审计、沙箱和回滚。
- 所有用户输入、监控消息和日志片段都视为不可信文本；不能覆盖系统约束。
- Java→Agent 通过 Outbox + 内网 HTTP；重复请求必须按 `runId` 幂等。
- Kuma 只经 `UptimeKumaPushAdapter` 写 Push heartbeat；人工预先创建 monitor；不访问内部 Socket.IO。
- 目标探测仅针对管理员登记、策略批准的服务目标；默认禁止云元数据地址；执行 DNS/重定向/速率限制。
- 模型默认 `mock`，离线可演示；密钥由环境变量/secret store 提供，不写入仓库和日志。

## 2026-10-20 启动实施计划（G0/G1）

**Goal：** 在当前项目根目录建立可运行、可测试的 Java 安全骨架；后续逐门扩展监控和 Agent。当前目录作为 `agentops-platform` 的仓库根，保留现有规格路径。

**Architecture：** Spring Boot 模块化单体，JDBC + Flyway 管理 PostgreSQL；不透明 bearer session、服务端 RBAC 和目标 ACL。内部服务 token 独立于用户会话。Python、Vue、Kuma 在后续阶段接入。

**Tech Stack：** Java 21、Spring Boot 4.1.1、PostgreSQL 17；构建使用 Maven 3.9 GA。宿主机默认 Java 17 不作为项目编译基线。

**Spec：** 本文件及 docs/01–15；尤其 02、06、07、09、13。用户已要求按现有文档开始实施，本轮直接执行此首阶段计划。

### 全局约束与实施决策

- 所有源码、缓存、下载工具和测试临时目录限定在当前项目；不修改全局 Java/Python 配置。
- 此轮不接触已有数据库、其他项目容器或生产服务。迁移验证只使用新建的临时 PostgreSQL 测试实例。
- 默认拒绝；管理员配置权不自动授予 ON_CALL；读目标需 ACL（管理员配置视图除外）。
- 禁止明文会话入库、默认演示密码、日志输出秘密、任意 URL 探测、shell/SSH/自动修复。
- 首个版本只允许 HTTPS 公网域名登记；HTTP 和私网策略在 G2 完整实现后再开放。登记不发送 HTTP 请求。
- G1 创建操作按用户、操作、幂等键及请求 hash 原子去重，重放时重新鉴权。
- 现有规格中的 DDL 是基线，按阶段拆为不可变 Flyway 迁移；不提前创建尚未实现模块的空表。

### Task 1：可重复构建和测试环境

- [x] 核验本阶段 GA/许可及 Java 21、Maven、PostgreSQL 测试二进制，锁定直接依赖和已核验镜像。
- [x] 新增 `backend/pom.xml`、`backend/src/main/resources/application.yml`、项目内构建脚本和 `.env.example`。
- [x] 生成有效运行依赖清单 `backend/dependencies.lock`；在 `docs/15-开源许可证与接口核验.md` 留下来源、日期和未完成检查。

### Task 2：身份、审计和内部鉴权（测试先行）

- [x] 新增真实 PostgreSQL 上的 `backend/src/test/java/dev/agentops/FoundationTest.java`：匿名拒绝、错误密码、hash session、撤销/过期/停用、角色立即生效、内部 token 隔离、秘密不出现在响应和审计。
- [x] 先运行测试，记录缺失功能导致的失败；再实现 `identity`、`access`、`audit`、`shared`。
- [x] `db/migration/V1__identity_and_audit.sql` 创建用户/角色/会话/审计；`V2__targets_and_idempotency.sql` 创建目标/ACL/幂等。
- [x] 仅在显式配置 bootstrap 用户和密码、且用户表为空时建立首个 OPS_ADMIN；无内置密码。

### Task 3：服务目录与安全边界（测试先行）

- [x] 测试管理员创建/授权、跨目标拒绝、observer 不能写、管理员不自动获得值班权、分页上限、幂等重放/冲突和审计原子性。
- [x] 测试 URL 拒绝 userinfo、IP literal、非 HTTPS、非标准端口、query/fragment、DNS 私网/metadata/loopback 和混合地址。
- [x] 实现 `targets` 服务及 `/api/v1/targets`、`/targets/{id}`、`/targets/{id}/access/{userId}`；内部健康检查只做鉴权，不提前开放业务内部 API。
- [x] 完整跑编译、单元与 PostgreSQL 集成测试，检查 Flyway 第二次启动不重复迁移。

### Task 4：启动与交接

- [x] 新增 backend 容器配置和只含已实现服务的 Compose；数据库不对宿主机暴露，API 只绑定 loopback。
- [x] 新增 CI 后端检查配置；保留原始测试结果并归档可复验摘要。
- [x] 更新 README 和 `docs/13-测试矩阵与阶段门.md` 的真实结果；未测项标为 NOT RUN，G2–G5 保留未开始。
- [ ] 网络可达后执行容器构建/启动，远端 CI 与 Linux 平台测试；完成全量 SBOM/传递依赖许可与漏洞扫描，才能关闭完整 G0。

### Review Focus

- 会话失效、用户停用或角色撤回后，旧 token 立即失去相应权限。
- 内部 token 不能访问业务 API，用户 token 不能访问内部端点。
- 幂等键冲突及数据库写失败不留下半条目标/授权/审计。
- DNS 全部结果均需通过策略，禁止只检查第一个 IP。
- 错误响应和审计仅含稳定错误码与资源标识，不存密码、token、URL 或请求体。

### 实施记录

初始状态：17 份 Markdown，尚无应用代码或 Git 元数据。本机 Java 17.0.20.1、Python 3.13.5、Node 24.22.0；Docker Server 29.8.2。公开 Maven Central 可访问；Docker Hub registry 直连超时，镜像锁与容器 smoke 待确认。后续结果在此表更新。

| 任务 | 状态 | 实际证据 |
|---|---|---|
| Task 1 | 本阶段基线完成；完整 G0 待补 | Java21/Maven/PG17 已实跑，镜像 digest 固定，运行依赖清单已解析；全量扫描与后续组件锁待补 |
| Task 2 | 完成 | 不透明会话、RBAC、内部鉴权、bootstrap、用户管理均有真实 PostgreSQL 测试 |
| Task 3 | 完成 | ACL、幂等并发、DNS 边界、审计失败回滚、重复迁移通过；完整 verify 46/46 |
| Task 4 | 配置与本地验证完成；容器/远端 CI 待补 | Compose config --quiet 通过；容器 build/up 与远端 CI NOT RUN |

最终本地检查：2026-10-20 15:09:41（Asia/Shanghai），`.\scripts\backend.ps1 verify` 返回 BUILD SUCCESS；46 项测试、0 失败、0 错误、0 跳过，耗时 34.870 秒（不是性能测量）。

独立只读评审及修复：请求指纹从无密钥 SHA-256 改为用途隔离的 HMAC-SHA256；登录读取用户使用 `FOR SHARE`，防止停用漏撤销并发新会话；用户管理使用事务 advisory lock 保护最后活跃管理员。以上均先观察回归失败，再修复，并纳入最终 46 项检查。另补齐未知路由/不支持方法的 404/405 Problem 响应和审计故障注入回滚检查。评审已静态复核三项修复。

实施决策与代价：当前目录直接作为仓库根（若改目录名需同步本地路径）；首阶段仅开放公网 HTTPS 登记（HTTP/私网功能需 G2 扩展）；安全评审后修改原 SHA-256 指纹规范（轮换 pepper 时旧幂等键在保留期内需换新键）。没有未处理的本轮评审缺陷；后续阶段及环境限制见上表。

## 2026-10-20 G2 实施计划

**Goal：** 按既有规格完成“登记目标 → 定时检查 → 故障事故 → 人工处理 → 恢复观察”的后端与 Vue 页面闭环。
**Architecture：** Java JDBC 事务和 PostgreSQL 租约承载检查与事故事实；Apache HttpClient 连接绑定已校验的 DNS IP，TLS 校验原域名；Vue 同源代理，仅内存保存 bearer token。
**Tech Stack：** 继续 Java 21 / Spring Boot 4.1.1 / PostgreSQL 17，新增 BOM 管理的 HttpClient5；Vue 3.5 / TypeScript / Vite 固定实际稳定版本及 npm lock。
**Spec：** docs/02、03、06、07、09、13；本段补充已有设计的实施选择。用户已明确继续下一阶段，按批准的项目规格执行。

### 约束与设计选择

- 保留当前 IDEA 服务和用户创建的数据库；仅独立临时 PostgreSQL 执行迁移/回归，不重启用户服务。
- HTTP 必须逐目标显式批准；私网 CIDR 同时受部署级允许网段约束。metadata、loopback、link-local、multicast 和保留地址永远不可放行。目标创建支持 URL/HTTP/CIDR/理由一次原子登记；更新同样原子审批。
- 连接前重新解析且校验全部 DNS 地址，使用固定解析结果建立连接；不跟随重定向，不使用代理/认证/cookie，不存正文；默认 3 秒、最多 64 KiB，GET/HEAD，间隔至少 60 秒。
- 每目标可有多个检查。活动事故冻结当时 ACTIVE 检查的配置版本和恢复阈值；活动事故期间拒绝修改目标 URL/网络策略和检查阈值。暂停不能让未恢复检查从恢复条件中消失。
- 连续失败打开同目标唯一活动事故。恢复达阈值只记录恢复，人工状态不可跳过；INVESTIGATING 自动进入 MONITORING 后，所有快照检查再各成功达到恢复阈值，才自动 RESOLVED。再次失败退回 INVESTIGATING；CLOSED 不复用。
- 单实例最多 4 个探测工作者、每域名 1 个并发、最多每秒领取 4 个任务；数据库租约与幂等执行记录支持崩溃后重新领取。过期工作者不能提交结果。
- 所有配置/人工事故操作审计；ON_CALL 且 can_operate 才能操作事故，OPS_ADMIN 不能自动代替 ON_CALL。

### Task A：G0 依赖证据

- [x] 固定 CycloneDX Maven 插件，生成包含测试传递依赖的 SBOM/许可清单；用 OSV 版本查询保存漏洞结果及未覆盖范围。
- [x] 检查受限容器网络；未实跑容器/远端 Linux CI 保留 NOT RUN，不把本地 API 成功等同完整 G0。

### Task B：监控配置与出站安全

- [x] V3 增量迁移：版本化网络批准、检查定义/租约、结果、事故/时间线及恢复快照。
- [x] 扩展 targets 配置 API，增加 GET/POST/PUT 检查、状态切换、历史分页；原子审计、幂等、版本冲突和 ACL。
- [x] 实现 CIDR/DNS 策略、HttpClient5 IP 绑定、超时、响应上限、无重定向和资源释放；安全测试覆盖混合 DNS/重绑定/保留地址/过大响应/TLS 主机名。

### Task C：调度与事故

- [x] SKIP LOCKED 领取/租约恢复/过期结果拒绝；同域并发与领取速率上限；冻结配置、幂等提交、连续计数。
- [x] 实现 IncidentService 与列表/详情/事件/版本化 transitions API；真实 PG 验证多检查恢复、并发去重、状态冲突、权限、审计失败回滚和租约恢复。

### Task D：Vue 工作台

- [x] 实现登录、概览、目标配置/检查历史、事故队列与时间线；完整空态/加载/错误/过期/无权和版本冲突。
- [x] 密码/token 不进持久化浏览器存储；退出撤销 session。补齐开发代理/构建/启动说明与前端锁文件。
- [x] 类型检查、构建和浏览器操作验证，复跑后端全套测试；更新现有文档及正式证据。

### Review Focus

DNS 二次解析是否绕开校验；过期租约的慢结果能否污染新配置；两个检查同时故障是否产生重复事故；暂停失败检查是否伪装恢复；撤销 ACL 后写操作是否仍可继续。各项均需独立回归证据。

Ruling: 私网目标直接以扩展 POST /targets 原子审批登记，替代“先建公网占位目标”的初稿 — 用户应能直接登记实际目标，且无需伪造 URL — G1 五字段请求用兼容 fingerprint 保留 24 小时幂等重放。
Ruling: 配额在 PostgreSQL 中保存 4 个全局槽、每域名租约和每秒 4 次领取预算，目标间隔至少 60 秒；租约 60 秒 — 多实例/崩溃重领也不能绕过出站配额 — 停机恢复最多额外等待约 60 秒。

### G2 中途记录（历史，最终结果见下节）

- 已生成 baseline 112 组件 SBOM/许可与 OSV 结果，命中 3 个组件的 10 条公告；固定同系列 Tomcat 11.0.27、Jackson BOM 3.1.8 后，含 HttpClient5 的 115 组件复查零版本命中。原始/更新后证据分别在 `docs/verification/dependencies-2026-10-20*`；非容器 OS/原生库扫描，许可声明不等于完整原生二进制许可审计。
- `scripts/dependency-audit.ps1` 可重生成 SBOM、查询 OSV、记录许可；查询不完整退出 1，有版本命中退出 2。完整 G0 仍保留容器/远端 CI 等未验证项。
- V3、新检查/策略 API、事故 API、配额/租约、聚合恢复和安全 HTTP 传输已实现；17 项 MonitoringApiTest 已单独通过（后台任务验证也已通过），另有 4 项私网策略、4 项 HTTP 传输和原 26 项 URL 策略。
- 全套首次运行 71 项中 70 通过；唯一失败是旧 Flyway 用例仍写死两条迁移，已更新为明确校验 V1/V2/V3，尚需最终全套重跑。未以部分结果宣布整个 G2 完成。
- 前端尚待实现、渲染核验；TLS 主机名/分块过大响应还需补专项传输用例；最终独立评审、全套验证和文档交接尚待完成。


### G2 最终交付与验证（2026-10-20）

Task A–D 的 G2 交付已完成。完整 verify 于 17:49:57 返回 BUILD SUCCESS：81 项、0 失败/错误/跳过；其中 MonitoringApi 21、私网 5、HTTP 7、TLS 2，原 G1 46 项保留。最后补强一条恢复断言后单项复跑通过。前端类型检查/构建通过；npm audit 零命中；实际安装的 47 个构建/运行包许可正文随 dist 保留。详见 docs/13 与 docs/15。

独立只读评审的四项 P2 均已先复现再修复，且复核关闭：租约校验使用取得目标锁后的实际时钟；概览仅接受当前目标与检查版本证据；3fff::/20 文档专用地址阻断；检查编辑与状态冲突提供真正的刷新入口。补充时间线持久化递增序列，避免同事务时间戳相同导致乱序。未留下已知重要评审项。

Vue 采用中文浅色工作台：固定导航、表格信息密度、明确文字状态、绿色用于常规动作、异常色用于故障；不依赖外部字体/图片。1440 桌面与 390 窄屏均实际渲染；窄屏表格内部滚动，页面无横向溢出。检查/事故均有理由、当前版本和错误恢复；先前五张截图经独立评审无重大布局问题，修复后又保存冲突与时间线新截图。完整事故确认→排查→观察→解决→关闭、目标/检查创建、授权、角色表单、observer 资源隔离、刷新回登录均经浏览器操作；两会话并发检查修改也验证了 409→刷新最新→保存成功。验收数据全部为独立临时 fixture。

启动与接口文档已更新 README、docs/07、09、12、13、15。后端 dependencies.lock、前端 package-lock、SBOM/OSV/npm audit、截图与验证摘要已归档。完整 MVP 的 Agent/运行手册/Kuma/前端生产容器属于 G3–G5，本轮未实现；Docker registry 再试超时，容器 build/up、远端 Linux CI 和性能指标明确 NOT RUN，不将 G0 标为完整通过。

没有修改用户级配置，没有重启用户 IDEA 服务、迁移现有数据库、发布或推送。新后端首次启动会应用 V3，用户升级前应备份该项目数据库。仓库尚无提交与远端；所有源码保留当前工作区供审阅。

### G2 人工验收与异常反馈补充（2026-10-20）

用户已反馈 G2 人工验收完成，并授权继续 G3。排查期间只读核实：本地目标前两次503，第三次已恢复200，未达到三次连续失败，因此没有正式事故；这不是已证实的队列刷新故障。为避免空队列掩盖未达阈值异常，新增按目标ACL过滤的 GET /incidents/pending-alerts，返回当前版本、新鲜DOWN证据的连续失败/阈值、状态码和下次计划；恢复清零、暂停/过期/版本变化或已入活动事故时不返回。页面增加异常进度、5秒自动刷新和回到页面立即刷新。后台完整verify 83/83通过，前端最终构建通过；浏览器已观察2/3自动移入事故列表，无需点击刷新。390窄屏修正表格定位后页面宽度等于视口382，无页面横向溢出；控制台无warn/error。截图 incident-pending-progress.png、incident-queue-mobile.png 位于 docs/verification/g2-ui。独立测试前后端已关闭，未改用户运行服务。

## G3 只读诊断 Implementation Plan（2026-10-20）

**Goal:** 已授权值班人员从事故发起持久化、幂等的 mock 诊断，查看可追溯至已发布手册与检查事实的报告。
**Architecture:** Java 保存业务事实和事务 Outbox；Python LangGraph 使用独立 PostgreSQL 数据库保存 checkpoint，同 runId advisory lock 串行执行。HTTP 在租约事务外，Java 再校验结果引用才保存终态。
**Tech Stack:** Java21/Spring Boot4.1.1/JDBC/Flyway V4；Vue3；Python3.13、FastAPI、LangGraph/PostgresSaver、psycopg。固定包与 hash lock；默认 mock，无外部模型调用。
**Spec:** docs/06、07、08、09、10、13；用户已完成G2验收并明确授权继续下一阶段，直接在当前目录按既有批准规格执行。

### Task E：不可变运行手册
- [x] 在 AgentApiTest 先观察缺失接口失败：草稿创建幂等、发布生成稳定section UUID/hash、新版本不覆盖历史、禁用不破坏历史引用、ACL/角色/审计回滚。
- [x] V4迁移新增 runbook、runbook_version、runbook_section；RunbookController/Service 实现目标列表/创建、草稿更新、发布、启停、历史版本。发布后只读，更新草稿需revision乐观锁，发布按标题分段最多5段、单段最多6000字，全文最多24000字。
- [x] 单项回归绿后运行后端完整verify；V1–V3保持不变。

### Task F：AgentRun与事务Outbox
- [x] AgentApiTest先写并观察发起幂等/目标操作权限/冻结上下文/撤权后读取拒绝；Outbox测试覆盖并发领取、过期租约、非法引用、重试和DEAD显式重试。
- [x] agent包拆分 AgentContracts、AgentService、AgentOutbox、AgentClient、AgentDispatcher、AgentController。新run+steps+Outbox与审计同事务，DTO只含30分钟内最多20条检查事实和5个已发布段落，无URL或秘密。
- [x] POST /incidents/{id}/agent-runs 202；GET列表/详情；OPS_ADMIN显式retry DEAD。周期3s、有界2个工作者，SKIP LOCKED租约90s，最多5次退避2/10/60/300s加抖动；202正在执行每3秒对账不占失败次数。终态和Outbox完成原子提交，过期租约拒绝提交。

### Task G：可恢复Python图
- [x] pytest先观察契约/鉴权/大小/引用/无证据失败；实际PG集成验证checkpointer持久化、同run幂等、并发202、checkpoint恢复和业务库隔离。
- [x] agent-service/agentops_agent 按models、graph、store、app拆分。6节点 StateGraph；同 runId=thread_id，advisory lock，多次invoke恢复checkpoint，拒绝同ID不同DTO；整图45s预算，同步请求，无后台fire-and-forget。
- [x] agent-service锁文件、项目venv/cache、启动/测试脚本和CI；只连接显式配置的独立checkpoint数据库。mock结果仅描述观测和未知，引用需命中冻结输入，文本纯文本显示，无命令执行能力。

### Task H：工作台与交接
- [x] 运行手册页面列表、草稿/发布/历史只读、冲突刷新；事故页面发起诊断和任务列表，诊断报告页进度/事实/假设/证据与mock标签；保持现有中文浅色设计，补空态错误态。
- [x] Java→Python→checkpoint→Java→浏览器端到端在独立fixture实跑；实际检查桌面/窄屏、权限、重试。补依赖许可/SBOM/漏洞检查，未运行项明示。
- [x] 完整verify/pytest/build通过后，由fresh-context reviewer一次整体只读评审，修复重要项并复跑；更新现有文档，不自动提交/发布，不操作用户现有数据库。

### Review Focus
撤权与发起并发必须串行；重放必须重新鉴权；禁用手册不影响旧引用但不可检索新run；HTTP超时后不重复业务写；checkpoint恢复不能被新body覆盖；不可信手册文本不得改变执行能力；相同section必须匹配version与hash。

Ruling: G3仅提供mock只读图，真实模型、interrupt审批/人工结果属于G4；G3保留明确状态/API扩展边界，不提供伪审批按钮。收益是当前可独立验收，代价是真实模型判断能力仍待下一阶段。

### G3 最终实施与评审记录

Task E–H完成。版本化手册、AgentRun/AgentStep、事务Outbox、Python StateGraph与独立PostgreSQL checkpoint、中文报告/引用和管理员元数据恢复入口已实跑。最终完整verify于2026-10-20 20:43:40返回BUILD SUCCESS：98/98、0失败/错误/跳过；Python图10/10，Java集成内Python真实PG3/3；前端最终build/typecheck通过。证据见docs/13与docs/verification/g3-validation-2026-10-20.json。

Final: fixed GET+POST HTTP预算叠加 — AgentClientTest.getAndReinvokeShareOneDeadlineRatherThanDoublingTheLeaseBudget RED→GREEN，整次共享60秒。
Final: fixed advisory lock并发分支漏核输入指纹 — test_postgres_checkpoint_resume_and_concurrent_advisory_lock 不同输入 RED→GREEN，锁竞争前原子注册/比对。
Final: fixed 纯OPS_ADMIN无恢复入口 — pureAdministratorCanRecoverDeadDeliveryWithoutReadingReportEvidence RED→GREEN，仅暴露DEAD元数据，不扩大报告权限。
Final: fixed 撤权等待后旧角色快照仍能创建任务 — roleRevokedWhileWaitingForUserLockCannotCreateAnAgentRun RED→GREEN，用户锁与角色检查拆成两个语句。
Final: minor fixed 新建手册修改后旧幂等键无法恢复 — 复用useRequestKey按内容/目标变化换键，原内容重试继续同键；前端构建及正常创建/发布浏览器流程通过。

首次最终verify为97/98，唯一超时测量用例把HttpClient冷启动误计入请求截止；修正测试测量起点，保留原150ms请求期限与600ms断言，最终98/98。没有二次独立评审或自动提交。49个Python运行分发包OSV零已知版本命中，许可/正文均归档；原生库完整清单和G5分发仍待核验。

浏览器已实测发布→后台诊断→5条稳定引用、桌面/390窄屏、观察员隔离和纯管理员DEAD重试→实际COMPLETED/DONE。测试服务/临时凭据已关闭清理。当前IDEA实际服务保持原样，用户升级前需备份项目数据库并手动应用V4；Python独立数据库与启动步骤见README。真实模型、人审/线下回填、Kuma、完整Compose/远端CI/性能和恢复演练仍未完成，未标为已验证。

## G4 人工审阅与模型接入 Implementation Plan（2026-10-21）

用户反馈G3验收完成并授权下一阶段；原有服务/数据库保留，不运行V5到现有库。基于docs03/06/07/09/10/13执行，继续当前目录本地实现，不自动提交/部署。

**Goal:** 完成可恢复的诊断→等待审阅→批准/拒绝/过期→线下结果回填闭环，并实现可配置的真实模型HTTP适配、结构/引用/风险校验及30场景自动回归。
**Architecture:** Java事务保存ApprovalRequest、报告hash与ManualActionReport，复用单run Outbox行的INVOKE/RESUME/CANCEL阶段。Python LangGraph interrupt/Command(resume)和PG检查点保存等待/恢复；批准不执行任何运维工具。Vue提供明确的人审/回填表单。
**Tech Stack:** 原版本Java21/Spring4.1.1、Python3.13/FastAPI/LangGraph/PostgresSaver、Vue3；复用已锁定httpx0.28.1，无新SDK；官方结构化输出和LangGraph中断契约已核对。

### Task I：持久化人工流程
- [x] V5增量迁移：扩展AgentRun状态/报告hash，ApprovalRequest唯一run、24h到期、版本、决策人/理由/hash，ManualActionReport唯一run；Outbox增加phase/payload，V1–V4不修改。
- [x] AgentApiTest（复用fixture）先RED：WAITING报告/引用冻结、ON_CALL+目标操作权限、同决定重放/冲突、并发只一次、审计失败回滚、撤权拒绝、到期拒批和取消、批准后才能回填、回填幂等和失败/未执行不伪装成功。
- [x] ApprovalService/Controller提供approval/manual-action-report，补Outbox等待/恢复/取消和3s到期清理；HTTP仍事务外，旧租约不可提交，纯管理员保留仅元数据重试。

### Task J：图中断与只读模型
- [x] Python测试先RED：新v2图WAITING_HUMAN，重建Store后resume；同决策幂等、不同决策409、取消和过期不继续；拒绝不执行finalize；G3 v1旧checkpoint仍可查询/恢复。
- [x] graph增加review节点，以interrupt保存等待；Store持久化决策指纹/取消元数据，恢复不再次调用analyze。内部resume/cancel严格鉴权/请求ID/64KiB/未知字段校验。
- [x] provider.py实现mock与OpenAI兼容Chat Completions HTTP适配，服务端显式配置baseURL/model/key才启用；25s总预算/64KiB响应/无重定向/无代理，无写工具。脱敏输入和输出敏感内容拒绝，模型不能改变run/graph/model身份或伪造引用，风险措辞不许降级为LOW。
- [x] 30个虚构场景覆盖500/超时/偶发/持续/恢复/无手册/冲突/提示注入/伪引用/缺字段/敏感输出/模型超时；只作自动回归，不伪称人工评分、真实模型效果或性能成绩。

### Task K：工作台与验证
- [x] 报告显示实际provider、等待/决策待同步/批准待线下/拒绝/过期状态；批准按钮明确不执行命令；只有APPROVED_FOR_MANUAL出现EXECUTED/NOT_EXECUTED/FAILED+说明回填；陈旧版本/无权限/网络错误可恢复。
- [x] 独立PG真实Java→Python→interrupt→审批→resume→回填端到端与浏览器桌面/390窄屏；完整旧回归/类型构建通过。
- [x] 一次fresh-context只读整体评审，重要项RED→GREEN修复并完整复跑；更新既有README/契约/安全/测试/依赖文档与证据，清理临时服务数据。

### Review Focus
审批与到期竞争、WAITING报告重复投递、Java已决策而Python未确认时崩溃恢复、拒绝/取消不重调模型、旧G3checkpoint兼容、原发起者与审批者撤权、报告不可替换/假引用、超时与外部服务故障不丢监控/事故。

Ruling: G4保留G3 readonly-mock-v1兼容，新建任务使用readonly-review-v2并冻结modelName；所有requiresHumanReview建议进入人工等待（mock默认也进入），批准只记APPROVED_FOR_MANUAL，真实线下动作由用户回填。真实模型配置缺失时仍可完整验收mock人审流程，付费/外部业务数据调用不由开发测试隐式启用。

### G4 实现验证与评审输入（2026-10-21）

Task I: complete — V5、ApprovalService、规范approval/manual-action-report路由、INVOKE/RESUME/CANCEL Outbox和过期处理。旧V1–V4未编辑。人审E2E先失败（COMPLETED而非WAITING）后通过；并发相反决定、只读值班、审批者撤权、过期撤权取消、审计原子回滚、回填失败/未执行和幂等冲突均验证。

Task J: complete — v2 graph interrupt/Command、PG决定/取消持久化、兼容G3 canonical hash；先3条RED后恢复测试通过。provider.py复用httpx实现严格schema、25秒绝对截止、64KiB、无代理/重定向；14条provider先RED后通过。30个合成场景单独归档，UNKNOWN摘要遗漏补RED→GREEN；Java敏感输出另补RED→GREEN。没有外部模型调用或真实效果评测。

Task K: implementation complete; final review pending — 57项Python图/模型/场景通过；真实PG8项通过，包含持久化决定提交后重启且模型调用仍只有一次。完整Java verify于13:48:42 BUILD SUCCESS，104项0失败0错误0跳过，1分23秒；前端typecheck/build通过。浏览器已观察自动等待→决定待同步→批准待线下→NOT_EXECUTED回填COMPLETED、拒绝后finalize SKIPPED、事故保持原状态、390宽度382/382且控制台无warn/error；截图见g4-ui。后续只运行必要修复验证和最终整体回归。

Ruling: 使用规格既定的 `/approval` 与 `/manual-action-report` 路由，新增approvalRequestId/version乐观锁字段；不引入临时decision/manual-report别名 — 避免后续API漂移。
Ruling: 启动器显式将java.io.tmpdir指向项目target/tmp — 防止手动运行UiPreview沿用系统缓存目录。首次启动已停止，后续实例均使用项目内临时目录；未删除用户系统缓存。

Completion audit 补充：T-09 不能仅以provider单元测试支撑E2E隔离。新增 `modelHttpOutageKeepsIncidentAndResumesSameRunAfterRecovery`，使用真实Java→Python→本机HTTP模型fixture：503只将Outbox退避PENDING，事故OPEN、原run保留；HTTP恢复后同run第二次请求进入WAITING_HUMAN，模型标识fixture-model冻结。16:06:21专项BUILD SUCCESS；无外部模型、真实密钥或业务数据。PythonFixture显式默认mock，避免从终端继承真实模型配置。

Final review: 1 Important，0 Critical — 首次模型返回而analyze checkpoint未提交时可能再次请求。原review worker在续接时暂时未出现在live清单，恢复后立即中断误启动的替代worker；只采用原review_g4的一份最终结论，没有第二轮评审。
Important fix RED→GREEN: 新增真实PG两种故障注入（模型结果提交失败 / 图checkpoint提交失败），新建model_calls.py持久化STARTED/COMPLETED/RETRYABLE；复用已验证结果，完成不明则MODEL_OUTCOME_UNKNOWN停止同run重发。模型连接失败与读取超时区别、Java稳定错误码先RED后通过；59项Python单元、PG10及真实HTTP503恢复专项通过。管理员不可强制重发与UI提示进入最终整体回归。
Ruling: 不用文档收窄来掩盖重复调用窗口，增加调用journal并明确不确定状态 — 远端HTTP与PG无法原子提交，宁可让用户核实后明确创建新run；已知失败仍按原有策略恢复。

Task K: complete — 最终完整verify于16:22:19 BUILD SUCCESS，107项0失败0错误0跳过；59项Python单元与11项真实PG通过。模型journal故障注入覆盖请求前、结果提交前、图checkpoint提交前；已保存结果复用，其他不确定情况不自动重发。类型检查/生产构建、桌面/390界面与“不确定结果不可重试”均实际验证。原始报告与截图归档g4-tests/g4-ui，汇总g4-validation-2026-10-21.json。临时服务和凭据清理，用户库和IDEA服务未动，无提交/推送/部署。真实模型效果、性能、Linux/容器和G5明确NOT RUN。
Ruling: 审批测试复用已有AgentApiTest的独立PG/HTTP fixture，不另建ApprovalApiTest类 — 减少重复启动与辅助代码，保留全部审批场景；本轮AgentApiTest共14项。

### 本地版本保存与专属启动指南（2026-10-21）

用户要求提供适合本机中文 IDEA 的启动 Markdown，并随项目更新，随后用 Git 保存当前版本。新增根目录 `IDEA启动与验收指南.md`，覆盖本机路径、Java/Python/Vue 启动、环境变量、Read-Host 分步输入、独立 checkpoint 库及 G4 验收；项目 `AGENTS.md` 登记启动相关变更须同步维护指南。README 修正至 G4/V5 并增加指南入口。指南不写入实际密码、token 或带凭据 DSN。

本任务仅修改文档与维护约定，核对实际配置、路径和 Git 暂存内容后保存本地初始版本；不推送、不重启用户服务、不执行用户数据库迁移。G4 测试沿用既有归档记录，文档任务不声称重新运行完整应用回归。
