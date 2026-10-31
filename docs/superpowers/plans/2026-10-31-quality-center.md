# G8 发布基线与诊断质量中心 Implementation Plan

**Goal:** 修复外部 DNS 导致的 FoundationTest 不稳定，并交付可独立验收的离线诊断质量工作台。
**Architecture:** FoundationTest 在测试配置中注入固定公共地址。Python 复用既有 score/引用/风险校验导出版本化工作台包；Vue 管理员页面加载包，展示 30 场景、真实历史回执与待人评状态，导出兼容既有 CLI 的 reviews.json。离线人评通过导出文件保存，重新导入恢复；本阶段不新增业务数据库表。
**Tech Stack:** 既有 Spring Boot/JUnit、Python/Pydantic、Vue3/TypeScript/Reka UI。
**Spec:** 用户于本轮批准的 G8A → G8B 离线版顺序，以及本文件中的交付范围。

## Constraints and review focus

- 不发起新的 DeepSeek 请求，不自动填写人评分数；未知 Token/费用维持未知。
- 不降低 SSRF、风险或引用校验，不修改已有断言；历史材料与新版本验证分开。
- 历史试评测：2 次请求、1 接受、1 风险拒绝；28 个场景未调用。拒绝原文不保留。
- 工作台文件仅包含原创合成数据，导入来源未经认证；页面不认定模型质量 PASS。
- 报告内容按文本展示；人工复核绑定 inputHash/reportHash，变化或外来评分不能套用。
- 页内切换保留草稿，离开/刷新前提示导出；导出的 JSON 是离线持久化载体。
- 使用原有主题、Select、分页和侧栏；管理员入口、键盘、深浅主题及窄屏可用。
- 同步 README 与 IDEA 启动验收指南；最多新增本计划和一份脱敏验证摘要。

## Tasks

- [x] 1. 复现 FoundationTest → 固定测试 DNS → 全量 Java 验证。测试专用配置只解析 fixture 地址，异常地址继续拒绝。
- [x] 2. 测试先行实现 quality_workbench.py 和 evaluate.py workbench：输出 30 场景，引用原 score 结果；匹配输入 hash 的回执显示请求/Token/估算费用及拒绝码。无匹配回执不推测请求状态。
- [x] 3. 前端质量中心：管理员路由 /admin/quality、目录搜索/过滤/分页、选中场景证据和报告、人评与 JSON 导出/导入。测试 hash 错配、未知值、无报告禁止质量评分、完整人评才计数及注入文本不执行。
- [ ] 4. 完整 acceptance、mock 演示和浏览器验收；记录确切结果、启动指南与 README 同步；本地提交后推送/远端 CI，满足发布检查再处理合并。

## Execution record

- 2026-10-31：原 FoundationTest 19 项中 3 失败已在沙箱外复现；外部 DNS 回答 198.18.0.49 触发 TARGET_URL_BLOCKED。沙箱内初跑因 loopback 权限失败，不能作为功能结论。
- Native实施：复用原有score与UI体系，不新增依赖/迁移。Python新增3项、前端新增4项先观察缺模块失败，再实现并通过。
- 完整acceptance通过：Java143/Python174/前端23/脚本14/报告5，核心旅程PASS；UI标签和下载图标收尾后再次运行前端23项和生产构建通过。
- 浏览器管理员续接、历史计数、拒绝场景筛选、无报告禁用评分、真实Vue草稿JSON导出通过。桌面深色及浅色390px无横向溢出已检查；浏览器文件选择长时间阻塞并会话过期，导入只记代码回归通过，观察员及完整键盘流程未浏览器实测。
- 本轮隔离demo已正常结束，session-result确认servicesStopped/credentialsRemoved均true。无新模型调用，完整质量成绩保持INCOMPLETE。详细状态见verification/g8-quality-center-2026-10-31.json。
- 独立只读审阅发现2项Important已修复：报告重新绑定清除旧评分；工作台包携带Python生成的报告JSON，浏览器核对SHA-256与正文一致性，避免沿用修改前的评分。另统一非负整数计数；对应新增回归均观察RED→GREEN。最终前端25项和生产构建通过；Python收尾复跑遇旧用例WinError5文件替换，已保留为环境限制并用全新项目内临时目录再次核实。
- 最终Python全套178通过/12按约定跳过；后者已由本轮Java143项中的PostgreSQL集成入口覆盖。发布前不据此声称完整30场景真实质量评测或性能通过。
- 提交fb76b26已推送，草稿PR#1包含G6之后累计29个提交；main仍为39c1948。远端run41204812165的verify/frontend成功，compose在外部后端健康检查失败，恢复演练未运行。静态确认Compose的http://agent-service:8090与G7非回环HTTP拒绝规则冲突；CI缺后端应用日志，尚未证明唯一运行时根因。Task4发布部分未完成，需要进一步确定容器内部HTTPS/证书信任方案，不能为了通过CI降低凭据传输校验。
