[CmdletBinding()]
param([ValidateSet('Install','Test','Run')][string]$Action='Run',[switch]$Dev,[ValidateRange(1024,65535)][int]$Port=8090)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskService=Join-Path $taskRoot 'agent-service'
$taskPython=Join-Path $taskService '.venv/Scripts/python.exe'
$taskTemp=Join-Path $taskService '.tmp'
New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
$taskPreviousTemp=$env:TEMP; $taskPreviousTmp=$env:TMP; $taskPreviousEncoding=$env:PYTHONUTF8
try {
    $env:TEMP=$taskTemp; $env:TMP=$taskTemp; $env:PYTHONUTF8='1'
    if($Action -eq 'Install'){
        if(!(Test-Path -LiteralPath $taskPython)){ & python -m venv (Join-Path $taskService '.venv');if($LASTEXITCODE -ne 0){throw 'Unable to create project venv.'} }
        $taskLock=if($Dev){'requirements-dev.lock'}else{'requirements.lock'}
        & $taskPython -m pip --isolated install --index-url https://pypi.org/simple --cache-dir (Join-Path $taskRoot '.cache/pip') --require-hashes --only-binary=:all: -r (Join-Path $taskService $taskLock)
    }elseif(!(Test-Path -LiteralPath $taskPython)){
        throw 'Run scripts/agent.ps1 Install -Dev first.'
    }else{
        Push-Location $taskService
        try{
            if($Action -eq 'Test'){& $taskPython -m pytest tests/test_graph.py tests/test_review.py tests/test_provider.py tests/test_scenarios.py tests/test_deepseek_check.py tests/test_deepseek_demo.py tests/test_quality_evaluation.py tests/test_evaluation_pilot.py tests/test_quality_review_package.py -q -p no:cacheprovider}
            else{
                if(!$env:AGENT_CHECKPOINT_DSN -or !$env:AGENT_INTERNAL_TOKEN){throw 'Set AGENT_CHECKPOINT_DSN and AGENT_INTERNAL_TOKEN in this terminal first. See README G3.'}
                & $taskPython -m uvicorn agentops_agent.app:from_environment --factory --host 127.0.0.1 --port $Port --no-access-log
            }
        }finally{Pop-Location}
    }
    if($LASTEXITCODE -ne 0){throw "Agent command failed (exit $LASTEXITCODE)."}
}finally{$env:TEMP=$taskPreviousTemp;$env:TMP=$taskPreviousTmp;$env:PYTHONUTF8=$taskPreviousEncoding}
