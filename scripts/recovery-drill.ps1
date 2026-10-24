[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskName='agentops-recovery-'+[guid]::NewGuid().ToString('N')
$taskImage='postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24'
function Invoke-DrillDocker([string[]]$Arguments){
    $taskOutput=& docker @Arguments 2>&1
    if($LASTEXITCODE -ne 0){throw "Recovery Docker step failed: $taskOutput"}
    return $taskOutput
}
try{
    Invoke-DrillDocker @('run','-d','--name',$taskName,'--label','agentops.recovery-fixture=true','--network','none','--tmpfs','/var/lib/postgresql/data','-e','POSTGRES_HOST_AUTH_METHOD=trust','-e','POSTGRES_DB=drill_source',$taskImage) | Out-Null
    $taskReady=$false
    for($taskAttempt=0;$taskAttempt -lt 80;$taskAttempt++){
        # The image's initialization server accepts socket connections before
        # database setup is complete. TCP is enabled only on the final server.
        & docker exec $taskName pg_isready -h 127.0.0.1 -U postgres -d drill_source *> $null
        if($LASTEXITCODE -eq 0){$taskReady=$true;break};Start-Sleep -Milliseconds 250
    }
    if(!$taskReady){throw 'Isolated recovery PostgreSQL did not start'}
    $taskSql=(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'db/migration') -Filter 'V*.sql' | Sort-Object Name | ForEach-Object {[IO.File]::ReadAllText($_.FullName)}) -join "`n"
    $taskMigrationOutput=$taskSql | & docker exec -i $taskName psql -U postgres -d drill_source -v ON_ERROR_STOP=1 2>&1
    if($LASTEXITCODE -ne 0){throw "Fixture migrations failed: $taskMigrationOutput"}
    $taskSeed=@'
INSERT INTO app_user(id,username,password_hash,display_name) VALUES('11111111-1111-4111-8111-111111111111','restore-fixture','fixture-not-a-login-hash','Restore fixture');
INSERT INTO service_target(id,code,name,environment,base_url,created_by) VALUES('22222222-2222-4222-8222-222222222222','restore-fixture','Restore fixture','test','https://fixture.example.test/health','11111111-1111-4111-8111-111111111111');
INSERT INTO incident(id,target_id,summary,last_failure_at) VALUES('33333333-3333-4333-8333-333333333333','22222222-2222-4222-8222-222222222222','Synthetic recovery fixture',now());
INSERT INTO agent_run(id,incident_id,target_id,requested_by,request_id,status,graph_version,input) VALUES('44444444-4444-4444-8444-444444444444','33333333-3333-4333-8333-333333333333','22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111','fixture','RUNNING','readonly-review-v2','{}');
INSERT INTO outbox_event(id,run_id,type,state,lease_token,lease_until) VALUES('55555555-5555-4555-8555-555555555555','44444444-4444-4444-8444-444444444444','AGENT_RUN_REQUESTED','LEASED','66666666-6666-4666-8666-666666666666',now()-interval '1 second');
CREATE DATABASE checkpoint_source;
'@
    $taskSeed | & docker exec -i $taskName psql -U postgres -d drill_source -v ON_ERROR_STOP=1 *> $null
    if($LASTEXITCODE -ne 0){throw 'Fixture seed failed'}
    # Paired run marker is a backup consistency check, not a replacement for LangGraph integration tests.
    "CREATE SCHEMA agent_checkpoint; CREATE TABLE agent_checkpoint.drill_marker(run_id uuid PRIMARY KEY,graph_version text); INSERT INTO agent_checkpoint.drill_marker VALUES('44444444-4444-4444-8444-444444444444','readonly-review-v2');" | & docker exec -i $taskName psql -U postgres -d checkpoint_source -v ON_ERROR_STOP=1 *> $null
    if($LASTEXITCODE -ne 0){throw 'Checkpoint marker seed failed'}
    foreach($taskPair in @(@('drill_source','business_restored'),@('checkpoint_source','checkpoint_restored'))){
        Invoke-DrillDocker @('exec',$taskName,'pg_dump','-U','postgres','-Fc','-f',('/tmp/'+$taskPair[0]+'.dump'),$taskPair[0]) | Out-Null
        Invoke-DrillDocker @('exec',$taskName,'createdb','-U','postgres',$taskPair[1]) | Out-Null
        Invoke-DrillDocker @('exec',$taskName,'pg_restore','-U','postgres','--exit-on-error','-d',$taskPair[1],('/tmp/'+$taskPair[0]+'.dump')) | Out-Null
    }
    $taskBusiness=Invoke-DrillDocker @('exec',$taskName,'psql','-U','postgres','-d','business_restored','-Atc',"SELECT count(*) FROM incident WHERE status='OPEN'; SELECT count(*) FROM outbox_event WHERE state='LEASED' AND lease_until<now(); SELECT count(*) FROM integration_config;")
    if(($taskBusiness -join ',') -ne '1,1,0'){throw 'Restored incident/expired lease/migration assertions failed'}
    $taskMarker=Invoke-DrillDocker @('exec',$taskName,'psql','-U','postgres','-d','checkpoint_restored','-Atc',"SELECT run_id::text||':'||graph_version FROM agent_checkpoint.drill_marker")
    if(($taskMarker -join '') -ne '44444444-4444-4444-8444-444444444444:readonly-review-v2'){throw 'Paired checkpoint marker mismatch'}
    Write-Output 'PASS: PostgreSQL 17.11 custom dumps restored into two NEW databases; incident, expired Outbox lease, V6 and paired marker retained.'
    Write-Output 'Scope: synthetic SQL backup/restore only. Real LangGraph checkpoint and worker takeover are verified by backend tests.'
}finally{
    $taskOwned=& docker inspect --format '{{index .Config.Labels "agentops.recovery-fixture"}}' $taskName 2>$null
    if($LASTEXITCODE -eq 0 -and $taskOwned -eq 'true'){& docker rm -f $taskName *> $null}
}
