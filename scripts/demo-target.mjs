import { createServer } from 'node:http'
import { isIPv4 } from 'node:net'
import { networkInterfaces } from 'node:os'
import { once } from 'node:events'
import { createInterface } from 'node:readline'
import { parseArgs } from 'node:util'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'

// A disposable health endpoint. There is deliberately no HTTP control endpoint.
export function createDemoTarget() {
  let mode='up'
  const server=createServer((request,response)=>{
    const headers={'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','Connection':'close'}
    if(request.url!=='/health'){
      response.writeHead(404,headers);response.end('{"error":"NOT_FOUND"}');request.resume();return
    }
    if(!['GET','HEAD'].includes(request.method)){
      response.writeHead(405,{...headers,Allow:'GET, HEAD'});response.end();request.resume();return
    }
    const body=JSON.stringify({service:'agentops-demo',status:mode==='up'?'UP':'DOWN'})
    response.writeHead(mode==='up'?200:503,{...headers,'Content-Length':Buffer.byteLength(body)})
    response.end(request.method==='HEAD'?undefined:body)
  })
  server.headersTimeout=5000;server.requestTimeout=5000;server.maxConnections=32
  return {server,setMode(value){if(!['up','down'].includes(value))throw new Error('Mode must be up or down');mode=value},getMode(){return mode}}
}

export function validateBindAddress(host,interfaces=networkInterfaces()) {
  if(!isIPv4(host))throw new Error('请用 --host 指定本机 RFC1918 私网 IPv4 地址。')
  const [a,b]=host.split('.').map(Number)
  if(!(a===10 || a===172&&b>=16&&b<=31 || a===192&&b===168))throw new Error('测试服务只允许绑定 RFC1918 私网地址。')
  if(!Object.values(interfaces).flat().some(item=>item?.family==='IPv4'&&!item.internal&&item.address===host))throw new Error('指定的私网地址不属于本机。')
  return host
}

async function main() {
  const {values}=parseArgs({options:{host:{type:'string'},help:{type:'boolean'}}})
  if(values.help){console.log('node scripts/demo-target.mjs --host <本机私网IPv4>\n固定使用 HTTP 80；终端输入 down / up / status / quit。');return}
  const host=validateBindAddress(values.host||'')
  const demo=createDemoTarget()
  demo.server.listen(80,host)
  try{await once(demo.server,'listening')}
  catch(error){throw new Error(error.code==='EADDRINUSE'?'HTTP 80 已被占用；请保留已有服务，另选测试环境。':`测试服务启动失败：${error.code||'UNKNOWN'}`)}
  console.log(`本地测试服务已启动：${host}:80，初始状态 HTTP 200。`)
  console.log('输入 down 切换 503；输入 up 恢复 200；输入 status 查看状态；输入 quit 停止。')
  console.log(`目标批准网段和部署出站上限均可限定为 ${host}/32。`)
  const input=createInterface({input:process.stdin,output:process.stdout,terminal:!!process.stdin.isTTY})
  let closing=false
  function stop(){if(closing)return;closing=true;input.close();demo.server.close();demo.server.closeIdleConnections();setTimeout(()=>demo.server.closeAllConnections(),1000).unref()}
  input.on('line',line=>{
    const command=line.trim().toLowerCase()
    if(command==='quit'){stop();return}
    if(command==='up'||command==='down')demo.setMode(command)
    else if(command!=='status'){console.log('可用命令：down / up / status / quit');return}
    console.log(`当前状态：HTTP ${demo.getMode()==='up'?200:503}`)
  })
  input.on('close',stop);process.once('SIGINT',stop);process.once('SIGTERM',stop)
}

if(process.argv[1]&&fileURLToPath(import.meta.url)===resolve(process.argv[1])){
  main().catch(error=>{console.error(error.message);process.exitCode=1})
}
