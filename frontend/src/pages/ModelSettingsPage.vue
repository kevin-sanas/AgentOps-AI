<script setup lang="ts">
import { computed,onMounted,reactive,ref,watch } from 'vue'
import { api,errorText } from '../api'
import { time } from '../composables'
import { balanceTransition,credentialLabel,emptyBalanceState,formatTokens,outcomeLabels,purposeLabels,tokenTrendLabel,usageSource } from '../model-admin'
import type { ModelBalanceReply,ModelConfigurationStatus,ModelUsageRequest,ModelUsageSummary,ModelUsageTrendBucket,Page } from '../types'
import AppIcon from '../components/AppIcon.vue'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import TokenTrendChart from '../components/TokenTrendChart.vue'

const status=ref<ModelConfigurationStatus|null>(null),statusLoading=ref(true),statusError=ref('')
const form=reactive({apiKey:'',showSecret:false}),saving=ref(false),testing=ref(false),deleting=ref(false),confirmDelete=ref(false)
const actionError=ref(''),actionSuccess=ref('')
const balance=ref(emptyBalanceState())
const summary=ref<ModelUsageSummary|null>(null),trend=ref<ModelUsageTrendBucket[]|null>(null),requests=ref<Page<ModelUsageRequest>|null>(null),usageLoading=ref(false),usageError=ref('')
const range=ref('24'),purpose=ref(''),page=ref(0),size=20

const configuredLabel=computed(()=>status.value?credentialLabel(status.value):'读取中…')
const canTest=computed(()=>status.value?.configured||form.apiKey.trim().length>=8)
const rangeQuery=computed(()=>{const to=new Date(),from=new Date(to.getTime()-Number(range.value)*60*60*1000);return `from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}`})
const trendAriaLabel=computed(()=>tokenTrendLabel(summary.value))

async function loadStatus(){statusLoading.value=true;statusError.value='';try{status.value=await api<ModelConfigurationStatus>('/admin/model-config')}catch(e){status.value=null;statusError.value=errorText(e)}finally{statusLoading.value=false}}
async function loadUsage(){usageLoading.value=true;usageError.value='';try{const query=rangeQuery.value+(purpose.value?`&purpose=${purpose.value}`:'');[summary.value,trend.value,requests.value]=await Promise.all([api<ModelUsageSummary>(`/admin/model-usage/summary?${query}`),api<ModelUsageTrendBucket[]>(`/admin/model-usage/trend?${query}`),api<Page<ModelUsageRequest>>(`/admin/model-usage/requests?${query}&page=${page.value}&size=${size}`)])}catch(e){summary.value=null;trend.value=null;requests.value=null;usageError.value=errorText(e)}finally{usageLoading.value=false}}
function resetMessages(){actionError.value='';actionSuccess.value=''}
async function save(){if(!status.value)return;resetMessages();saving.value=true;try{status.value=await api<ModelConfigurationStatus>('/admin/model-config',{method:'PUT',body:{apiKey:form.apiKey,version:status.value.version}});form.apiKey='';form.showSecret=false;balance.value=emptyBalanceState();actionSuccess.value='模型密钥已加密保存。请执行连接测试，确认当前密钥与网络可用。'}catch(e){actionError.value=errorText(e)}finally{saving.value=false}}
async function testConnection(){resetMessages();testing.value=true;try{const body=form.apiKey.trim()?{apiKey:form.apiKey}:{};const reply=await api<ModelBalanceReply>('/admin/model-config/test',{method:'POST',body});actionSuccess.value=`连接成功：${reply.model}，耗时 ${reply.elapsedMs} ms。此测试不生成内容，不消耗推理 Token。`;if(!form.apiKey.trim())await loadStatus();await loadUsage()}catch(e){actionError.value=errorText(e);await loadUsage()}finally{testing.value=false}}
async function refreshBalance(){balance.value=balanceTransition(balance.value,{type:'loading'});try{const reply=await api<ModelBalanceReply>('/admin/model-config/balance');balance.value=balanceTransition(balance.value,{type:'success',reply});await loadUsage()}catch(e){balance.value=balanceTransition(balance.value,{type:'error',message:errorText(e)});await loadUsage()}}
async function remove(){if(!status.value)return;resetMessages();deleting.value=true;try{await api<void>('/admin/model-config',{method:'DELETE',body:{version:status.value.version}});form.apiKey='';form.showSecret=false;confirmDelete.value=false;balance.value=emptyBalanceState();actionSuccess.value='模型配置已删除；之后新建的诊断任务将使用 mock 模式。';await Promise.all([loadStatus(),loadUsage()])}catch(e){actionError.value=errorText(e)}finally{deleting.value=false}}
function source(row:ModelUsageRequest){return usageSource(row)}
function statusClass(outcome:string){return outcome==='SUCCEEDED'?'good':outcome==='FAILED'?'danger':outcome==='UNKNOWN'?'warning':'neutral'}
watch([range,purpose],()=>{page.value=0;void loadUsage()})
onMounted(()=>{void Promise.all([loadStatus(),loadUsage()])})
</script>

<template><div class="page-heading model-heading"><div><p class="eyebrow">MODEL OPERATIONS</p><h1>模型配置与用量</h1><p>在页面内管理 Dawnwatch 的 DeepSeek 连接，并审计每一次模型相关请求。</p></div><span class="status" :class="status?.configured?'good':'neutral'"><span class="status-dot"></span>{{ status?.configured?'已配置':'未配置' }}</span></div>
<div v-if="actionSuccess" class="success-message" role="status">{{ actionSuccess }}</div><div v-if="actionError" class="error-message" role="alert">{{ actionError }}</div>
<section class="model-config-grid">
 <article class="panel model-config-card"><div class="panel-heading"><div><h2>DeepSeek 凭据</h2><p>密钥仅加密保存于后端；浏览器之后只能看到末四位。</p></div><AppIcon name="model" /></div><div class="panel-pad">
  <PageState :loading="statusLoading" :error="statusError" @retry="loadStatus"><template v-if="status"><dl class="model-facts"><div><dt>提供商 / 模型</dt><dd>{{ status.provider }} / {{ status.model }}</dd></div><div><dt>保存的密钥</dt><dd class="mono">{{ configuredLabel }}</dd></div><div><dt>最后更新</dt><dd>{{ time(status.updatedAt) }}</dd></div><div><dt>最近连接测试</dt><dd>{{ status.lastTestOutcome?`${outcomeLabels[status.lastTestOutcome]||status.lastTestOutcome} · ${time(status.lastTestAt)}`:'尚未测试' }}</dd></div></dl>
  <form class="model-secret-form" @submit.prevent="save"><label>API Key<span class="secret-input"><input v-model="form.apiKey" :type="form.showSecret?'text':'password'" minlength="8" maxlength="512" required autocomplete="new-password" placeholder="输入新的 DeepSeek API Key"><button type="button" class="text-button" :aria-label="form.showSecret?'隐藏 API Key':'显示 API Key'" @click="form.showSecret=!form.showSecret">{{ form.showSecret?'隐藏':'显示' }}</button></span><small>保存后输入框立即清空，页面与接口均不回显完整密钥。</small></label><div class="form-actions model-actions"><button type="button" class="btn" :disabled="testing||!canTest" @click="testConnection">{{ testing?'测试中…':'测试连接' }}</button><button class="btn primary" :disabled="saving||form.apiKey.trim().length<8">{{ saving?'保存中…':'保存密钥' }}</button></div></form>
  <div v-if="status.configured" class="danger-zone"><template v-if="!confirmDelete"><button class="text-button danger" @click="confirmDelete=true">删除模型配置</button></template><template v-else><p>删除后只影响新诊断；历史用量记录会保留。</p><div class="row-actions"><button class="btn small" @click="confirmDelete=false">取消</button><button class="btn small danger" :disabled="deleting" @click="remove">{{ deleting?'删除中…':'确认删除' }}</button></div></template></div>
  </template></PageState>
 </div></article>
 <article class="panel balance-card"><div class="panel-heading"><div><h2>账户余额</h2><p>由 DeepSeek 余额接口实时返回，不作为系统计费账单。</p></div><button class="btn small" :disabled="balance.status==='loading'||!status?.configured" @click="refreshBalance"><AppIcon name="refresh" :size="14" />{{ balance.status==='loading'?'查询中…':'查询余额' }}</button></div><div class="panel-pad balance-content">
  <p v-if="!status?.configured" class="muted">保存密钥后可查询余额。</p><p v-else-if="balance.status==='idle'" class="muted">尚未查询。本页不会把历史结果当作当前余额。</p><div v-else-if="balance.status==='error'" class="error-message" role="alert">{{ balance.message }}</div><p v-else-if="balance.status==='loading'" class="muted" role="status">正在连接服务商查询当前余额…</p><p v-else-if="balance.message" class="notice">{{ balance.message }}</p>
  <div v-if="balance.status==='ready'&&balance.rows.length" class="balance-list"><div v-for="row in balance.rows" :key="row.currency"><span>{{ row.currency }}</span><strong>{{ row.total }}</strong><small>赠送 {{ row.granted }} · 充值 {{ row.toppedUp }}</small></div></div><p v-else-if="balance.status==='ready'&&!balance.message" class="muted">服务商未返回余额币种。</p>
 </div></article>
</section>
<section class="panel token-trend-panel"><div class="panel-heading"><div><h2>Token 用量趋势</h2><p>按调用时间聚合服务商真实回执；无回执的时间桶保留缺口。</p></div><div class="usage-filters"><label><span class="sr-only">时间范围</span><select v-model="range"><option value="24">最近 24 小时</option><option value="168">最近 7 天</option><option value="720">最近 30 天</option></select></label><label><span class="sr-only">调用来源</span><select v-model="purpose"><option value="">全部调用</option><option value="DIAGNOSIS">AI 故障诊断</option><option value="CONNECTION_TEST">连接测试</option><option value="BALANCE_QUERY">余额查询</option></select></label><button class="btn small" :disabled="usageLoading" @click="loadUsage"><AppIcon name="refresh" :size="14" />刷新</button></div></div>
 <PageState :loading="usageLoading&&!trend" :error="usageError" @retry="loadUsage"><TokenTrendChart v-if="trend" :buckets="trend" :label="trendAriaLabel" /></PageState>
 <div class="panel-footnote">{{ trend?.filter(row=>row.requests>0&&row.requestsWithUsage===0).length||0 }} 个时间桶含请求但无 Token 回执 · 图中显示为缺口而非 0</div>
</section>
<section class="panel usage-panel"><div class="panel-heading"><div><h2>模型请求审计</h2><p>沿用上方时间和来源筛选；Token 未知不会显示为 0。</p></div></div>
 <div v-if="summary" class="usage-summary"><div><span>请求次数</span><strong>{{ summary.requests.toLocaleString() }}</strong><small>成功 {{ summary.succeeded }} · 失败 {{ summary.failed }} · 未知 {{ summary.unknown }}</small></div><div><span>已用 Token</span><strong>{{ formatTokens(summary.totalTokens) }}</strong><small>输入 {{ formatTokens(summary.promptTokens) }} · 输出 {{ formatTokens(summary.completionTokens) }}</small></div><div><span>含用量回执</span><strong>{{ summary.requestsWithUsage.toLocaleString() }}</strong><small>其余请求的 Token 显示“未知”</small></div></div>
 <PageState :loading="usageLoading&&!requests" :error="usageError" :empty="!!requests&&!requests.items.length" title="所选范围内没有模型请求" @retry="loadUsage"><template v-if="requests"><div class="table-scroll"><table><thead><tr><th>时间 / 结果</th><th>系统调用位置</th><th>模型</th><th>Token</th><th>耗时 / 状态</th></tr></thead><tbody><tr v-for="row in requests.items" :key="row.id"><td><span class="status" :class="statusClass(row.outcome)"><span class="status-dot"></span>{{ outcomeLabels[row.outcome]||row.outcome }}</span><small class="cell-note">{{ time(row.startedAt) }}</small></td><td><RouterLink v-if="source(row).to" class="text-link" :to="source(row).to!">{{ source(row).label }}<AppIcon name="chevron" :size="13" /></RouterLink><span v-else>{{ source(row).label }}</span><small class="cell-note">{{ purposeLabels[row.purpose]||row.purpose }}</small></td><td><strong>{{ row.model }}</strong><small class="cell-note">{{ row.provider }}</small></td><td><strong>{{ formatTokens(row.totalTokens) }}</strong><small class="cell-note">输入 {{ formatTokens(row.promptTokens) }} · 输出 {{ formatTokens(row.completionTokens) }}</small></td><td><span>{{ row.elapsedMs==null?'未知':`${row.elapsedMs} ms` }}</span><small class="cell-note">{{ row.errorCode||row.httpStatus||'—' }}</small></td></tr></tbody></table></div><PaginationBar :page="page" :size="size" :total="requests.total" :loading="usageLoading" @change="p=>{page=p;loadUsage()}" /></template></PageState>
</section></template>
