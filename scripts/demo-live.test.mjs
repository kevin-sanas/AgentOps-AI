import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

test('live mode refuses unattended Check before asking for credentials or starting services', { skip: process.platform !== 'win32' }, () => {
  const root = fileURLToPath(new URL('../', import.meta.url))
  const result = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', './scripts/demo.ps1', '-DeepSeek', '-Check'], { cwd: root, encoding: 'utf8', timeout: 15000, windowsHide: true })
  assert.notEqual(result.status, 0)
  assert.match(result.stdout + result.stderr, /LIVE_MODEL_CHECK_FORBIDDEN/)
  assert.doesNotMatch(result.stdout, /DEMO_SESSION=/)
})
