import { access, mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { dirname, resolve, sep, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
const root=dirname(dirname(fileURLToPath(import.meta.url)))
export function sessionDirectory(value){
 const path=resolve(value),base=resolve(root,'.cache/demo')+sep
 if(!path.toLowerCase().startsWith(base.toLowerCase()))throw new Error('只允许当前项目 .cache/demo 下的独立会话目录。')
 return path
}
export function localOrigin(value){
 if(typeof value!=='string'||!/^http:\/\/127\.0\.0\.1:\d{4,5}$/.test(value)||Number(value.split(':').at(-1))<1024||Number(value.split(':').at(-1))>65535)throw new Error('演示服务必须使用本机随机端口。')
 return value
}
export async function readSession(directory){
 const manifest=JSON.parse(await readFile(join(sessionDirectory(directory),'ready.json'),'utf8'))
 if(manifest.schemaVersion!==1||manifest.demo!==true||!Number.isFinite(Date.parse(manifest.expiresAt))||Date.parse(manifest.expiresAt)<=Date.now())throw new Error('演示会话无效或已过期。')
 localOrigin(manifest.backendOrigin);return manifest
}
export async function sendCommand(directory,action,{timeoutMs=15000,pollMs=100}={}){
 const dir=sessionDirectory(directory)
 if(!['down','up','status','quit'].includes(action))throw new Error('可用命令：down / up / status / quit')
 if(action==='quit'){await writeFile(join(dir,'stop'),'stop');return {status:'PASS',mode:'STOPPING'}}
 const lock=join(dir,'control.lock'),requestId=randomUUID(),temporary=join(dir,'command-'+requestId+'.tmp')
 try{await mkdir(lock)}catch(error){if(error.code==='EEXIST')throw new Error('上一命令仍在处理，请稍后重试。');throw error}
 try{
  let pending=false;try{await access(join(dir,'command.json'));pending=true}catch{}
  if(pending)throw new Error('上一命令仍在处理，请稍后重试。')
  await writeFile(temporary,JSON.stringify({requestId,action}));await rename(temporary,join(dir,'command.json'))
  const until=Date.now()+timeoutMs
  while(Date.now()<until){
   let reply;try{reply=JSON.parse(await readFile(join(dir,'reply.json'),'utf8'))}catch{}
   if(reply?.requestId===requestId){if(reply.status!=='PASS')throw new Error('演示命令未完成：'+(reply.code||'COMMAND_FAILED'));return reply}
   await delay(pollMs)
  }
  throw new Error('演示命令超时；请检查终端服务是否仍在运行。')
 }finally{await rm(temporary,{force:true});await rm(lock,{recursive:true,force:true})}
}
if(process.argv[1]&&fileURLToPath(import.meta.url)===resolve(process.argv[1])){
 try{console.log(JSON.stringify(await sendCommand(process.argv[2],process.argv[3])))}catch(error){console.error(error.message);process.exitCode=1}
}
