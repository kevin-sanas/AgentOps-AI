[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskRun=Join-Path $taskRoot ('.cache/acceptance/'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
$taskStarted=[DateTimeOffset]::UtcNow.ToString('o')
$taskStages=[Collections.Generic.List[object]]::new()
if(!(Get-Command node -ErrorAction SilentlyContinue)){throw '需要 Node.js 22.12+；按 IDEA启动与验收指南安装现有项目依赖。'}
New-Item -ItemType Directory -Path $taskRun -Force | Out-Null
$taskJourney=Join-Path $taskRun 'journey.json'
function Invoke-AcceptanceStep([string]$Id,[scriptblock]$Command){
    # Native warnings must not abort PowerShell 5.1 logging. Each command below
    # explicitly checks its exit code; project wrapper scripts throw on failure.
    $ErrorActionPreference='Continue'
    $taskLog=Join-Path $taskRun ($Id+'.log')
    $taskCode=0
    Write-Host "正在验收：$Id"
    try{& $Command *> $taskLog}catch{Add-Content -LiteralPath $taskLog -Value $_.Exception.Message;$taskCode=1}
    $taskStatus=if($taskCode -eq 0){'PASS'}else{'FAIL'}
    $taskStages.Add(@{id=$Id;status=$taskStatus;exitCode=$taskCode;log=$Id+'.log'})
    Write-Host "${Id}: $taskStatus"
}
Push-Location $taskRoot
try{
    $taskRevision=(& git rev-parse HEAD 2>$null)
    if($LASTEXITCODE -ne 0){$taskRevision='unknown'}
    $taskDirty=[bool](& git status --porcelain 2>$null)
    Invoke-AcceptanceStep 'backend' {& (Join-Path $PSScriptRoot 'backend.ps1') verify "-Dagentops.acceptance.report=$taskJourney"}
    Invoke-AcceptanceStep 'python' {& (Join-Path $PSScriptRoot 'agent.ps1') Test}
    Invoke-AcceptanceStep 'frontend-tests' {& npm.cmd --prefix frontend test;if($LASTEXITCODE -ne 0){throw '前端回归失败，详见本阶段日志。'}}
    Invoke-AcceptanceStep 'frontend-build' {& npm.cmd --prefix frontend run build;if($LASTEXITCODE -ne 0){throw '前端类型/构建失败，详见本阶段日志。'}}
    Invoke-AcceptanceStep 'demo-target' {& node --test scripts/demo-target.test.mjs scripts/demo-session.test.mjs;if($LASTEXITCODE -ne 0){throw '演示目标/会话契约失败，详见本阶段日志。'}}
    Invoke-AcceptanceStep 'report-tests' {& node --test scripts/acceptance-report.test.mjs;if($LASTEXITCODE -ne 0){throw '报告回归失败，详见本阶段日志。'}}
    $taskJourneyData=$null
    if(Test-Path -LiteralPath $taskJourney){try{$taskJourneyData=Get-Content -Raw -Encoding UTF8 -LiteralPath $taskJourney | ConvertFrom-Json}catch{Write-Warning '本次旅程记录无法解析，报告会标为未通过。'}}
    $taskInput=@{revision=[string]$taskRevision;dirty=$taskDirty;startedAt=$taskStarted;finishedAt=[DateTimeOffset]::UtcNow.ToString('o');stages=@($taskStages.ToArray());journey=$taskJourneyData}
    $taskMetadata=Join-Path $taskRun 'input.json'
    [IO.File]::WriteAllText($taskMetadata,($taskInput | ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    & node (Join-Path $PSScriptRoot 'acceptance-report.mjs') $taskMetadata
    if($LASTEXITCODE -ne 0){throw "验收未通过，请打开 $taskRun\report.html 查看失败步骤。"}
    Write-Host '核心验收通过。临时数据库/服务随测试关闭；本次报告和日志保留在上方目录。'
}finally{Pop-Location}
