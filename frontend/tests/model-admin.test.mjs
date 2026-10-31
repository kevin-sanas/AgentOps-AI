import test from 'node:test'
import assert from 'node:assert/strict'
import {
  balanceRows,
  balanceTransition,
  canViewModelSettings,
  clearSecretAfterSuccess,
  credentialLabel,
  emptyBalanceState,
  formatTokens,
  MODEL_SETTINGS_META,
  MODEL_SETTINGS_PATH,
  tokenTrendSeries,
  tokenTrendLabel,
  usageSource,
} from '../src/model-admin.ts'

test('model settings are admin-only and saved credentials are represented only by suffix',()=>{
  assert.equal(MODEL_SETTINGS_PATH,'/admin/model')
  assert.deepEqual(MODEL_SETTINGS_META,{title:'模型配置与用量',admin:true})
  assert.equal(canViewModelSettings(['OPS_ADMIN']),true)
  assert.equal(canViewModelSettings(['ON_CALL','OBSERVER']),false)
  assert.equal(credentialLabel({configured:true,keySuffix:'7xQp'}),'•••• 7xQp')
  assert.equal(credentialLabel({configured:false,keySuffix:null}),'尚未配置')
})

test('successful save clears the password field without changing unrelated form state',()=>{
  assert.deepEqual(clearSecretAfterSuccess({apiKey:'candidate-secret',showSecret:true}),{apiKey:'',showSecret:false})
})

test('a balance refresh clears stale rows before loading and keeps them cleared on failure',()=>{
  const current={status:'ready',rows:[{currency:'CNY',total:'18.25',granted:'0.00',toppedUp:'18.25'}],message:'上次查询成功'}
  const loading=balanceTransition(current,{type:'loading'})
  assert.deepEqual(loading,{status:'loading',rows:[],message:''})
  assert.deepEqual(balanceTransition(loading,{type:'error',message:'查询失败'}),{status:'error',rows:[],message:'查询失败'})
})

test('token formatting distinguishes an unknown measurement from measured zero',()=>{
  assert.equal(formatTokens(null),'未知')
  assert.equal(formatTokens(undefined),'未知')
  assert.equal(formatTokens(0),'0')
  assert.equal(formatTokens(12345),'12,345')
})

test('balance keeps every provider currency instead of merging amounts',()=>{
  const rows=balanceRows({isAvailable:true,balances:[
    {currency:'CNY',totalBalance:'18.25',grantedBalance:'0.00',toppedUpBalance:'18.25'},
    {currency:'USD',totalBalance:'2.50',grantedBalance:'1.00',toppedUpBalance:'1.50'},
  ]})
  assert.deepEqual(rows,[
    {currency:'CNY',total:'18.25',granted:'0.00',toppedUp:'18.25'},
    {currency:'USD',total:'2.50',granted:'1.00',toppedUp:'1.50'},
  ])
  assert.deepEqual(emptyBalanceState(),{status:'idle',rows:[],message:''})
})

test('usage rows link diagnosis calls to the run and administrative calls to their source',()=>{
  assert.deepEqual(usageSource({purpose:'DIAGNOSIS',agentRunId:'run-1',incidentId:'incident-1'}),{label:'诊断任务 run-1',to:'/agent-runs/run-1'})
  assert.deepEqual(usageSource({purpose:'CONNECTION_TEST',agentRunId:null,incidentId:null}),{label:'连接测试',to:null})
  assert.deepEqual(usageSource({purpose:'BALANCE_QUERY',agentRunId:null,incidentId:null}),{label:'余额查询',to:null})
})

test('token trend keeps unknown provider usage as a chart gap instead of zero',()=>{
  const points=tokenTrendSeries([
    {startedAt:'2026-01-01T01:00:00Z',endedAt:'2026-01-01T02:00:00Z',requests:0,requestsWithUsage:0,promptTokens:0,completionTokens:0,totalTokens:0},
    {startedAt:'2026-01-01T02:00:00Z',endedAt:'2026-01-01T03:00:00Z',requests:1,requestsWithUsage:0,promptTokens:null,completionTokens:null,totalTokens:null},
    {startedAt:'2026-01-01T03:00:00Z',endedAt:'2026-01-01T04:00:00Z',requests:1,requestsWithUsage:1,promptTokens:120,completionTokens:30,totalTokens:150},
  ])
  assert.deepEqual(points.prompt,[[Date.parse('2026-01-01T01:00:00Z'),0],[Date.parse('2026-01-01T02:00:00Z'),null],[Date.parse('2026-01-01T03:00:00Z'),120]])
  assert.deepEqual(points.completion,[[Date.parse('2026-01-01T01:00:00Z'),0],[Date.parse('2026-01-01T02:00:00Z'),null],[Date.parse('2026-01-01T03:00:00Z'),30]])
  assert.deepEqual(points.total,[[Date.parse('2026-01-01T01:00:00Z'),0],[Date.parse('2026-01-01T02:00:00Z'),null],[Date.parse('2026-01-01T03:00:00Z'),150]])
  assert.equal(points.unknownBuckets,1)
})

test('token trend accessibility label does not describe unknown usage as zero',()=>{
  assert.equal(tokenTrendLabel({requests:2,requestsWithUsage:0,totalTokens:0}),'Token 用量趋势：2 次请求均无 Token 回执')
  assert.equal(tokenTrendLabel({requests:2,requestsWithUsage:1,totalTokens:150}),'Token 用量趋势：150 Token，1 次请求含用量回执')
})
