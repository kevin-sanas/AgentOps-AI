export interface WorkTab {key:string;fullPath:string;title:string;returnTo?:string}
export function viewKey(path:string) { return path.split(/[?#]/)[0] || '/overview' }
export function safeReturnPath(value:unknown,fallback='/overview'):string {
  return typeof value==='string'&&value.length<=2048&&!value.includes('\\')&&/^\/(?:overview|targets(?:\/(?:new|[a-z0-9-]+))?|incidents(?:\/[a-z0-9-]+)?|diagnosis|agents|runbooks(?:\/[a-z0-9-]+)?|agent-runs\/[a-z0-9-]+|admin\/users)(?:[?#].*)?$/.test(value)?value:fallback
}
export function visitTab(tabs:WorkTab[],entry:{fullPath:string;title:string},from?:string) {
  const key=viewKey(entry.fullPath),found=tabs.find(t=>t.key===key)
  if(found){found.fullPath=entry.fullPath;return found}
  const tab:WorkTab={key,fullPath:entry.fullPath,title:entry.title,...(from&&viewKey(from)!==key?{returnTo:safeReturnPath(from)}:{})}
  tabs.push(tab);return tab
}
export function closeTab(tabs:WorkTab[],key:string,current:string) {
  if(key==='/overview')return current
  const index=tabs.findIndex(t=>t.key===key);if(index<0)return current
  tabs.splice(index,1)
  return viewKey(current)===key?(tabs[Math.max(0,index-1)]?.fullPath||'/overview'):current
}
export function runAction(run:{status:string;approvalStatus?:string|null;canOperate?:boolean}) {
  if(run.status==='WAITING_HUMAN'&&run.approvalStatus==='PENDING')return {label:run.canOperate?'立即审阅':'查看审阅记录',hash:'#review-heading'}
  if(run.status==='APPROVED_FOR_MANUAL')return {label:run.canOperate?'回填处理结果':'查看审阅记录',hash:'#review-heading'}
  if(run.status==='FAILED')return {label:'查看失败原因',hash:'#delivery-error'}
  return {label:run.status==='COMPLETED'?'查看诊断报告':'查看诊断进度',hash:run.status==='COMPLETED'?'#report':'#execution'}
}
