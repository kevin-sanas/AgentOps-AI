import { readFile, writeFile } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const stageNames = { backend: 'Java / PostgreSQL 与核心旅程', python: 'Python 图与模型契约', 'frontend-tests': '前端状态与导航回归', 'frontend-build': '前端类型检查与生产构建', 'demo-target': '演示目标与会话契约', 'report-tests': '验收报告回归' }
const stepNames = {
  bootstrap: '隔离环境与三个角色登录', 'check-enabled': '创建并启用检查', 'threshold-progress': '阈值前异常进度', 'incident-open': '达到阈值只建一个事故',
  'role-boundaries': '管理员与观察员操作边界', 'incident-investigation': '确认事故并进入排查', 'published-runbook': '发布诊断依据',
  'diagnosis-idempotency': '同键发起只产生一个诊断', 'waiting-human': '真实 Python 检查点进入人审', 'frozen-citations': '报告引用冻结检查与手册',
  'human-approval': '审阅决定与后台恢复', 'manual-report': '如实回填未执行且不解决事故', 'observer-report-denied': '观察员不能读取诊断报告',
  'recovery-monitoring': '检查恢复后进入观察', 'incident-closed': '恢复满足窗口后关闭事故', 'timeline-audit': '时间线与审计完整'
}
const notRun = ['外部目标实际网络探测', '真实模型质量与人工评分', '性能与容量基准', '本轮 Compose / Kuma 运行验收', '本轮远端 CI（另见 GitHub Actions）']

export function buildReport(input) {
  const stages = Object.entries(stageNames).map(([id, label]) => {
    const matches = input.stages?.filter(s => s.id === id) || []
    const source = matches[0]
    const status = matches.length === 1 && source.status === 'PASS' && source.exitCode === 0 ? 'PASS' : source?.status === 'NOT_RUN' || !source ? 'NOT_RUN' : 'FAIL'
    return { id, label, status, exitCode: source?.exitCode ?? null, log: source?.log || '' }
  })
  const journey = input.journey
  const steps = Object.entries(stepNames).map(([id, label]) => {
    const matches = journey?.steps?.filter(s => s.id === id) || []
    const source = matches[0]
    return { id, label, status: matches.length === 1 && source.status === 'PASS' ? 'PASS' : source ? 'FAIL' : 'NOT_RUN', detail: source?.detail || '本次没有通过记录', elapsedMs: source?.elapsedMs ?? null }
  })
  const validJourney = journey?.schemaVersion === 1 && journey.status === 'PASS' && journey.sampleMode === 'synthetic' && journey.model === 'mock' && steps.every(s => s.status === 'PASS')
  return { schemaVersion: 1, status: validJourney && stages.every(s => s.status === 'PASS') ? 'PASS' : 'FAIL', revision: input.revision || 'unknown', dirty: !!input.dirty,
    startedAt: input.startedAt, finishedAt: input.finishedAt, sampleMode: 'synthetic', model: 'mock', stages, steps, notRun,
    scope: '临时 PostgreSQL、随机本机端口、Java 公共 API、真实 Python mock / checkpoint；检查结果为受控合成样本。没有访问用户数据库、外部模型或服务目标。' }
}
const escape = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]))
export function renderReport(report) {
  const badge = status => `<span class="badge ${status === 'PASS' ? 'pass' : status === 'FAIL' ? 'fail' : 'pending'}">${status === 'PASS' ? '✓ 通过' : status === 'FAIL' ? '× 未通过' : '— 未执行'}</span>`
  const logLink = value => /^[a-z0-9-]+\.log$/.test(value) ? `<a href="${escape(value)}">查看日志</a>` : ''
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>AgentOps 核心验收报告</title><style>
*{box-sizing:border-box}body{margin:0;background:#0b1220;color:#e2e8f0;font:15px/1.7 system-ui,"Microsoft YaHei",sans-serif}main{max-width:1080px;margin:auto;padding:48px 24px}header{border-bottom:1px solid #334155;padding-bottom:28px;margin-bottom:28px}h1{font-size:32px;letter-spacing:-1px;margin:8px 0 16px}h2{font-size:20px;margin:32px 0 16px}.eyebrow{color:#22c6da;font-size:12px;letter-spacing:2px}.muted,small{color:#94a3b8}.summary{display:flex;gap:16px;align-items:center;flex-wrap:wrap}.badge{padding:4px 10px;border:1px solid;border-radius:6px;font-size:12px;white-space:nowrap}.pass{color:#86efac;background:#14532d33;border-color:#166534}.fail{color:#fca5a5;background:#7f1d1d33;border-color:#991b1b}.pending{color:#cbd5e1;border-color:#475569}.scope{background:#151f2e;border:1px solid #334155;border-radius:12px;padding:20px}ol{list-style:none;padding:0;margin:0;display:grid;grid-template-columns:1fr 1fr;gap:12px}li.step{padding:16px;border:1px solid #334155;border-radius:10px;background:#151f2e}.step-head{display:flex;justify-content:space-between;gap:12px}.step p{font-size:13px;color:#94a3b8;margin:12px 0 0;overflow-wrap:anywhere}table{width:100%;border-collapse:collapse}td,th{padding:14px 12px;text-align:left;border-bottom:1px solid #334155}a{color:#8eb1ff}code{overflow-wrap:anywhere}footer{margin-top:40px;border-top:1px solid #334155;padding-top:16px;font-size:12px;color:#94a3b8}@media(max-width:700px){main{padding:24px 16px}ol{grid-template-columns:1fr}h1{font-size:26px}td,th{padding:12px 6px}}
</style></head><body><main><header><div class="eyebrow">AGENTOPS · ACCEPTANCE</div><h1>核心流程验收报告</h1><div class="summary">${badge(report.status)}<span>版本 <code>${escape(report.revision)}</code>${report.dirty ? ' · 含本地未提交修改' : ''}</span><span class="muted">Synthetic / Mock</span></div><p class="muted">${escape(report.startedAt)} → ${escape(report.finishedAt)}</p></header><p class="scope">${escape(report.scope)}</p><h2>构建与回归</h2><table><thead><tr><th>检查</th><th>结果</th><th>详情</th></tr></thead><tbody>${report.stages.map(stage => `<tr><td>${escape(stage.label)}</td><td>${badge(stage.status)}</td><td>${logLink(stage.log)}</td></tr>`).join('')}</tbody></table><h2>事故处置旅程</h2><ol>${report.steps.map(step => `<li class="step"><div class="step-head"><strong>${escape(step.label)}</strong>${badge(step.status)}</div><p>${escape(step.detail)}</p>${step.elapsedMs === null ? '' : `<small>本次步骤耗时 ${escape(step.elapsedMs)} ms（非性能基准）</small>`}</li>`).join('')}</ol><h2>本轮未验证</h2><ul>${report.notRun.map(item => `<li>${escape(item)}</li>`).join('')}</ul><footer>该报告记录本次命令与合成合同检查，不能作为真实模型评分、外部探测或生产发布证明。原始阶段日志与 report.json 同目录保存。</footer></main></body></html>`
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  const input = resolve(process.argv[2])
  const report = buildReport(JSON.parse(await readFile(input, 'utf8')))
  await writeFile(resolve(dirname(input), 'report.json'), JSON.stringify(report, null, 2) + '\n')
  await writeFile(resolve(dirname(input), 'report.html'), renderReport(report))
  console.log(`验收 ${report.status}: ${resolve(dirname(input), 'report.html')}`)
  process.exitCode = report.status === 'PASS' ? 0 : 1
}
