import test from 'node:test'
import assert from 'node:assert/strict'
import { once } from 'node:events'
import { createDemoTarget, validateBindAddress } from './demo-target.mjs'

async function fixture(t) {
  const demo=createDemoTarget()
  demo.server.listen(0,'127.0.0.1');await once(demo.server,'listening')
  t.after(()=>new Promise((resolve,reject)=>demo.server.close(e=>e?reject(e):resolve())))
  return {...demo,url:`http://127.0.0.1:${demo.server.address().port}/health`}
}
test('the same health URL returns 200, then 503, then 200 without configuration changes',async t=>{
  const demo=await fixture(t)
  let response=await fetch(demo.url)
  assert.equal(response.status,200);assert.deepEqual(await response.json(),{service:'agentops-demo',status:'UP'})
  demo.setMode('down');response=await fetch(demo.url)
  assert.equal(response.status,503);assert.deepEqual(await response.json(),{service:'agentops-demo',status:'DOWN'})
  demo.setMode('up');response=await fetch(demo.url)
  assert.equal(response.status,200);await response.arrayBuffer()
  assert.throws(()=>demo.setMode('invalid'))
  response=await fetch(demo.url);assert.equal(response.status,200);await response.arrayBuffer()
})
test('HEAD reports the current status with no response body',async t=>{
  const demo=await fixture(t)
  for(const mode of ['up','down']){
    demo.setMode(mode);const response=await fetch(demo.url,{method:'HEAD'})
    assert.equal(response.status,mode==='up'?200:503);assert.equal(await response.text(),'')
    assert.equal(response.headers.get('cache-control'),'no-store')
  }
})
test('HTTP clients cannot change state or access other resources',async t=>{
  const demo=await fixture(t)
  for(const suffix of ['/down','/up','/?status=503','/health?status=503']){
    const response=await fetch(demo.url.replace('/health','')+suffix)
    assert.equal(response.status,404);await response.arrayBuffer()
  }
  const post=await fetch(demo.url,{method:'POST',body:'down'})
  assert.equal(post.status,405);assert.equal(post.headers.get('allow'),'GET, HEAD');await post.arrayBuffer()
  const health=await fetch(demo.url);assert.equal(health.status,200);await health.arrayBuffer()
})
test('CLI binding is restricted to a private IPv4 address owned by this machine',()=>{
  const interfaces={test:[{address:'10.20.1.2',family:'IPv4',internal:false},{address:'127.0.0.1',family:'IPv4',internal:true}]}
  assert.equal(validateBindAddress('10.20.1.2',interfaces),'10.20.1.2')
  for(const host of ['0.0.0.0','127.0.0.1','169.254.169.254','93.184.216.34','10.20.1.3','::1','010.20.1.2'])
    assert.throws(()=>validateBindAddress(host,interfaces),host)
})
