# 你的 AgentOps AI：IDEA 启动与验收指南

更新日期：2026-10-23。适用版本：G5 + Dark-first UI/UX 工作空间、处置导航修正、G6核心自动验收及一键交互演示，业务库迁移至 V6。本文按你的 Windows、中文 IDEA 和项目位置编写；以后修改启动配置、端口或验收流程时同步更新本文。

## 1. 先记住这些位置

| 项目 | 你这台电脑的配置 |
|---|---|
| 项目根目录 | `E:\javaProjects\AgentOps AI` |
| IDEA 打开的目录 | 上面的项目根目录 |
| Maven 项目 | `backend/pom.xml` |
| Java 启动类 | `dev.agentops.AgentOpsApplication` |
| Java / Maven | Java 21 / Maven 3.9.x |
| Python | Python 3.13，项目内 `agent-service/.venv` |
| Node.js | >= 22.12.0；本次开发使用 24.22.0 |
| PostgreSQL | 本机现有实例，以下示例使用 5432 |
| 业务数据库 | `agentops` |
| Python 独立数据库 | `agentops_checkpoint` |
| 后端 | `http://127.0.0.1:18080` |
| Python 内部服务 | `http://127.0.0.1:8090` |
| 工作台 | **[http://127.0.0.1:5174/login](http://127.0.0.1:5174/login)** |

本项目使用 5174；你电脑上的其他项目可能使用 5173。运行三个应用需要分别保留 IDEA 后端运行窗口、Python 终端、前端终端。

## 2. 每天启动：按这个顺序

你已经配置过 IDEA 和数据库，平时从这里开始即可。首次配置看第 3～5 节。

### 第一步：确认 PostgreSQL 已启动

使用现有数据库工具连接本项目实例，能看到 `agentops` 和 `agentops_checkpoint`。沿用现有用户名、密码和端口；不要重复建库。

**本次从旧版本升级到 G5 前，先备份本项目业务库。** 随后首次启动新版 Java 时，Flyway 自动执行尚未应用的迁移至 V6；无需手工重复执行迁移 SQL。Python 独立 checkpoint 库也应配套备份。

### 第二步：启动 Python

打开一个 PowerShell，进入项目目录：

```powershell
Set-Location 'E:\javaProjects\AgentOps AI'
```

输入下面一行，然后回车：

```powershell
$checkpointSecret = Read-Host '请输入 checkpoint 数据库角色的密码' -AsSecureString
```

出现密码提示后，**只输入数据库密码，然后回车**。不要在这个提示后输入下一条命令。等重新出现 `PS E:\...>` 再执行：

```powershell
$checkpointPlain = [Net.NetworkCredential]::new('', $checkpointSecret).Password
$checkpointEncoded = [uri]::EscapeDataString($checkpointPlain)
$env:AGENT_CHECKPOINT_DSN = "postgresql://agentops_checkpoint:${checkpointEncoded}@127.0.0.1:5432/agentops_checkpoint"
Remove-Variable checkpointPlain, checkpointEncoded, checkpointSecret
```

上面假定角色也叫 `agentops_checkpoint`。如果之前创建的是其他角色名或端口，替换对应部分。

再输入这一行并回车：

```powershell
$internalSecret = Read-Host '请输入与 IDEA 完全相同的 AGENT_INTERNAL_TOKEN' -AsSecureString
```

提示出现后，只粘贴 IDEA 运行配置中的内部 token，回车。等回到 PowerShell 提示符，再执行：

```powershell
$env:AGENT_INTERNAL_TOKEN = [Net.NetworkCredential]::new('', $internalSecret).Password
Remove-Variable internalSecret
$env:AGENT_LLM_PROVIDER = 'mock'
$env:AGENT_MODEL_NAME = 'mock'
.\scripts\agent.ps1 Run
```

看到 `Uvicorn running on http://127.0.0.1:8090`，保留终端运行。如果还没有虚拟环境或更新了依赖锁文件，先运行一次 `.\scripts\agent.ps1 Install -Dev`。

这些环境变量只属于当前终端。关闭终端后，下次需要重新输入。脚本不会自动读取根目录 `.env`；不要把实际密码或 token 写入本文件。

### 第三步：在 IDEA 启动 Java

1. 打开 `E:\javaProjects\AgentOps AI`。
2. 右上角选择已有的 **AgentOpsApplication** 运行配置。
3. 确认其环境变量有 `PORT=18080`、`AGENT_ENABLED=true`、`AGENT_SERVICE_ORIGIN=http://127.0.0.1:8090`、`AGENT_MODEL_NAME=mock`，其余数据库和 secret 保留原值。
4. 点击绿色三角形运行。已有旧进程时，先用 IDEA 红色方块停止该项目后端，再启动。
5. 等待控制台显示 `Started AgentOpsApplication`，并确认 Tomcat 使用 **18080**。
6. 打开 [后端健康检查](http://127.0.0.1:18080/actuator/health)，返回 JSON 中 `"status":"UP"` 表示后端健康。

健康检查只返回 JSON。项目的操作界面在 5174。

### 第四步：启动前端

**另开一个 PowerShell**：

```powershell
Set-Location 'E:\javaProjects\AgentOps AI\frontend'
npm run dev
```

首次安装，或 `package-lock.json` 更新后，在 `npm run dev` 前执行一次：

```powershell
npm ci --ignore-scripts --no-fund --no-audit
```

看到 Vite 输出 `http://127.0.0.1:5174/` 后，打开 [登录页面](http://127.0.0.1:5174/login)。前端默认把 `/api` 请求代理到 Java 18080。

### 第五步：登录

使用你已经验收成功的账号。你之前使用的管理员用户名是 **`admin`**，密码沿用之前创建该账号时设置的密码；项目没有统一默认密码。

`BOOTSTRAP_ADMIN_PASSWORD` 只在首次初始化空用户表时创建管理员，修改该变量不会重置已有账户密码。登录成功的 token 有有效期，失效后重新登录。刷新页面后也可能需要重新登录，因为会话仅保存在页面内存。

## 3. IDEA 首次配置或配置丢失时

### 3.1 设置 JDK 21

打开 **文件 → 项目结构 → 项目**：

- 项目 SDK：选你已安装的 **temurin-21**，或添加本项目已有的 `E:\javaProjects\AgentOps AI\.tools\java21\jdk-21.0.12.1+1`。
- 语言级别：选 SDK 默认值（21），不要保留 22。
- 模块 SDK：继承项目 SDK，或明确选 Java 21。

项目构建要求 Java 21；本机其他项目的 Java 17 不适用于这里。

### 3.2 正确导入 Maven

在左侧找到 **backend → pom.xml**，右键选择 **添加为 Maven 项目**。也可以在右侧 Maven 窗口点击 `+`，选择这个文件。

右侧应出现 **AgentOps AI backend**。如果出现之前误导入的 **Apache Commons IO**，右键该条目 → **取消链接 Maven 项目**，再导入正确的 `backend/pom.xml`。不要导入 `.cache` 或 `.tools` 内依赖包自带的 POM。

打开 **文件 → 设置 → 构建、执行、部署 → 构建工具 → Maven**：

| 设置 | 填写或选择 |
|---|---|
| Maven 主路径 | `E:\javaProjects\AgentOps AI\.tools\maven\apache-maven-3.9.16` |
| 本地仓库 | `E:\javaProjects\AgentOps AI\.cache\m2`，勾选右侧重写 |
| 脱机工作 | 不勾选 |
| 用户设置文件 | 沿用现有设置；没有自定义文件时无需新建 |
| 导入子页面的 JDK | 项目 JDK 21 |
| 运行程序子页面的 JRE | 项目 JDK 21 |

点击确定，再点击 Maven 窗口的 **重新加载所有 Maven 项目**。首次下载需要等待。未激活的 Linux 测试 profile 标红时，先看 Maven 同步/构建的实际错误；不要直接删除跨平台依赖配置。

### 3.3 建立 Java 运行配置

打开 `backend/src/main/java/dev/agentops/AgentOpsApplication.java`，点击类旁的绿色三角形。然后打开 **运行 → 编辑配置**：

| 字段 | 内容 |
|---|---|
| 名称 | `AgentOpsApplication` |
| JRE | Java 21 |
| 使用模块的类路径 | `agentops-backend` |
| 主类 | `dev.agentops.AgentOpsApplication` |
| 有效配置文件 | 留空，使用默认配置 |
| 工作目录 | `E:\javaProjects\AgentOps AI` |

点击 **修改选项 → 环境变量**，再点击该输入框右侧的表格按钮。在上方用户环境变量表用 `+` 一项一项添加：

| Name（名称） | Value（值） |
|---|---|
| `PORT` | `18080` |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/agentops` |
| `DB_USER` | 你已验证可用的业务数据库角色 |
| `DB_PASSWORD` | 该数据库角色的实际密码 |
| `SESSION_TOKEN_PEPPER` | 保留原值；首次配置时使用至少 32 字符的随机值 |
| `AGENT_INTERNAL_TOKEN` | 保留原值；至少 32 字符，且与 pepper 不同，Python 必须使用相同 token |
| `AGENT_ENABLED` | `true` |
| `AGENT_SERVICE_ORIGIN` | `http://127.0.0.1:8090` |
| `AGENT_MODEL_NAME` | `mock` |
| `BOOTSTRAP_ADMIN_USERNAME` | 仅首次建管理员时填写，例如 `admin` |
| `BOOTSTRAP_ADMIN_PASSWORD` | 仅首次建管理员时填写；至少 12 字符、至多 72 UTF-8 字节 |

保留“包含系统环境变量”勾选，依次点击 **确定 → 应用 → 确定**。表格中的说明文字要换成实际值，不能原样填入。

注意：`PORT` 控制 IDEA 后端端口；`.env.example` 中的 `BACKEND_PORT` 用于 Compose 的宿主机端口映射，不能代替这里的 `PORT`。IDEA 不会自动加载根 `.env`。

如果要继续监控你之前的私网演示目标，保留现有 `MONITOR_ALLOWED_PRIVATE_CIDRS`。后端部署允许范围与目标自己的 CIDR/HTTP 审批需要同时满足；主机地址变化时需重新核对。不要随意放宽为全部网段。

## 4. 只有首次安装才需要准备数据库

业务库使用 `agentops`，Java 通过 Flyway 建表。`DB_USER` 必须能在该库执行项目迁移。已有正常运行的配置直接保留。

Python 使用独立库和受限角色。若尚未建立，由你在确认属于本项目的 PostgreSQL 实例中执行，替换示例密码：

```sql
CREATE ROLE agentops_checkpoint LOGIN PASSWORD '替换为独立强密码'
  NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE agentops_checkpoint OWNER agentops_checkpoint;
```

已存在就不要重复执行。这个角色不应获得业务表权限；Python 会拒绝超级用户、具有 CREATEDB/CREATEROLE 权限的角色和业务数据库。启动后在独立库维护 checkpoint 与模型调用记录。正式部署还需限制数据库 CONNECT 范围。

## 5. G4 启动后怎么验收

先用默认 **mock** 走完整流程，无需提供模型密钥。

1. 登录工作台，确认能看到目标和检查历史。新建检查默认禁用，要明确启用才会探测。
2. 进入已有异常的事故详情。如果需要重新制造异常，使用之前登记、审批过的演示目标，等待检查达到配置的连续失败阈值；单次异常不一定立即建立事故。
3. 确认该目标存在已发布的运行手册；没有时由管理员建立草稿并填写发布理由。
4. 用具有 **ON_CALL 角色 + 该目标操作权限** 的账号点击 **发起诊断**。纯 OPS_ADMIN 也需要显式取得这两项权限。
5. 等待诊断进入 **等待人工审阅**，查看观测、推断、未知与手册引用。
6. 填写理由，点击 **批准线下处理**：先出现决定待同步，再进入待线下处理。用户自行处理后回填“已执行／未执行／执行失败”和说明，诊断任务结束。
7. 另发起一项诊断，选择拒绝，确认不会出现线下结果回填。24 小时未决定的审阅会过期取消。

批准不会执行任何运维命令，回填结束的是诊断任务。事故仍需按其确认、排查、观察、解决、关闭流程处理，不能以“诊断完成”代替“事故恢复”。

若 `AGENT_ENABLED=false`，任务可以排队，但后台不会分发或同步审阅决定。开启后继续处理。

### 可选：以后使用真实模型

先确认服务商、数据出站和费用，再手动配置。默认 mock 不会自动发起外部模型请求。

| 变量 | IDEA Java | Python 终端 |
|---|---|---|
| `AGENT_MODEL_NAME` | 实际模型标识 | 与 Java 完全相同 |
| `AGENT_LLM_PROVIDER` | 无需配置 | `openai-compatible` |
| `AGENT_LLM_BASE_URL` | 无需配置 | 支持严格 JSON Schema 的 Chat Completions 兼容服务 HTTPS API 前缀 |
| `AGENT_LLM_API_KEY` | 无需配置 | 服务商密钥，仅在 Python 服务端设置 |

本机模型使用 HTTP 时还需 `AGENT_ALLOW_LOCAL_MODEL_HTTP=true`，且仅允许 localhost/127.0.0.1/::1。修改变量后重启对应服务。新任务冻结当时的模型标识，不会修改旧任务。

若出现 `AGENT_MODEL_OUTCOME_UNKNOWN`，先核对模型服务的调用记录。该任务不会自动重试，也不能强制重发同一个任务；确认后如有必要，从事故详情明确发起新诊断。

## 6. 常见问题直接对照

| 现象 | 怎么处理 |
|---|---|
| `Read-Host` 后下一条命令变成密码/token | `Read-Host` 引号内只是提示文字。重新执行输入步骤，提示后仅输入秘密值；回到 `PS ...>` 再输入下一条命令 |
| 没有 JDK / 运行按钮不可用 | 检查项目和模块 SDK 21、Maven 导入及 `agentops-backend` 类路径 |
| Maven 出现 Apache Commons IO | 取消链接误导入项，再导入 `backend/pom.xml` |
| 依赖下载失败 | 关闭 Maven 脱机模式，核对仓库设置，重新加载；查看 Maven 输出第一条实际下载错误，不要仅凭编辑器红线判断 |
| Bootstrap 提示用户名/密码不合法 | 用户名 3～80 位，只含字母、数字、下划线、点、短横线；密码至少 12 字符且最多 72 UTF-8 字节 |
| secret 配置报错 | pepper 与内部 token 都至少 32 字符，彼此不同；Java/Python 内部 token 完全相同 |
| 数据库连接或认证失败 | 核对服务、端口、库名、角色、密码；checkpoint 密码在 DSN 中必须 URL 编码 |
| Python 拒绝数据库角色 | 使用独立 checkpoint 库的受限角色，不能使用 postgres 超级用户或业务库 |
| 后端启动在 8080，网页接口失败 | IDEA 设置 `PORT=18080` 后重新启动；不要只填 `BACKEND_PORT` |
| 登录 401 | 输入实际账户密码，不能填说明文字；已有账号不会被 bootstrap 变量重置 |
| 已登录后 401 / 刷新后回登录页 | 重新登录获取新会话 |
| 发起诊断或审批没有权限 | 管理员显式分配 ON_CALL，再授予对应目标操作权限 |
| 诊断一直排队 / 决定一直待同步 | 检查 Java `AGENT_ENABLED=true`、Python 8090 正在运行、origin 与内部 token 一致 |
| 5174 或 18080 端口被占用 | 确认是否本项目旧进程；通过所属终端或 IDEA 正常停止，勿结束其他项目进程 |
| 修改源码后界面/行为没变 | Java/Python 需重启加载新版；前端通常热更新，依赖变化时重新安装并启动 |

## 7. 停止、更新与回归

停止时，前端终端按 `Ctrl+C`，IDEA 点击后端红色停止按钮，Python 终端按 `Ctrl+C`。保留数据库服务和其他项目进程。

更新项目后，先看本指南更新日期及迁移说明：依赖锁文件变化则重新安装对应依赖；数据库迁移变化则先备份再由你启动 Java 应用迁移。不要修改已执行的旧 Flyway 文件，不要删除用户数据库来解决启动问题。

需要开发回归时，在根目录执行（这些不是每天启动必需的步骤）：

```powershell
.\scripts\agent.ps1 Test
.\scripts\backend.ps1 verify
npm --prefix frontend run build
```

后端验证使用独立临时 PostgreSQL，不接入日常业务库。G5 已归档结果为 Java 123 项、Python 单元59项及真实PostgreSQL11项通过，前端类型检查/构建、完整Compose、真实Kuma Push及故障恢复通过，详见 [验证记录](docs/verification/g5-validation-2026-10-21.json)。这些是开发阶段记录，不代表每次阅读本文都重新执行了测试。真实外部模型效果、性能和远端CI尚未验收。

## 8. G5 新增：Kuma 与 Compose（按需使用）

**继续使用 IDEA 时，前面四步启动顺序不变。** Kuma 默认关闭，不妨碍登录、监控、事故和诊断。升级 V6 后，管理员可在“服务目标 → 目标详情 → Kuma 推送”看到新面板。

### 8.1 可选 Kuma 配置与验收

先在 Kuma 自己的界面建立 **Push 类型 Monitor**。为每个目标使用独立 Monitor/token，不使用 Kuma 登录密码。将 token 仅配置到 Java 环境变量，例如 `KUMA_PUSH_TOKEN_DEMO`；界面只填 `env:KUMA_PUSH_TOKEN_DEMO`，不要粘贴完整 Push URL。

IDEA 后端还需以下环境变量，修改后重启后端：

| 变量 | 内容 |
|---|---|
| `KUMA_ENABLED` | `true`；默认 `false`，关闭时不发推送 |
| `KUMA_BASE_URL` | 唯一允许的 origin，例如 `https://你的Kuma域名`，可含明确端口；不带路径、query 或尾部斜杠 |
| `KUMA_ALLOW_HTTP` | 仅明确使用 HTTP 时设 `true` |
| `KUMA_ALLOWED_PRIVATE_CIDRS` | 私网 Kuma 的精确许可范围，逗号分隔；不与目标检查的许可混用 |
| `KUMA_PUSH_TOKEN_DEMO` | 上面预建 Monitor 的实际 Push token，仅留在本地环境 |

默认安全规则禁止 IP 地址形式、回环、metadata 和混合不安全 DNS 结果。因此 IDEA 连接 Kuma 时应使用已批准、可到达的域名地址；不要填写 `localhost`、`127.0.0.1` 或直接照抄 Compose 容器域名。已有目标的 HTTP/CIDR 批准不会自动批准 Kuma。

在新面板中填写与部署配置完全相同的服务地址、令牌引用和理由，保存为停用状态 → 填理由“测试推送” → 刷新至“测试已通过／已送达” → 再填写理由并启用推送。**测试会发送一条测试 UP**，请先在专用演示 Monitor 验收。

之后只推送该目标最新、当前配置且未过期的有效 UP/DOWN 样本；UNKNOWN 不当作 UP。查看“最近 20 条投递”了解尝试次数、HTTP 状态和脱敏错误。Kuma 不可用不会改变本地检查结果或事故，临时失败最多尝试 5 次；新样本替代旧待投递样本。401/403 停止重试；Kuma 2.3.0 通用 404 也可能代表处理异常，因此有界重试。终止的旧记录保留，新检查样本会形成新投递。

### 8.2 可选完整 Compose 启动

这是 IDEA 日常启动之外的另一种运行方式。先停止本项目 IDEA 后端和两个应用终端，避免 18080/5174 冲突；不要停止其他项目。

1. 根目录复制 `.env.example` 为 `.env`，填写业务库密码、pepper、内部 token、首次管理员密码，以及不同的 `CHECKPOINT_OWNER_PASSWORD` 和 `CHECKPOINT_PASSWORD`。设置 `AGENT_ENABLED=true`；模型保持 `mock`，Kuma 初次仍关闭。
2. 执行 `docker compose up --build -d --wait`。首次会创建本项目的两个独立 PostgreSQL 数据卷，保留原本 IDEA 使用的数据库。
3. 打开 5174 登录；新 Compose 数据库首次初始化的账号密码取自该 `.env`，不会复制 IDEA 数据库的账号。数据库与 Python 没有宿主机端口。
4. 需要 Kuma 时执行 `docker compose --profile kuma up -d`，在 `http://127.0.0.1:3001` 建立自己的用户和 Push Monitor。容器中的后端可以使用 `http://kuma.agentops.test:3001`；设置 `KUMA_ALLOW_HTTP=true` 并核对 Kuma 容器实际私网 IP 的精确 CIDR。容器重建后 IP 可能变化，不能抄用开发验收地址。
5. 更新 `.env` 后用 `docker compose up -d backend` 重建后端配置；映射表单使用相同 origin 和 `env:KUMA_PUSH_TOKEN_DEMO`。不要以 `restart` 代替环境变量重建。

日常停止使用 `docker compose --profile kuma stop`，下次 `docker compose --profile kuma up -d`。需要移除容器时 `down` 默认保留卷；**不要添加 `--volumes` 删除自己的数据**。前端为同源静态服务，仍需在正式环境配置 HTTPS。

### 8.3 恢复验证与边界

`powershell -File .\scripts\recovery-drill.ps1`（或 PowerShell 中 `.\scripts\recovery-drill.ps1`）只会创建一个无网络、无端口映射的临时 PostgreSQL 容器，用合成数据测试备份恢复到两个新数据库，结束清理该测试容器。它不会备份、恢复或改动你的现有数据库。

自己的备份需要在停止 Java/Python 写入后，配套备份业务库与完整 checkpoint 库，并保留匹配的代码版本和环境 secret。恢复后的 checkpoint 库仍应由受限应用角色拥有，否则 schema 初始化会因 CREATE 权限被拒绝。先恢复到新库核对 runId、事故、审批和模型调用记录，再决定切换；不能把临时测试脚本当作生产恢复工具，也不能在 checkpoint 丢失时盲目重发结果不确定的模型任务。

## 9. 本文以后怎么跟着项目更新

后续开发只要改变启动命令、目录、依赖版本、端口、环境变量、数据库迁移、模型配置或验收入口，就在同一次改动中更新本文和 README 中相关内容。仓库 [AGENTS.md](AGENTS.md) 已登记这个维护要求。

配置核对来源：`backend/pom.xml`、`backend/src/main/resources/application.yml`、`scripts/agent.ps1`、`scripts/backend.ps1`、`frontend/package.json`、`frontend/vite.config.ts`、`db/migration/`。文档只记录变量用途和示例，实际账户密码、token、DSN 保留在你本地的运行配置中，不进入 Git。


## 10. 新版工作台的启动与界面验收（2026-10-21）

本轮增加Tailwind/shadcn-vue原语、Pinia、Lucide和ECharts，锁文件已更新。**第一次使用本版先停止前端终端中的Vite，再重新安装依赖并启动：**

```powershell
Set-Location 'E:\javaProjects\AgentOps AI\frontend'
npm ci --ignore-scripts --no-fund --no-audit
npm run dev
```

IDEA仍运行Java21的`dev.agentops.AgentOpsApplication`、PORT18080，Python仍为8090，前端仍为5174。新增只读查询需要IDEA重新运行新版后端；本轮无V7或其他新增迁移，已在V6的数据库不用执行额外SQL。旧版本尚未升V6时仍按前述备份与升级步骤处理。本轮没有替你重启用户服务或迁移用户数据库。Compose用户需要自行重新构建应用镜像后启动，不会由开发工具自动替换你的容器。

打开 [工作台](http://127.0.0.1:5174/login)，使用已有账号（不在文档保存密码）。默认深色；右上角太阳/月亮切换主题，偏好保存在本浏览器，登录会话仍仅内存。

1. **运维总览**：核对当前服务数、正常/异常/未知、活动事故。时间范围改变事件趋势、最近事件和检查样本；当前快照不变化。检查图最多取最新100条，范围内无数据留空，不显示随机曲线。
2. **全局搜索**：Ctrl+K（Mac为Cmd+K），输入至少2字符，↑/↓选择、Enter打开、Esc关闭。搜索服务/事件/手册/任务，每类最多8项；只读用户不会看到越权记录。
3. **服务监控与事件中心**：输入关键词、切换排序/状态、分页。全局时间按事件首次发现时间筛选；待达阈值异常显示当前状态。进入事件核对时间线、恢复证据、处理理由与版本。
4. **AI故障诊断**：从事件详情发起，只读诊断任务进入工作台，核对结论、假设、节点记录、冻结检查/手册引用和不确定项。Mock模式隐藏置信度；未确认节点不显示伪造工具执行进度。
5. **执行与审批**：筛选等待审阅/待线下处理任务，打开任务，填写理由批准或拒绝。批准仅允许人员线下处理；实际操作后回填“已执行/未执行/失败”，不会自动修复或解决事故。仍需ON_CALL和该目标操作权限。
6. **运维知识库**：跨全部可见手册搜索/排序，选择具体服务后新建草稿，发布不可变版本；旧诊断引用保持原版本。知识目录不受全局时间影响。
7. 缩窄至手机宽度，使用左上角导航菜单；测试键盘Tab、焦点可见、搜索弹窗焦点锁与Esc。观察员不应看到用户管理/网络/ACL/Kuma管理或审批表单，报告权限不足会明确报错。

本地前端验证：`npm test`（Node22.12需实验性类型剥离，脚本已带参数；开发实测Node24.22）、`npm run build`。后端继续根目录` .\scripts\backend.ps1 verify`。本版最终实际结果与截图见测试矩阵和`docs/verification/ui-validation-2026-10-21.json`；不要把历史G5记录当本次重跑结果。

## 11. 本轮导航与管理员处理体验验收（2026-10-21）

**启动配置、端口、环境变量、依赖锁文件和迁移均未改变。** IDEA 重新运行一次新版后端，使任务列表返回当前用户的 `canOperate`；前端已在运行时可热更新，生产/Compose 需要按原流程自行重新构建。继续使用你的已有账号，不必重新建库、填写新密钥或配置 Kuma。本轮开发没有重启你的 IDEA、Python、前端或容器，也没有迁移用户库。

1. 登录后，顶部是**可切换、可关闭的工作页签**，显示服务名称与“事件/诊断”；运维总览固定保留。关闭其他页签不改变当前页，关闭当前页签回到相邻工作页。
2. 点击左侧分组展开子菜单。进入一个服务、事件或诊断后，会出现“当前服务·检查配置/检查历史”“当前事件·处理/事件时间线”“当前任务·诊断报告/工具与节点记录/人工审阅与回填”。这些入口指向刚查看的实际记录，尚未选择记录时不显示虚假详情入口。
3. **管理员验收事件**：先从“告警与事件 → 事件列表”选择事件。页首“当前处理步骤”集中显示可用的“确认事故 → 开始排查 → 确认解决 → 关闭事故”，以及“发起 AI 诊断/检查历史”。按钮随实际状态、恢复证据与权限变化；不会提前展示不可执行动作。管理员仍需 `ON_CALL` 及目标操作授权。
4. **验收 AI 处理**：发起诊断后，在页首“下一步处理”点“立即审阅”，在当前页弹窗填写批准/拒绝及理由。后台同步后按钮变为“回填处理结果”；实际未做修复就选择“未执行”并说明。也能从“执行与审批”的列表按钮或左侧“当前任务·人工审阅/回填”直达，不用翻到长报告底部。
5. **验收返回**：在事件列表设置搜索词、状态和排序，进入事件/检查历史/诊断，再用浏览器后退或页面返回链接。应回到原工作页面，列表筛选、页码在同一登录会话中保留；失效页码按实际剩余结果校正。浏览器后退移除审阅锚点时弹窗关闭，前进可重新打开。事件时间线每15秒更新，但不会反复把阅读位置拉回标题。
6. 时间范围、任务状态、排序等均使用共享主题下拉框。切换深浅色检查文字/选中项，使用 ↑/↓、Enter 选择，Esc 关闭。手机左上角菜单包含同样的子菜单，表格在自身区域横向滚动。
7. 刷新页面后按原安全设计需要重新登录，成功后续接原内部详情地址及查询/锚点。页签、筛选、滚动位置只在当前登录会话保留；退出/换账号清除，未提交表单不跨页面保存。观察员不应出现处理按钮、管理菜单或上一个账号的工作页签。

本轮实际结果：Java完整128项、前端10项、类型/生产构建通过；独立测试库与Python mock的浏览器处理闭环通过，深浅下拉/返回/子菜单/窄屏/角色隔离已检查。截图与限制见 `docs/verification/navigation-validation-2026-10-21.json`。Linux镜像、Compose、真实模型和远端CI本轮未重跑，历史证据不作为本轮结果。

## 12. 一条命令执行核心验收（G6，2026-10-23）

日常IDEA/前端/Python启动方式不变。需要核对当前代码的核心闭环时，在IDEA终端执行：

```powershell
Set-Location 'E:\javaProjects\AgentOps AI'
.\scripts\acceptance.ps1
```

前提是前文的Java21/Maven3.9、项目Python虚拟环境（包含开发测试依赖）、Node及前端锁定依赖已安装。脚本不代装工具、不修改系统配置。它不需要Docker、用户业务库、模型密钥、Kuma或额外运行环境变量；临时凭据在测试中随机生成，仅存内存。

命令依次检查Java/真实PostgreSQL与Python检查点、Python图契约、前端回归及类型/构建、演示目标和报告工具。终端逐项显示PASS/FAIL，日志位于 `.cache/acceptance/<时间-随机号>/`；最后打印 **report.html** 的完整路径，双击该文件查看。旁边的 **report.json** 便于对比和归档。目录已被Git忽略，不要把原始测试日志或随机凭据手动上传。

报告的16步对应：隔离身份登录 → 创建并启用检查 → 阈值前异常进度 → 唯一事故 → 权限边界 → 确认排查 → 发布手册 → 幂等发起 → 后台Python等待人审 → 冻结引用 → 批准与恢复 → 未执行回填 → 只读隔离 → 恢复观察 → 解决关闭 → 时间线审计。

检查样本是**受控合成503/200**，通过真实聚合器生成事故；Java公共API、Outbox后台工作者、内部HTTP和Python/PostgreSQL检查点实际执行。它不代表实际探测外部网站或调用真实模型。真实模型评分、性能、Compose/Kuma及本轮远端CI在报告中明确列为未验证。

任一命令失败、旅程缺失或步骤未通过，整体都显示FAIL并返回非零退出码；不会拿上一次成功报告冒充本次结果。打开对应阶段日志定位原因。临时服务/数据随测试关闭，验收报告作为本次结果保留；你的日常服务不必停止。

本轮Git从main建立 `codex/g6-repeatable-acceptance` 功能分支。可先实操查看：`git branch --show-current`、`git diff main...HEAD --stat`。提交前看未提交修改用 `git diff --stat`；提交后再比较分支。合并、推送主分支应在审阅本轮结果后另行进行。

## 13. 自己动手验收：一键独立演示（2026-10-23）

这次验收直接使用此入口。无需先启动IDEA里的Java、日常Python、数据库、前端或Docker；已经在运行的日常服务可以保留。演示使用随机本机端口和独立临时数据库，不会连接你的业务库。初次开发依赖仍需按前文安装：Java21、Maven3.9、项目Python虚拟环境的开发依赖、Node22.12+和前端锁定依赖。首次缺少Maven缓存时仍需要下载依赖。

在IDEA下方的“终端”（PowerShell）执行：

```powershell
Set-Location 'E:\javaProjects\AgentOps AI'
.\scripts\demo.ps1
```

等待终端出现 **演示工作台** 的网址后，在浏览器打开它。端口每次不同，以这次打印的地址为准；不用打开固定5174。终端会同时显示两个账号的**本次随机密码**：

| 账号 | 用途 |
|---|---|
| `demo-admin` | 管理员兼值班人员，已获得演示服务操作授权，可确认、诊断、审批和关闭事故 |
| `demo-observer` | 观察员，可查看演示服务和事件，不能处理、诊断或审批 |

账号只在本次演示环境有效，不能用于日常工作台。服务目标“交互式故障验收服务”、健康检查和已发布手册均已准备好，不用自己登记。顶部有“独立演示环境”和“验收步骤”；这些提示仅出现在演示启动入口。

按这个顺序验收：

1. 用 `demo-admin` 登录，查看“服务监控 → 服务列表”，预建服务最初为正常。
2. 回到**保持运行的同一个终端**，在 `demo:` 提示后输入 `down` 并回车。它注入三条合成503，达到失败阈值。打开“告警与事件 → 事件列表”，看到一条待确认事故；列表每5秒刷新，也可点击刷新。
3. 点“处理事件”，依次点击页首“确认事故”“开始排查”，各填一个理由。再点“发起 AI 诊断”，等待“等待人工审阅”，检查报告的503证据、冻结手册和mock说明。
4. 点页首“立即审阅”，填写批准理由并提交。待断点同步后，在同一弹窗的“线下处理结果”选择**未执行**，填写“仅验收，未执行修复”并保存。事故应仍在处理中，批准和回填都不会代替检查恢复。
5. 在启动终端输入 `up`。四条合成200依次满足恢复阈值和观察条件；返回当前事件，刷新后应为“已解决”。在时间线核对“恢复达到阈值”“进入恢复观察”“事故已解决”，再点页首“关闭事故”填写理由。
6. 退出登录，改用 `demo-observer`。能查看事件及时间线，应看到只读提示，没有确认、发起诊断或审批按钮，也没有用户管理入口。
7. 在启动终端输入 `status` 查看当前合成模式及最近事故状态；验收结束输入 `quit`。等到“独立演示已结束”后再关闭终端。

`down` / `up` 每次都是立即注入一组样本；每60秒还会记录一条当前模式样本，保持服务状态不过期。`up` 一次完成四个样本，页面可能直接显示已解决，恢复观察过程可在时间线核对。如果没有先确认并开始排查，恢复证据不会跳过这些人工步骤；先完成处理，再次输入 `up`。

演示最长两小时，退出后数据库、账号和凭据清除，重跑从全新数据开始；不要在这里保存需要长期保留的配置。浏览器刷新仍需重新登录，可使用本次终端密码续接原详情。验收期间避免同时执行前端生产构建，生成静态文件可能触发开发页重载。启动或操作失败时，查看终端打印的 `DEMO_SESSION` 目录里的 `compile.log`、`runtime.*.log` 和 `frontend.*.log`，不必重配日常环境。临时凭据保存在该Git忽略目录的 `ready.json`，正常结束删除；不要上传或分享原始目录。

如果想先让脚本自动检查这个入口，执行：

```powershell
.\scripts\demo.ps1 -Check
```

此模式验证前端代理登录、两种角色、故障/恢复命令、真实Outbox与Python检查点、人审、未执行回填及关闭，共7步；完成后自动退出，不在终端显示密码。`check-result.json` 是本次脱敏结果，`session-result.json` 记录服务关闭及凭据清除。完整代码回归仍使用第12节 `acceptance.ps1`。

本入口的503/200是明确标注的**合成检查样本**，目标地址不会发出外部探测；诊断使用Python mock。Java业务流程、Python检查点、审批和审计实际运行，但不作为外网监控、真实模型质量、Kuma或性能验收。本轮未新增迁移或日常环境变量，IDEA日常启动仍按第2节执行。
