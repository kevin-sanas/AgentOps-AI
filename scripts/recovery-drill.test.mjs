import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const shell = process.platform === 'win32' ? 'powershell.exe' : 'pwsh'

function runDrill(failMigration = false) {
  const stub = `
$global:taskTcpProbes=0
$global:taskFinalReady=$false
function global:docker {
  $taskDockerArgs=@($args)
  $null=@($input)
  $global:LASTEXITCODE=0
  if($taskDockerArgs[0] -eq 'run'){ return 'synthetic-container-id' }
  if($taskDockerArgs[0] -eq 'inspect'){ return 'true' }
  if($taskDockerArgs -contains 'pg_isready'){
    if($taskDockerArgs -contains '-h'){
      $global:taskTcpProbes++
      if($global:taskTcpProbes -lt 3){ $global:LASTEXITCODE=1 }
      else{ $global:taskFinalReady=$true }
    }
    return
  }
  if($taskDockerArgs -contains 'psql'){
    if(!$global:taskFinalReady){ $global:LASTEXITCODE=1; return 'SYNTHETIC_BOOTSTRAP_SERVER_NOT_READY' }
    if(${failMigration ? '$true' : '$false'} -and $taskDockerArgs -contains 'ON_ERROR_STOP=1'){
      $global:LASTEXITCODE=1; return 'SYNTHETIC_MIGRATION_ERROR'
    }
    if($taskDockerArgs -contains '-Atc'){
      if(($taskDockerArgs -join ' ') -match 'count\\(\\*\\)'){ return @('1','1','0') }
      return '44444444-4444-4444-8444-444444444444:readonly-review-v2'
    }
  }
}
try { & './scripts/recovery-drill.ps1'; Write-Output "TCP_READY_PROBES=$global:taskTcpProbes" }
catch { Write-Output $_.Exception.Message; exit 1 }
`
  return spawnSync(shell, ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', stub], { cwd: root, encoding: 'utf8', timeout: 15000, windowsHide: true })
}

test('recovery waits for final TCP server rather than socket-only bootstrap server', () => {
  const result = runDrill()
  assert.equal(result.status, 0, result.stdout + result.stderr)
  assert.match(result.stdout, /TCP_READY_PROBES=3/)
  assert.match(result.stdout, /PASS: PostgreSQL/)
})

test('synthetic migration failures retain useful diagnostic output', () => {
  const result = runDrill(true)
  assert.notEqual(result.status, 0)
  assert.match(result.stdout + result.stderr, /SYNTHETIC_MIGRATION_ERROR/)
})
