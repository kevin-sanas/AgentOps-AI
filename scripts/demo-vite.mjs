import { readFile, writeFile, rename, rm, access } from 'node:fs/promises'
import { dirname, resolve, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { readSession, sessionDirectory } from './demo-session.mjs'
const root=dirname(dirname(fileURLToPath(import.meta.url))),directory=sessionDirectory(process.argv[2]),port=Number(process.argv[3]),parent=Number(process.argv[4])
const manifest=await readSession(directory)
process.env.BACKEND_API_ORIGIN=manifest.backendOrigin;process.env.VITE_AGENTOPS_DEMO='true'
process.env.VITE_AGENTOPS_LIVE_MODEL=manifest.modelName==='deepseek-flash'?'deepseek-flash':''
const {createServer}=await import(pathToFileURL(join(root,'frontend/node_modules/vite/dist/node/index.js')))
const server=await createServer({root:join(root,'frontend'),configFile:join(root,'frontend/vite.config.ts'),server:{host:'127.0.0.1',port,strictPort:true}})
await server.listen()
await writeFile(join(directory,'frontend.json.tmp'),JSON.stringify({origin:`http://127.0.0.1:${port}`}));await rename(join(directory,'frontend.json.tmp'),join(directory,'frontend.json'))
let closing=false
async function stop(){if(closing)return;closing=true;clearInterval(watchdog);await server.close();await rm(join(directory,'frontend.json'),{force:true})}
const watchdog=setInterval(async()=>{try{process.kill(parent,0);await access(join(directory,'ready.json'))}catch{await stop()}},500)
process.once('SIGINT',stop);process.once('SIGTERM',stop)
