[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$MavenArgs = @('verify'))
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskJavaHome = $env:JAVA_HOME
$taskLocalJava = Join-Path $taskRoot '.tools\java21'
if (Test-Path -LiteralPath $taskLocalJava) {
    $taskJavaHome = (Get-ChildItem -LiteralPath $taskLocalJava -Directory | Select-Object -First 1).FullName
}
$taskMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
$taskLocalMaven = Join-Path $taskRoot '.tools\maven'
if (Test-Path -LiteralPath $taskLocalMaven) {
    $taskMaven = Join-Path (Get-ChildItem -LiteralPath $taskLocalMaven -Directory | Select-Object -First 1).FullName 'bin\mvn.cmd'
} elseif ($taskMaven) {
    $taskMaven = $taskMaven.Source
}
if (!$taskJavaHome -or !$taskMaven) { throw 'Java 21 and Maven 3.9 are required. See README.' }
$taskPreviousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $taskJavaHome
    New-Item -ItemType Directory -Path (Join-Path $taskRoot 'backend\target\tmp') -Force | Out-Null
    # Windows PowerShell 5.1 wraps redirected native stderr as ErrorRecord.
    # Keep warnings in the log, and use Maven's exit code to determine failure.
    $taskPreviousErrorAction=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        & $taskMaven '-B' '-ntp' "-Dmaven.repo.local=$taskRoot\.cache\m2" '-f' (Join-Path $taskRoot 'backend\pom.xml') @MavenArgs 2>&1 | ForEach-Object { $_.ToString() }
    } finally { $ErrorActionPreference=$taskPreviousErrorAction }
    if ($LASTEXITCODE -ne 0) { throw "Maven failed (exit $LASTEXITCODE)." }
} finally {
    $env:JAVA_HOME = $taskPreviousJava
}
