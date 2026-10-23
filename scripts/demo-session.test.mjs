import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdir, mkdtemp, writeFile, readFile, rm } from 'node:fs/promises'
import { dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { sessionDirectory, localOrigin, sendCommand, readSession } from './demo-session.mjs'
const root=dirname(dirname(fileURLToPath(import.meta.url))),base=join(root,'.cache/demo')
async function fixture(action){await mkdir(base,{recursive:true});const dir=await mkdtemp(join(base,'protocol-test-'));try{await action(dir)}finally{assert.ok(resolve(dir).startsWith(resolve(base)+sep));await rm(dir,{recursive:true,force:true})}}
test('session paths stay in the project demo directory and origins are loopback only',()=>{
 assert.throws(()=>sessionDirectory(root));assert.throws(()=>sessionDirectory(join(root,'..','other-project')))
 assert.equal(localOrigin('http://127.0.0.1:12345'),'http://127.0.0.1:12345')
 for(const value of ['https://example.com','http://127.0.0.1:12345/path','http://user:pass@127.0.0.1:12345','http://localhost:12345'])assert.throws(()=>localOrigin(value))
})
test('command acknowledgments are matched to their request rather than an old success',async()=>fixture(async dir=>{
 await writeFile(join(dir,'reply.json'),JSON.stringify({requestId:'old',status:'PASS'}))
 const reply=sendCommand(dir,'status',{timeoutMs:1000,pollMs:5})
 let body
 for(let n=0;n<100;n++){try{body=JSON.parse(await readFile(join(dir,'command.json'),'utf8'));break}catch{await new Promise(r=>setTimeout(r,5))}}
 assert.equal(body.action,'status');assert.notEqual(body.requestId,'old')
 await writeFile(join(dir,'reply.json'),JSON.stringify({requestId:body.requestId,status:'PASS',mode:'UP'}))
 assert.equal((await reply).mode,'UP')
}))
test('busy, unknown and timed out commands fail without overwriting an existing command',async()=>fixture(async dir=>{
 await assert.rejects(sendCommand(dir,'delete-everything'))
 await mkdir(join(dir,'control.lock'));await writeFile(join(dir,'command.json'),'existing')
 await assert.rejects(sendCommand(dir,'down'))
 assert.equal(await readFile(join(dir,'command.json'),'utf8'),'existing')
 await rm(join(dir,'control.lock'),{recursive:true});await rm(join(dir,'command.json'))
 await assert.rejects(sendCommand(dir,'status',{timeoutMs:25,pollMs:5}),/超时/)
}))
test('expired sessions are rejected and quitting only writes this session stop marker',async()=>fixture(async dir=>{
 const manifest={schemaVersion:1,demo:true,backendOrigin:'http://127.0.0.1:12345',expiresAt:'invalid'}
 await writeFile(join(dir,'ready.json'),JSON.stringify(manifest));await assert.rejects(readSession(dir))
 manifest.expiresAt='2000-01-01T00:00:00Z';await writeFile(join(dir,'ready.json'),JSON.stringify(manifest));await assert.rejects(readSession(dir))
 assert.equal((await sendCommand(dir,'quit')).mode,'STOPPING');assert.equal(await readFile(join(dir,'stop'),'utf8'),'stop')
}))
