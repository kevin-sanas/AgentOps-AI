<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {onBeforeRouteLeave} from 'vue-router'
import baseline from '../quality-baseline.json'
import {exportReviews,guardLabels,importReviews,parseWorkbench,qualityReviewed,qualitySummary} from '../quality-center'
import type {Workbench} from '../quality-center'
import Select from '../components/ui/Select.vue'
import PaginationBar from '../components/PaginationBar.vue'
import AppIcon from '../components/AppIcon.vue'

// This bundled synthetic fixture is generated and validated by the offline Python tool.
const bundle=ref(baseline as unknown as Workbench),selected=ref(bundle.value.cases[0].id)
const query=ref(''),filter=ref('all'),sort=ref('default'),page=ref(0),busy=ref(false),error=ref(''),notice=ref(''),dirty=ref(false)
const current=computed(()=>bundle.value.cases.find(c=>c.id===selected.value)!)
const summary=computed(()=>qualitySummary(bundle.value.cases))
const filtered=computed(()=>{
 const rows=bundle.value.cases.filter(c=>(c.id+' '+c.title).toLowerCase().includes(query.value.trim().toLowerCase())&&(filter.value==='all'||filter.value==='accepted'&&c.guard==='ACCEPTED'||filter.value==='failed'&&(c.guard==='REJECTED'||c.receipt?.status==='FAILED')||filter.value==='pending'&&!qualityReviewed(c)))
 return sort.value==='title'?rows.sort((a,b)=>a.title.localeCompare(b.title,'zh-CN')):rows
})
watch([query,filter,sort],()=>{page.value=0})
const rows=computed(()=>filtered.value.slice(page.value*10,page.value*10+10))
const facts=computed({get:()=>current.value.review.factsVsInferencesScore??'',set:(v:string|number)=>{current.value.review.factsVsInferencesScore=v===''?null:Number(v);changed()}})
const actionability=computed({get:()=>current.value.review.actionabilityScore??'',set:(v:string|number)=>{current.value.review.actionabilityScore=v===''?null:Number(v);changed()}})
const uncertainty=computed({get:()=>current.value.review.expressesUncertainty===null?'':String(current.value.review.expressesUncertainty),set:(v:string)=>{current.value.review.expressesUncertainty=v===''?null:v==='true';changed()}})
const unsafe=computed({get:()=>current.value.review.unsafeRecommendation===null?'':String(current.value.review.unsafeRecommendation),set:(v:string)=>{current.value.review.unsafeRecommendation=v===''?null:v==='true';changed()}})
const ratings=[{value:'' as const,label:'尚未评分'},...[1,2,3,4,5].map(n=>({value:n,label:n+' 分'}))]
const judgments=[{value:'',label:'尚未判断'},{value:'true',label:'是'},{value:'false',label:'否'}]
const requestLabel=computed(()=>current.value.receipt?`${current.value.receipt.requestAttempts??'未知'} 次请求 · ${current.value.receipt.status==='ACCEPTED'?'回执接受':current.value.receipt.status==='FAILED'?'回执失败':'回执未知'}`:'无请求回执')
function changed(){dirty.value=true;notice.value=''}
function download(){
 error.value=''
 try{
  const data=exportReviews(bundle.value.cases);importReviews(data,bundle.value.cases)
  const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)+'\n'],{type:'application/json'}))
  const a=document.createElement('a');a.href=url;a.download='dawnwatch-quality-reviews.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)
  dirty.value=false;notice.value='人评文件已导出。请保留该文件，下次导入后可继续审阅。'
 }catch(e){error.value=e instanceof Error?e.message:'导出失败，请检查人评字段。'}
}
async function read(event:Event,kind:'reviews'|'bundle'){
 const input=event.target as HTMLInputElement,file=input.files?.[0];if(!file)return
 if(dirty.value&&!window.confirm('当前人评尚未导出。导入会替换它，是否继续？')){input.value='';return}
 busy.value=true;error.value='';notice.value=''
 try{
  if(file.size>2*1024*1024)throw Error('文件超过 2 MiB，请选择评测工具导出的 JSON。')
  const value=JSON.parse((await file.text()).replace(/^\uFEFF/,''))
  if(kind==='reviews')bundle.value={...bundle.value,cases:importReviews(value,bundle.value.cases)}
  else{bundle.value=await parseWorkbench(value);selected.value=bundle.value.cases[0].id;page.value=0}
  dirty.value=false;notice.value='导入完成。文件来源未经认证，不作为真实模型质量通过的证明。'
 }catch(e){error.value=e instanceof SyntaxError?'JSON 无法解析，请选择完整的导出文件。':e instanceof Error?e.message:'导入失败。原人评已保留。'}
 finally{busy.value=false;input.value=''}
}
function leaving(event:BeforeUnloadEvent){if(dirty.value){event.preventDefault();event.returnValue=''}}
window.addEventListener('beforeunload',leaving)
onBeforeUnmount(()=>window.removeEventListener('beforeunload',leaving))
onBeforeRouteLeave(()=>!dirty.value||window.confirm('人评尚未导出，离开将丢失本页草稿。是否离开？'))
</script>

<template>
 <div class="page-heading"><div><h1>诊断质量中心</h1><p>逐条核对证据、报告与人工判断，让诊断质量有据可查。</p></div><button class="btn primary" :disabled="busy" @click="download"><AppIcon name="download" :size="16" />导出人评</button></div>
 <section class="panel quality-intro">
  <div><strong>离线审阅 · 30 个原创合成场景</strong><p class="muted">当前显示历史试评测材料，页面操作不会调用 DeepSeek。报告通过校验不代表人工认可；完整质量评测仍待完成。</p></div>
  <div class="quality-imports"><label class="btn" tabindex="0" @keydown.enter="($refs.reviewInput as HTMLInputElement).click()">导入人评<input ref="reviewInput" class="sr-only" type="file" accept=".json,application/json" :disabled="busy" @change="read($event,'reviews')" /></label><label class="btn" tabindex="0" @keydown.enter="($refs.bundleInput as HTMLInputElement).click()">导入评测包<input ref="bundleInput" class="sr-only" type="file" accept=".json,application/json" :disabled="busy" @change="read($event,'bundle')" /></label></div>
  <p class="form-hint">人评草稿保留在本页内存中，请导出后再离开。重新导入须匹配本批输入与报告。全局时间筛选不影响冻结的评测材料。</p>
 </section>
 <p v-if="busy" role="status" class="notice">正在校验文件…</p><p v-if="error" role="alert" class="error-message">{{ error }}</p><p v-if="notice" role="status" class="notice">{{ notice }}</p>
 <dl class="quality-summary panel" aria-label="离线评测覆盖情况"><div><dt>有回执的请求</dt><dd>{{ summary.requestAttempts }} 次</dd></div><div><dt>校验接受报告</dt><dd>{{ summary.accepted }} / 30</dd></div><div><dt>人工完成评分</dt><dd>{{ summary.qualityReviewed }} / 30</dd></div><div><dt>已知 Token</dt><dd>{{ summary.knownTokens.toLocaleString() }}<small>{{ summary.unknownUsage }} 条用量未知 · 仅回执统计</small></dd></div></dl>
 <div class="quality-layout">
  <section class="panel quality-directory" aria-label="评测场景目录">
   <div class="panel-heading"><h2>场景目录 <span class="heading-count">{{ filtered.length }}</span></h2></div>
   <div class="quality-filters"><label>搜索场景<input v-model="query" placeholder="名称或场景 ID" /></label><div class="quality-filter-row"><label>状态<Select aria-label="场景状态筛选" v-model="filter" :options="[{value:'all',label:'全部场景'},{value:'accepted',label:'校验接受'},{value:'failed',label:'失败 / 拒绝'},{value:'pending',label:'待人工完成'}]" /></label><label>排序<Select aria-label="场景排序" v-model="sort" :options="[{value:'default',label:'原始顺序'},{value:'title',label:'场景名称'}]" /></label></div></div>
   <p v-if="!rows.length" class="quality-empty">没有匹配场景，请调整搜索或状态。</p>
   <ul class="quality-case-list"><li v-for="c in rows" :key="c.id"><button :aria-current="selected===c.id?'true':undefined" :class="{'quality-selected':selected===c.id}" @click="selected=c.id"><strong>{{ c.title }}</strong><small>{{ c.id }}</small><span class="quality-case-state"><span :class="c.guard==='ACCEPTED'?'good':c.receipt?.status==='FAILED'?'danger':'muted'">{{ c.receipt?.status==='FAILED'?'回执失败':guardLabels[c.guard] }}</span><span>{{ qualityReviewed(c)?'已人评':'待人评' }}</span></span></button></li></ul>
   <PaginationBar :page="page" :size="10" :total="filtered.length" @change="p=>page=p" />
  </section>
  <section class="panel quality-detail" aria-label="场景审阅" :key="current.id">
   <div class="panel-heading"><div><h2>{{ current.title }}</h2><p class="mono muted">{{ current.id }}</p></div><span class="status" :class="current.guard==='ACCEPTED'?'good':'neutral'">{{ guardLabels[current.guard] }}</span></div>
   <div class="quality-detail-body">
    <dl class="quality-receipt"><div><dt>请求记录</dt><dd>{{ requestLabel }}</dd></div><div><dt>Token 用量</dt><dd>{{ current.receipt?.totalTokens?.toLocaleString()??'未知' }}</dd></div><div><dt>峰时未命中估算费用</dt><dd>{{ current.receipt?.estimatedCostUsd===null||!current.receipt?'未知':`US$ ${current.receipt.estimatedCostUsd}` }}<small>估算值，不是账户账单</small></dd></div></dl>
    <p v-if="current.receipt?.errorCode" class="error-message" role="status">{{ current.receipt.errorCode }} · 此场景调用失败或被护栏拒绝，不能作为成功诊断。拒绝原文未保留。</p>
    <details open><summary>预期观测与人工金标准</summary><p class="muted">以下预期是待复核草稿。请先核对证据，再确认相关性。</p><ul><li v-for="item in current.expectations.observations" :key="item">{{ item }}</li></ul><h3>允许的假设与边界</h3><ul><li v-for="item in current.expectations.allowedHypotheses" :key="item">{{ item }}</li></ul></details>
    <form @input="changed" @change="changed" @submit.prevent="download">
     <fieldset><legend>证据相关性复核</legend><label v-for="e in current.evidence" :key="e.id" class="quality-evidence"><input v-model="current.review.relevantEvidence" type="checkbox" :value="e.id" /><span><code>{{ e.id }}</code><span class="preserve-text">{{ e.text }}</span></span></label><p v-if="!current.evidence.length" class="muted">本场景没有检查或运行手册证据。</p><label>金标准复核人<input v-model="current.review.annotationReviewer" maxlength="120" placeholder="填写实际复核人" /></label><label>金标准状态<Select aria-label="金标准状态" v-model="current.review.annotationStatus" :options="[{value:'PENDING',label:'待复核'},{value:'APPROVED',label:'已人工确认'}]" /></label></fieldset>
     <div class="quality-report"><h3>诊断报告 · 只读</h3><template v-if="current.report"><p class="preserve-text">{{ current.report.summary }}</p><h3>诊断假设</h3><ol><li v-for="(h,i) in current.report.hypotheses" :key="i"><p>{{ h.text }}</p><small class="mono muted">{{ h.evidenceIds.join(' · ') }}</small></li></ol><h3>建议与风险</h3><ul><li v-for="(s,i) in current.report.nextSteps" :key="i"><strong>{{ s.risk }}</strong> · {{ s.text }}</li></ul><h3>未知项</h3><ul><li v-for="(u,i) in current.report.uncertainties" :key="i">{{ u }}</li></ul></template><p v-else class="quality-empty">{{ current.receipt?.status==='FAILED'?'该次未产生可审阅的接受报告。':'尚无可审阅报告。' }}完成模型评测后，可导入新的评测包。</p></div>
     <fieldset :disabled="!current.report"><legend>人工质量评分</legend><p class="form-hint">仅在阅读报告后填写。空项保持待办；“不安全建议”用于人工核对风险等级和审批边界。</p><label>评分人<input v-model="current.review.qualityReviewer" maxlength="120" placeholder="填写实际评分人" /></label><div class="quality-filter-row"><label>事实与推断区分<Select aria-label="事实与推断区分" v-model="facts" :options="ratings" /></label><label>建议可操作性<Select aria-label="建议可操作性" v-model="actionability" :options="ratings" /></label></div><div class="quality-filter-row"><label>是否表达不确定性<Select aria-label="是否表达不确定性" v-model="uncertainty" :options="judgments" /></label><label>是否存在不安全建议<Select aria-label="是否存在不安全建议" v-model="unsafe" :options="judgments" /></label></div></fieldset>
     <label>人工备注<textarea v-model="current.review.reviewerNotes" rows="3" maxlength="2000" placeholder="记录引用错误、风险判断、建议质量或拒绝原因；请勿填写密钥。" /></label>
     <div class="form-actions"><span class="muted" role="status">{{ dirty?'有尚未导出的人评草稿':'已载入离线材料' }}</span><button class="btn primary" type="submit">导出全部人评</button></div>
    </form>
    <details><summary>输入与报告标识</summary><p class="mono preserve-text">inputHash: {{ current.inputHash }}<br />reportHash: {{ current.reportHash??'无报告' }}</p><p class="form-hint">标识用于防止旧评分串用；导入文件本身不认证来源。可操作性与备注作为补充人评保存，不改变既有离线评分公式。</p></details>
   </div>
  </section>
 </div>
</template>

<style scoped>
.quality-intro{padding:24px;display:grid;gap:16px;margin-bottom:24px}.quality-intro p{max-width:80ch}.quality-imports{display:flex;flex-wrap:wrap;gap:8px}.quality-summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));margin-bottom:24px}.quality-summary>div{padding:20px 24px}.quality-summary>div+div{border-left:1px solid var(--border)}dt{color:var(--muted);font-size:12px}dd{margin:4px 0 0;font-size:20px;font-variant-numeric:tabular-nums}dd small{display:block;color:var(--muted);font-size:11px}.quality-layout{display:grid;grid-template-columns:minmax(260px,320px) minmax(0,1fr);gap:24px;align-items:start}.quality-directory{position:sticky;top:16px}.quality-filters{padding:16px;display:grid;gap:16px}.quality-filter-row{display:grid;grid-template-columns:1fr 1fr;gap:16px}.quality-case-list{padding:0;margin:0;list-style:none}.quality-case-list li+li{border-top:1px solid var(--border)}.quality-case-list button{background:transparent;border:0;width:100%;color:var(--ink);text-align:left;padding:16px 20px;display:grid;gap:4px}.quality-case-list button:hover,.quality-case-list .quality-selected{background:var(--surface)}.quality-case-list .quality-selected strong{color:var(--accent)}.quality-case-list small{color:var(--muted)}.quality-case-state{display:flex;justify-content:space-between;font-size:11px;color:var(--muted)}.quality-detail-body{padding:24px;display:grid;gap:24px;overflow-wrap:anywhere}.quality-receipt{display:grid;grid-template-columns:1fr 1fr 1fr;gap:16px;margin:0}.quality-receipt dd{font-size:13px}details summary{cursor:pointer;font-weight:600;margin-bottom:12px}details ul,.quality-report ul,.quality-report ol{padding-left:20px}h3{margin:16px 0 8px}.quality-detail form{display:grid;gap:24px}fieldset{border:0;margin:0;padding:0;display:grid;gap:16px}legend{font-weight:600;margin-bottom:16px}fieldset:disabled{opacity:.65}.quality-evidence{display:flex;align-items:flex-start;gap:12px}.quality-evidence input{flex-shrink:0;margin-top:4px}.quality-evidence>span{display:grid;gap:8px;min-width:0}.quality-evidence code{font-size:11px;color:var(--ai);overflow-wrap:anywhere}.quality-evidence .preserve-text{font-size:12px}.quality-report{padding:24px 0;border-block:1px solid var(--border)}.quality-report li{margin-block:12px}.quality-empty{padding:20px;color:var(--muted)}
@media(max-width:1100px){.quality-layout{grid-template-columns:260px minmax(0,1fr);gap:16px}.quality-detail-body{padding:16px}.quality-receipt{grid-template-columns:1fr}.quality-filter-row{grid-template-columns:1fr}}
@media(max-width:760px){.quality-layout{grid-template-columns:1fr}.quality-directory{position:static}.quality-summary{grid-template-columns:1fr 1fr}.quality-summary>div{padding:16px}.quality-summary>div+div{border:0}.quality-summary>div:nth-child(even){border-left:1px solid var(--border)}.quality-summary>div:nth-child(n+3){border-top:1px solid var(--border)}.quality-filter-row{grid-template-columns:1fr 1fr}.quality-intro{padding:16px}}
@media(max-width:440px){.quality-filter-row{grid-template-columns:1fr}.quality-summary dd{font-size:18px}}
</style>
