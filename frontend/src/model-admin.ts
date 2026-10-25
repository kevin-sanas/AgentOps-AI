import type { ModelBalanceReply,ModelConfigurationStatus,ModelUsageRequest } from './types'

export type BalanceRow={currency:string;total:string;granted:string;toppedUp:string}
export type BalanceState={status:'idle'|'loading'|'ready'|'error';rows:BalanceRow[];message:string}
export type BalanceEvent={type:'loading'}|{type:'success';reply:ModelBalanceReply}|{type:'error';message:string}|{type:'reset'}

export const MODEL_SETTINGS_PATH='/admin/model'
export const MODEL_SETTINGS_META={title:'模型配置与用量',admin:true} as const
export function canViewModelSettings(roles:string[]){return roles.includes('OPS_ADMIN')}
export function credentialLabel(status:Pick<ModelConfigurationStatus,'configured'|'keySuffix'>){return status.configured&&status.keySuffix?`•••• ${status.keySuffix}`:'尚未配置'}
export function clearSecretAfterSuccess<T extends {apiKey:string;showSecret:boolean}>(state:T):T{return {...state,apiKey:'',showSecret:false}}
export function emptyBalanceState():BalanceState{return {status:'idle',rows:[],message:''}}
export function balanceRows(reply:Pick<ModelBalanceReply,'isAvailable'|'balances'>):BalanceRow[]{
  if(reply.isAvailable!==true)return []
  return (reply.balances||[]).map(row=>({currency:row.currency,total:row.totalBalance,granted:row.grantedBalance,toppedUp:row.toppedUpBalance}))
}
export function balanceTransition(_state:BalanceState,event:BalanceEvent):BalanceState{
  if(event.type==='loading')return {status:'loading',rows:[],message:''}
  if(event.type==='error')return {status:'error',rows:[],message:event.message}
  if(event.type==='success')return {status:'ready',rows:balanceRows(event.reply),message:event.reply.isAvailable===false?'服务商当前未开放余额查询':''}
  return emptyBalanceState()
}
export function formatTokens(value:number|null|undefined){return value==null?'未知':value.toLocaleString('en-US')}
export function usageSource(request:Pick<ModelUsageRequest,'purpose'|'agentRunId'|'incidentId'>):{label:string;to:string|null}{
  if(request.purpose==='DIAGNOSIS'&&request.agentRunId)return {label:`诊断任务 ${request.agentRunId}`,to:`/agent-runs/${request.agentRunId}`}
  if(request.purpose==='CONNECTION_TEST')return {label:'连接测试',to:null}
  if(request.purpose==='BALANCE_QUERY')return {label:'余额查询',to:null}
  return request.incidentId?{label:`事件 ${request.incidentId}`,to:`/incidents/${request.incidentId}`}:{label:'系统模型请求',to:null}
}
export const purposeLabels:Record<string,string>={DIAGNOSIS:'AI 故障诊断',CONNECTION_TEST:'连接测试',BALANCE_QUERY:'余额查询'}
export const outcomeLabels:Record<string,string>={SUCCEEDED:'成功',FAILED:'失败',UNKNOWN:'结果未知',STARTED:'进行中'}
