[CmdletBinding()]
param([switch]$Check,[switch]$NoInput,[switch]$DeepSeek)
$ErrorActionPreference='Stop'
if($DeepSeek -and $Check){throw 'LIVE_MODEL_CHECK_FORBIDDEN: real model mode requires explicit local Key input and manual acceptance.'}
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskNode=(Get-Command node -ErrorAction Stop).Source
foreach($taskNeeded in @('agent-service/.venv/Scripts/python.exe','frontend/node_modules/vite/bin/vite.js')){if(!(Test-Path -LiteralPath (Join-Path $taskRoot $taskNeeded))){throw '请先按专属启动指南安装项目Python开发依赖和前端依赖。'}}
$taskJavaHome=$env:JAVA_HOME
if(Test-Path -LiteralPath (Join-Path $taskRoot '.tools/java21')){$taskJavaHome=(Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/java21') -Directory | Select-Object -First 1).FullName}
if(!$taskJavaHome){throw '需要Java21，请核对IDEA启动与验收指南。'}
$taskJava=Join-Path $taskJavaHome 'bin/java.exe'
$taskRun=Join-Path $taskRoot ('.cache/demo/'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $taskRun -Force | Out-Null
$taskJavaProcess=$null;$taskViteProcess=$null;$taskSuccess=$false
function Start-DemoProcess([string]$Executable,[string[]]$Arguments,[string]$WorkingDirectory,[string]$Name){
    $taskQuoted=($Arguments | ForEach-Object {if($_.Contains('"')){throw 'Unexpected quote in process argument'};'"'+$_+'"'}) -join ' '
    Start-Process -FilePath $Executable -ArgumentList $taskQuoted -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskRun ($Name+'.out.log')) -RedirectStandardError (Join-Path $taskRun ($Name+'.err.log')) -PassThru
}
function Wait-DemoFile([string]$Name,[Diagnostics.Process]$Process){
    $taskUntil=[DateTime]::UtcNow.AddSeconds(75)
    while(!(Test-Path -LiteralPath (Join-Path $taskRun $Name))){
        if($Process.HasExited){throw "演示服务提前退出，请查看 $taskRun 中的日志。"}
        if([DateTime]::UtcNow -ge $taskUntil){throw "演示启动超时，请查看 $taskRun 中的日志。"}
        Start-Sleep -Milliseconds 200
    }
}
function Stop-DemoProcess([Diagnostics.Process]$Process){
    if(!$Process -or $Process.HasExited){return}
    if($Process.WaitForExit(20000)){return}
    # Fallback applies only to the process tree launched by this invocation.
    $taskSnapshot=@(Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId)
    $taskOwned=[Collections.Generic.List[int]]::new();$taskOwned.Add($Process.Id)
    for($taskIndex=0;$taskIndex -lt $taskOwned.Count;$taskIndex++){foreach($taskChild in $taskSnapshot | Where-Object ParentProcessId -eq $taskOwned[$taskIndex]){$taskOwned.Add([int]$taskChild.ProcessId)}}
    for($taskIndex=$taskOwned.Count-1;$taskIndex -ge 0;$taskIndex--){$taskChild=Get-Process -Id $taskOwned[$taskIndex] -ErrorAction SilentlyContinue;if($taskChild){$taskChild | Stop-Process -Force -ErrorAction SilentlyContinue}}
    $Process.WaitForExit(5000) | Out-Null
}
Push-Location $taskRoot
try{
    if($DeepSeek){Write-Host '准备真实模型隔离验收环境（不连接日常数据库；页面发起诊断时才请求DeepSeek）…'}else{Write-Host '准备独立演示环境（不连接日常数据库，不调用外部模型）…'}
    $taskClasspathFile=Join-Path $taskRun 'classpath.txt'
    & (Join-Path $PSScriptRoot 'backend.ps1') test-compile 'org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath' '-Dmdep.includeScope=test' "-Dmdep.outputFile=$taskClasspathFile" *> (Join-Path $taskRun 'compile.log')
    $taskClasspath=(Join-Path $taskRoot 'backend/target/test-classes')+';'+(Join-Path $taskRoot 'backend/target/classes')+';'+[IO.File]::ReadAllText($taskClasspathFile).Trim()
    $taskJavaArgs=@("-Dagentops.demo.directory=$taskRun","-Dagentops.demo.parent=$PID")
    if($DeepSeek){$taskJavaArgs+='-Dagentops.demo.deepseek=true'}
    $taskJavaArgs+=@('-cp',$taskClasspath,'dev.agentops.DemoRuntime')
    if($DeepSeek){
        Write-Host 'DeepSeek直连验收：仅合成证据；本会话最多1次真实请求，不自动付费重试。请先确认本轮预算。'
        $taskPreviousDeepSeekKey=$env:AGENT_LLM_API_KEY
        $taskSecureKey=Read-Host '请输入DeepSeek API Key（输入不显示）' -AsSecureString
        try{
            $env:AGENT_LLM_API_KEY=[Net.NetworkCredential]::new('', $taskSecureKey).Password
            if([string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)){throw 'LIVE_MODEL_KEY_REQUIRED'}
            $taskJavaProcess=Start-DemoProcess $taskJava $taskJavaArgs (Join-Path $taskRoot 'backend') 'runtime'
        }finally{
            if($null -eq $taskPreviousDeepSeekKey){Remove-Item Env:AGENT_LLM_API_KEY -ErrorAction SilentlyContinue}else{$env:AGENT_LLM_API_KEY=$taskPreviousDeepSeekKey}
            $taskSecureKey.Dispose();$taskSecureKey=$null;$taskPreviousDeepSeekKey=$null
        }
    }else{$taskJavaProcess=Start-DemoProcess $taskJava $taskJavaArgs (Join-Path $taskRoot 'backend') 'runtime'}
    Wait-DemoFile 'ready.json' $taskJavaProcess
    $taskManifest=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $taskRun 'ready.json') | ConvertFrom-Json
    $taskListener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$taskListener.Start();$taskFrontendPort=$taskListener.LocalEndpoint.Port;$taskListener.Stop()
    $taskViteProcess=Start-DemoProcess $taskNode @((Join-Path $PSScriptRoot 'demo-vite.mjs'),$taskRun,[string]$taskFrontendPort,[string]$PID) $taskRoot 'frontend'
    Wait-DemoFile 'frontend.json' $taskViteProcess
    $taskFrontend=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $taskRun 'frontend.json') | ConvertFrom-Json
    Write-Host "演示工作台：$($taskFrontend.origin)/login"
    Write-Host "DEMO_SESSION=$taskRun"
    if($DeepSeek){Write-Host '当前为真实DeepSeek直连模式；页面标明合成检查与真实模型，模型调用上限1次。'}
    if($Check){
        & $taskNode (Join-Path $PSScriptRoot 'demo-smoke.mjs') $taskRun
        if($LASTEXITCODE -ne 0){throw "演示验收未通过，请查看 $taskRun/check-result.json。"}
    }elseif($NoInput){
        while(!$taskJavaProcess.HasExited -and !(Test-Path -LiteralPath (Join-Path $taskRun 'stop'))){Start-Sleep -Milliseconds 300}
    }else{
        Write-Host "管理员账号：$($taskManifest.admin.username)    本次密码：$($taskManifest.admin.password)"
        Write-Host "观察员账号：$($taskManifest.observer.username)    本次密码：$($taskManifest.observer.password)"
        Write-Host 'down 注入3条503；up 注入4条200；status查看；quit退出。每60秒还会记录一条当前模式的合成样本。最长两小时，退出后数据清除。'
        while(!$taskJavaProcess.HasExited){
            $taskCommand=Read-Host 'demo'
            if($null -eq $taskCommand){break};$taskCommand=$taskCommand.Trim().ToLowerInvariant()
            if($taskCommand -eq 'quit'){break}
            if($taskCommand -notin @('down','up','status')){Write-Host '请输入 down / up / status / quit';continue}
            $taskReply=& $taskNode (Join-Path $PSScriptRoot 'demo-session.mjs') $taskRun $taskCommand
            if($LASTEXITCODE -eq 0){$taskState=$taskReply | ConvertFrom-Json;Write-Host "合成模式 $($taskState.mode)，新增样本 $($taskState.inserted)，最近事故 $($taskState.incidentStatus)"}
        }
    }
    $taskSuccess=$true
}finally{
    [IO.File]::WriteAllText((Join-Path $taskRun 'stop'),'stop')
    Stop-DemoProcess $taskJavaProcess
    Stop-DemoProcess $taskViteProcess
    $taskRunAbsolute=[IO.Path]::GetFullPath($taskRun)
    $taskBase=[IO.Path]::GetFullPath((Join-Path $taskRoot '.cache/demo'))+[IO.Path]::DirectorySeparatorChar
    if(!$taskRunAbsolute.StartsWith($taskBase,[StringComparison]::OrdinalIgnoreCase)){throw 'Refusing cleanup outside owned demo directory'}
    if((!$taskJavaProcess -or $taskJavaProcess.HasExited) -and (!$taskViteProcess -or $taskViteProcess.HasExited)){
        foreach($taskName in @('ready.json','ready.json.tmp','reply.json','reply.json.tmp','command.json','frontend.json','frontend.json.tmp','stop','classpath.txt')){ $taskFile=Join-Path $taskRun $taskName;if(Test-Path -LiteralPath $taskFile){Remove-Item -LiteralPath $taskFile -Force} }
        foreach($taskName in @('postgres','control.lock')){$taskDirectory=[IO.Path]::GetFullPath((Join-Path $taskRun $taskName));if(!$taskDirectory.StartsWith($taskRunAbsolute+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Refusing cleanup outside owned demo directory'};if(Test-Path -LiteralPath $taskDirectory){Remove-Item -LiteralPath $taskDirectory -Recurse -Force}}
    }
    $taskManifest=$null
    [IO.File]::WriteAllText((Join-Path $taskRun 'session-result.json'),(@{success=$taskSuccess;servicesStopped=(!$taskJavaProcess -or $taskJavaProcess.HasExited) -and (!$taskViteProcess -or $taskViteProcess.HasExited);credentialsRemoved=!(Test-Path -LiteralPath (Join-Path $taskRun 'ready.json'));finishedAt=[DateTimeOffset]::UtcNow.ToString('o')} | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Pop-Location
    Write-Host '独立演示已结束；本次日志及脱敏结果保留在DEMO_SESSION目录。'
}
