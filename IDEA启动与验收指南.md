# 你的 AgentOps AI：IDEA 启动与验收指南

更新日期：2026-10-21。适用版本：G4（人工审阅、线下结果回填），业务库迁移至 V5。本文按你的 Windows、中文 IDEA 和项目位置编写；以后修改启动配置、端口或验收流程时同步更新本文。

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

**本次从旧版本升级到 G4 前，先备份本项目业务库。** 随后首次启动新版 Java 时，Flyway 自动执行尚未应用的迁移至 V5；无需手工重复执行迁移 SQL。

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

后端验证使用独立临时 PostgreSQL，不接入日常业务库。G4 已归档结果为 Java 107 项、Python 单元 59 项及真实 PostgreSQL 11 项通过，前端类型检查与构建通过，详见 [验证记录](docs/verification/g4-validation-2026-10-21.json)。这些是开发阶段记录，不代表每次阅读本文都重新执行了测试。真实外部模型效果、性能、完整 Compose 和 Kuma 尚未验收。

## 8. 本文以后怎么跟着项目更新

后续开发只要改变启动命令、目录、依赖版本、端口、环境变量、数据库迁移、模型配置或验收入口，就在同一次改动中更新本文和 README 中相关内容。仓库 [AGENTS.md](AGENTS.md) 已登记这个维护要求。

配置核对来源：`backend/pom.xml`、`backend/src/main/resources/application.yml`、`scripts/agent.ps1`、`scripts/backend.ps1`、`frontend/package.json`、`frontend/vite.config.ts`、`db/migration/`。文档只记录变量用途和示例，实际账户密码、token、DSN 保留在你本地的运行配置中，不进入 Git。
