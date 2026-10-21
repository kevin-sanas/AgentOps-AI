[CmdletBinding()]
param([string]$Bom = 'backend/target/backend-sbom.json', [string]$Output = 'backend/target/dependency-audit', [switch]$SkipGenerate)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskBom = [IO.Path]::GetFullPath((Join-Path $taskRoot $Bom))
$taskOutput = [IO.Path]::GetFullPath((Join-Path $taskRoot $Output))
if (!$taskOutput.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar)) { throw 'Output must stay in the project.' }
if (!$SkipGenerate) {
    & (Join-Path $PSScriptRoot 'backend.ps1') -MavenArgs @('org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom','-Pwindows-tests,linux-tests','-DincludeTestScope=true','-DincludeLicenseText=true','-DoutputFormat=json','-DoutputName=backend-sbom','-Dcyclonedx.skipAttach=true')
}
$taskDocument = Get-Content -LiteralPath $taskBom -Raw | ConvertFrom-Json
$taskComponents = @($taskDocument.components | Where-Object { $_.purl -like 'pkg:maven/*' })
if (!$taskComponents.Count) { throw 'SBOM contains no Maven packages.' }
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
Copy-Item -LiteralPath $taskBom -Destination (Join-Path $taskOutput 'sbom.json')
$taskLicenses = @($taskComponents | ForEach-Object {
    [pscustomobject]@{ group=$_.group; name=$_.name; version=$_.version; scope=$_.scope; purl=$_.purl
        licenses=@($_.licenses | ForEach-Object { if ($_.expression) { $_.expression } elseif ($_.license.id) { $_.license.id } else { $_.license.name } })
        review=if (@($_.licenses).Count) { 'DECLARED_LICENSE_REVIEW_REQUIRED' } else { 'MISSING_LICENSE' } }
})
$taskLicenses | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $taskOutput 'licenses.json') -Encoding utf8
$taskResults = [Collections.Generic.List[object]]::new()
function Invoke-Osv($TaskBody) {
    for ($taskAttempt=0; $taskAttempt -lt 3; $taskAttempt++) {
        try { return Invoke-RestMethod -Uri 'https://api.osv.dev/v1/querybatch' -Method Post -ContentType 'application/json' -Body ($TaskBody | ConvertTo-Json -Depth 15 -Compress) -TimeoutSec 45 }
        catch { if ($taskAttempt -eq 2) { throw }; Start-Sleep -Seconds (1+$taskAttempt) }
    }
}
try {
    for ($taskOffset=0; $taskOffset -lt $taskComponents.Count; $taskOffset+=100) {
        $taskBatch = @($taskComponents | Select-Object -Skip $taskOffset -First 100)
        $taskQueries = @($taskBatch | ForEach-Object { @{package=@{ecosystem='Maven';name=($_.group+':'+$_.name)};version=$_.version} })
        $taskResponse = Invoke-Osv @{queries=$taskQueries}
        if (@($taskResponse.results).Count -ne $taskBatch.Count) { throw 'OSV returned an incomplete batch.' }
        for ($taskIndex=0; $taskIndex -lt $taskBatch.Count; $taskIndex++) {
            $taskResult = $taskResponse.results[$taskIndex]
            $taskIds = [Collections.Generic.List[string]]::new()
            while ($true) {
                foreach ($taskVuln in $taskResult.vulns) { $taskIds.Add($taskVuln.id) }
                if (!$taskResult.next_page_token) { break }
                $taskNextQuery = $taskQueries[$taskIndex].Clone()
                $taskNextQuery.page_token = $taskResult.next_page_token
                $taskResult = (Invoke-Osv @{queries=@($taskNextQuery)}).results[0]
            }
            $taskResults.Add([pscustomobject]@{purl=$taskBatch[$taskIndex].purl;vulnerabilityIds=@($taskIds | Sort-Object -Unique)})
        }
    }
    $taskStatus='COMPLETE_QUERY_REVIEW_REQUIRED'
} catch {
    $taskStatus='INCOMPLETE'
    Write-Warning 'OSV query incomplete. The report records this and must not be counted as passing.'
} finally {
    [ordered]@{schemaVersion=1;generatedAt=[DateTimeOffset]::UtcNow.ToString('o');source='https://api.osv.dev/v1/querybatch'
        status=$taskStatus;sbomSha256=(Get-FileHash $taskBom -Algorithm SHA256).Hash;components=$taskComponents.Count;queried=$taskResults.Count
        packages=@($taskResults);limitations=@('Version matches require advisory and reachability review.','No container OS/native-library scan.','No Maven plugin/toolchain scan.','License declarations require human review including bundled native components.')
    } | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $taskOutput 'osv-results.json') -Encoding utf8
}
Write-Output "OSV: $taskStatus; $($taskResults.Count)/$($taskComponents.Count) queried"
if ($taskStatus -eq 'INCOMPLETE') { exit 1 }
$taskAffected = @($taskResults | Where-Object {$_.vulnerabilityIds.Count -gt 0})
Write-Output "Components with version matches requiring review: $($taskAffected.Count)"
if ($taskAffected.Count -gt 0) { exit 2 }
