import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdir, mkdtemp, writeFile, readFile, rm } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildReport, renderReport } from './acceptance-report.mjs'

const required = ['backend', 'python', 'frontend-tests', 'frontend-build', 'demo-target', 'report-tests']
const steps = ['bootstrap', 'check-enabled', 'threshold-progress', 'incident-open', 'role-boundaries', 'incident-investigation', 'published-runbook', 'diagnosis-idempotency', 'waiting-human', 'frozen-citations', 'human-approval', 'manual-report', 'observer-report-denied', 'recovery-monitoring', 'incident-closed', 'timeline-audit']
function sample() {
  return { revision: '39c1948', dirty: false, startedAt: '2026-10-23T00:00:00Z', finishedAt: '2026-10-23T00:01:00Z',
    stages: required.map(id => ({ id, status: 'PASS', exitCode: 0, log: `${id}.log` })),
    journey: { schemaVersion: 1, status: 'PASS', sampleMode: 'synthetic', model: 'mock', steps: steps.map(id => ({ id, status: 'PASS', detail: 'Synthetic contract verified' })) } }
}
test('a report requires all commands and the current successful journey', () => {
  assert.equal(buildReport(sample()).status, 'PASS')
  const missing = sample(); delete missing.journey
  assert.equal(buildReport(missing).status, 'FAIL')
  const incomplete = sample(); incomplete.stages.pop()
  assert.equal(buildReport(incomplete).status, 'FAIL')
  const partial = sample(); partial.journey.steps.pop()
  assert.equal(buildReport(partial).status, 'FAIL')
})
test('failed commands or failed journey steps cannot be hidden by an aggregate PASS', () => {
  const command = sample(); command.stages[0].exitCode = 1
  assert.equal(buildReport(command).status, 'FAIL')
  const step = sample(); step.journey.steps[0].status = 'FAIL'
  assert.equal(buildReport(step).status, 'FAIL')
  const skipped = sample(); skipped.stages[0].status = 'NOT_RUN'
  assert.equal(buildReport(skipped).status, 'FAIL')
})
test('the report explicitly distinguishes synthetic contracts and unmeasured model quality', () => {
  const report = buildReport(sample())
  assert.equal(report.sampleMode, 'synthetic')
  assert.equal(report.model, 'mock')
  assert.ok(report.notRun.includes('真实模型质量与人工评分'))
  assert.ok(report.notRun.includes('外部目标实际网络探测'))
  assert.equal(report.revision, '39c1948')
})
test('exported HTML escapes all supplied text and only links local stage logs', () => {
  const input = sample(); input.revision = '<img src=x onerror=alert(1)>'
  input.journey.steps[0].detail = '<script>alert("secret")</script>'
  input.stages[0].log = 'https://untrusted.example/log'
  const html = renderReport(buildReport(input))
  assert.ok(html.includes('&lt;script&gt;'))
  assert.ok(!html.includes('<script>'))
  assert.ok(!html.includes('<img'))
  assert.ok(!html.includes('href="https://untrusted.example'))
})
test('the CLI exports a failed report and exits nonzero when this run has no journey', async () => {
  const root = dirname(dirname(fileURLToPath(import.meta.url)))
  const cache = join(root, '.cache'); await mkdir(cache, { recursive: true })
  const fixture = await mkdtemp(join(cache, 'acceptance-report-test-'))
  try {
    const input = sample(); delete input.journey
    await writeFile(join(fixture, 'input.json'), JSON.stringify(input))
    const child = spawnSync(process.execPath, [join(root, 'scripts/acceptance-report.mjs'), join(fixture, 'input.json')], { cwd: root, encoding: 'utf8', timeout: 10000 })
    assert.equal(child.status, 1)
    assert.equal(JSON.parse(await readFile(join(fixture, 'report.json'), 'utf8')).status, 'FAIL')
    assert.ok((await readFile(join(fixture, 'report.html'), 'utf8')).includes('本次没有通过记录'))
  } finally {
    assert.ok(resolve(fixture).startsWith(resolve(cache) + sep))
    await rm(fixture, { recursive: true, force: true })
  }
})
