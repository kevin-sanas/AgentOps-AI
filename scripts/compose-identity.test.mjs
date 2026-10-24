import test from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
function render(overrides = {}) {
  const env = { ...process.env, DB_PASSWORD: 'test-only-not-real', SESSION_TOKEN_PEPPER: 'test-only-not-real', AGENT_INTERNAL_TOKEN: 'test-only-not-real', BOOTSTRAP_ADMIN_PASSWORD: 'test-only-not-real', CHECKPOINT_OWNER_PASSWORD: 'test-only-not-real', CHECKPOINT_PASSWORD: 'test-only-not-real', AGENT_LLM_API_KEY: '', KUMA_PUSH_TOKEN_DEMO: '' }
  delete env.COMPOSE_PROJECT_NAME
  delete env.DAWNWATCH_DATA_PREFIX
  Object.assign(env, overrides)
  return JSON.parse(execFileSync('docker', ['compose', '--env-file', '.env.example', '--profile', 'kuma', 'config', '--format', 'json'], { cwd: root, env, encoding: 'utf8', timeout: 20000, stdio: ['ignore', 'pipe', 'pipe'] }))
}
function volumeNames(config) {
  return ['postgres-data', 'checkpoint-data', 'kuma-data'].map(key => config.volumes[key].name)
}
test('new product name keeps all default existing physical data volumes', () => {
  const config = render({ DAWNWATCH_DATA_PREFIX: '' })
  assert.equal(config.name, 'dawnwatch')
  assert.deepEqual(volumeNames(config), ['agentops-platform_postgres-data', 'agentops-platform_checkpoint-data', 'agentops-platform_kuma-data'])
  assert.deepEqual(volumeNames(render()), volumeNames(config))
})
test('explicit data prefix preserves custom volumes independently of the new project name', () => {
  const config = render({ COMPOSE_PROJECT_NAME: 'dawnwatch-ci', DAWNWATCH_DATA_PREFIX: 'preserved-installation' })
  assert.equal(config.name, 'dawnwatch-ci')
  assert.deepEqual(volumeNames(config), ['preserved-installation_postgres-data', 'preserved-installation_checkpoint-data', 'preserved-installation_kuma-data'])
})
