import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { readSession, sessionDirectory, localOrigin, sendCommand } from './demo-session.mjs'
const directory=sessionDirectory(process.argv[2]),steps=[]
let status='FAIL'
try{
 const manifest=await readSession(directory),origin=localOrigin(JSON.parse(await readFile(join(directory,'frontend.json'),'utf8')).origin)
 async function request(method,path,token,body,expected=200,key){
  const response=await fetch(origin+'/api/v1'+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(key?{'Idempotency-Key':key}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(10000)})
  assert.equal(response.status,expected,method+' '+path);const text=await response.text();return text?JSON.parse(text):{}
 }
 async function step(id,action){await action();steps.push({id,status:'PASS'})}
 let admin,observer,incident,run,frozen
 await step('frontend-proxy-login',async()=>{
  admin=(await request('POST','/auth/login',null,manifest.admin)).token
  observer=(await request('POST','/auth/login',null,manifest.observer)).token
  assert.ok((await request('GET','/me',admin)).roles.includes('ON_CALL'))
  assert.equal((await request('GET','/overview',admin)).totalTargets,1)
 })
 await step('down-opens-incident',async()=>{
  const reply=await sendCommand(directory,'down');assert.equal(reply.inserted,3);assert.equal(reply.incidentStatus,'OPEN')
  const list=await request('GET','/incidents',admin);assert.equal(list.total,1);incident=list.items[0].id
 })
 await step('observer-cannot-operate',async()=>{
  await request('POST','/incidents/'+incident+'/transitions',observer,{version:0,action:'ACK',reason:'合成演示权限检查'},403)
 })
 async function transition(action){const detail=await request('GET','/incidents/'+incident,admin);await request('POST','/incidents/'+incident+'/transitions',admin,{version:detail.incident.version,action,reason:'交互式演示验收：'+action})}
 await step('acknowledge-investigate',async()=>{await transition('ACK');await transition('INVESTIGATE')})
 async function awaitStatus(expected){const until=Date.now()+40000;while(Date.now()<until){const current=await request('GET','/agent-runs/'+run,admin);if(current.status===expected)return current;await delay(250)}throw new Error('等待诊断状态超时：'+expected)}
 await step('real-outbox-python-review',async()=>{
  run=(await request('POST','/incidents/'+incident+'/agent-runs',admin,{},202,'demo-diagnosis')).runId
  frozen=await awaitStatus('WAITING_HUMAN');assert.ok(frozen.runbookEvidence.length>0);assert.equal(frozen.modelName,'mock')
 })
 await step('manual-approval-and-report',async()=>{
  await request('POST','/agent-runs/'+run+'/approval',admin,{approvalRequestId:frozen.approval.id,version:frozen.approval.version,decision:'APPROVED',reason:'仅批准线下核对，平台不执行修复'})
  await awaitStatus('APPROVED_FOR_MANUAL')
  await request('POST','/agent-runs/'+run+'/manual-action-report',admin,{outcome:'NOT_EXECUTED',note:'仅做独立演示验收，未执行线下修复。'},200,'demo-manual')
  assert.equal((await request('GET','/incidents/'+incident,admin)).incident.status,'INVESTIGATING')
 })
 await step('up-recovers-and-manual-close',async()=>{
  const reply=await sendCommand(directory,'up');assert.equal(reply.inserted,4);assert.equal(reply.incidentStatus,'RESOLVED')
  await transition('CLOSE');assert.equal((await request('GET','/incidents/'+incident,admin)).incident.status,'CLOSED')
 })
 status='PASS';console.log('PASS: frontend proxy, random demo accounts, terminal fault/recovery, Python checkpoint, approval, manual report and closure.')
}catch(error){console.error('FAIL: '+error.message);process.exitCode=1}
finally{await writeFile(join(directory,'check-result.json'),JSON.stringify({schemaVersion:1,status,mode:'synthetic/mock',steps},null,2)+'\n')}
