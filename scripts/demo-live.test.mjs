import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { access, readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { sessionDirectory } from './demo-session.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))

test('live mode refuses unattended Check before asking for credentials or starting services', { skip: process.platform !== 'win32' }, () => {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', './scripts/demo.ps1', '-DeepSeek', '-Check'], { cwd: root, encoding: 'utf8', timeout: 15000, windowsHide: true })
  assert.notEqual(result.status, 0)
  assert.match(result.stdout + result.stderr, /LIVE_MODEL_CHECK_FORBIDDEN/)
  assert.doesNotMatch(result.stdout, /DEMO_SESSION=/)
})

test('DeepSeek demo starts in page-managed mode and never reads a model key in PowerShell', async () => {
  const source = await readFile(join(root, 'scripts', 'demo.ps1'), 'utf8')
  assert.doesNotMatch(source, /Read-Host[^\r\n]*DeepSeek API Key/i)
  assert.doesNotMatch(source, /AGENT_LLM_API_KEY/)
  assert.match(source, /页面[^\r\n]*(保存|配置)[^\r\n]*(API Key|密钥)/i)
  const agent = await readFile(join(root, 'scripts', 'agent.ps1'), 'utf8')
  assert.match(agent, /AGENT_LLM_API_KEY/)
  assert.match(agent, /SetEnvironmentVariable\(\$taskLegacyName,\$null,'Process'\)/)
})

test('DeepSeek NoInput prints both accounts, waits for page configuration and removes its database', { skip: process.platform !== 'win32', timeout: 120000 }, async () => {
  const child = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', './scripts/demo.ps1', '-DeepSeek', '-NoInput'], { cwd: root, windowsHide: true })
  let output = ''
  const exited = new Promise((resolve, reject) => {
    child.on('error', reject)
    child.on('exit', code => resolve(code))
  })
  const session = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('NoInput did not print DEMO_SESSION in time')), 90000)
    const collect = chunk => {
      output += chunk.toString('utf8')
      const match = output.match(/DEMO_SESSION=([^\r\n]+)/)
      if (match) {
        clearTimeout(timer)
        resolve(sessionDirectory(match[1].trim()))
      }
    }
    child.stdout.on('data', collect)
    child.stderr.on('data', collect)
    child.on('exit', code => {
      clearTimeout(timer)
      reject(new Error(`NoInput exited before readiness (${code})`))
    })
  })
  try {
    await writeFile(join(session, 'stop'), 'stop')
    assert.equal(await exited, 0)
    const uuid = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
    assert.ok(new RegExp(`demo-admin[^\\r\\n]*${uuid}`, 'i').test(output), 'NoInput must print the temporary administrator credentials')
    assert.ok(new RegExp(`demo-observer[^\\r\\n]*${uuid}`, 'i').test(output), 'NoInput must print the temporary observer credentials')
    assert.match(output, /API Key/)
    const result = JSON.parse(await readFile(join(session, 'session-result.json'), 'utf8'))
    assert.equal(result.servicesStopped, true)
    assert.equal(result.credentialsRemoved, true)
    await assert.rejects(access(join(session, 'postgres')))
  } finally {
    if (child.exitCode === null) child.kill()
  }
})
