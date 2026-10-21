<script setup lang="ts">
import { onMounted,onUnmounted,ref,watch } from 'vue'
import { api,ApiProblem,hasRole } from '../api'
import { useLoad,time,shortId } from '../composables'
import type { Page,Incident,PendingAlert } from '../types'
import StatusBadge from '../components/StatusBadge.vue'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import AppIcon from '../components/AppIcon.vue'
import AgentDeliveryPanel from '../components/AgentDeliveryPanel.vue'
const page=ref(0),status=ref(''),pendingPage=ref(0),updatedAt=ref<string|null>(null)
const {data,loading,error,load}=useLoad(()=>api<Page<Incident>>(`/incidents?page=${page.value}&size=20${status.value?'&status='+status.value:''}`))
const {data:pendingData,loading:pendingLoading,error:pendingError,load:loadPending}=useLoad(async()=>{
  try{return await api<Page<PendingAlert>>(`/incidents/pending-alerts?page=${pendingPage.value}&size=10`)}
  catch(e){if(e instanceof ApiProblem&&[400,404].includes(e.status))throw new Error('异常进度接口尚未就绪，请确认后端已更新并重启。');throw e}
})
async function refresh(){await Promise.all([load(),loadPending()]);if(!error.value&&!pendingError.value)updatedAt.value=new Date().toISOString()}
function poll(){if(!document.hidden&&!loading.value&&!pendingLoading.value)void refresh()}
watch(status,()=>{page.value=0;void load()})
watch(pendingData,value=>{if(value&&pendingPage.value>0&&pendingPage.value*10>=value.total){pendingPage.value=Math.max(0,Math.ceil(value.total/10)-1);void loadPending()}})
onMounted(()=>{void refresh();window.addEventListener('focus',poll);document.addEventListener('visibilitychange',poll)})
const timer=setInterval(poll,5000)
onUnmounted(()=>{clearInterval(timer);window.removeEventListener('focus',poll);document.removeEventListener('visibilitychange',poll)})
const statuses=[['OPEN','待确认'],['ACKNOWLEDGED','已确认'],['INVESTIGATING','处理中'],['MONITORING','恢复观察'],['RESOLVED','已解决'],['CLOSED','已关闭']]
</script>
<template>
  <div class="page-heading"><div><h1>事故队列 <span v-if="data" class="heading-count">{{ data.total }}</span></h1><p>监控异常先累计失败次数，达到阈值后生成事故。</p></div><button class="btn" :disabled="loading||pendingLoading" @click="refresh"><AppIcon name="refresh" :size="16" />刷新</button></div>
  <section v-if="pendingError||pendingData?.total" class="panel pending-alerts" aria-label="未达事故阈值的监控异常">
    <div class="panel-heading"><div><h2>监控异常 <span v-if="pendingData" class="heading-count">{{ pendingData.total }}</span></h2><p>尚未达到事故阈值；恢复正常会清零连续失败次数。</p></div><span class="muted">等待下一次检查</span></div>
    <PageState :error="pendingError" @retry="loadPending"><template v-if="pendingData">
      <div class="table-scroll" tabindex="0" aria-label="监控异常列表，可横向滚动"><table>
        <thead><tr><th>服务目标</th><th>连续失败 / 阈值</th><th>最近结果</th><th>最近检查</th><th>下次计划</th><th>操作</th></tr></thead>
        <tbody><tr v-for="a in pendingData.items" :key="a.checkId">
          <td><RouterLink :to="'/targets/'+a.targetId" class="row-title">{{ a.targetName }}</RouterLink><small class="cell-note mono" :title="a.checkId">{{ shortId(a.checkId) }}</small></td>
          <td><strong class="pending-progress">{{ a.failureCount }} / {{ a.failThreshold }}</strong><small class="cell-note">再连续失败 {{ a.failThreshold-a.failureCount }} 次生成事故</small></td>
          <td><StatusBadge value="DOWN" /><small class="cell-note mono">{{ a.statusCode!==null?'HTTP '+a.statusCode:a.errorCode }}</small></td>
          <td class="timestamp">{{ time(a.lastCheckedAt) }}</td><td class="timestamp">{{ time(a.nextRunAt) }}</td>
          <td><RouterLink :to="{path:'/targets/'+a.targetId,query:{tab:'history',checkId:a.checkId}}" class="text-link">检查历史<AppIcon name="chevron" :size="15" /></RouterLink></td>
        </tr></tbody></table></div>
      <PaginationBar :page="pendingPage" :size="10" :total="pendingData.total" :loading="pendingLoading" @change="p=>{pendingPage=p;loadPending()}" />
    </template></PageState>
  </section>
  <section class="panel">
    <div class="table-toolbar"><label class="compact-label">事故状态<select v-model="status"><option value="">全部状态</option><option v-for="s in statuses" :key="s[0]" :value="s[0]">{{ s[1] }}</option></select></label><span class="muted">每 5 秒刷新<span v-if="updatedAt"> · 更新于 {{ time(updatedAt) }}</span></span></div>
    <PageState :loading="loading&&!data" :error="error" :empty="!!data&&!data.items.length" title="当前范围没有事故" description="异常达到连续失败阈值后会自动进入队列；未达阈值的异常显示在上方，也可切换状态查看历史。" @retry="load">
      <template v-if="data"><div class="table-scroll"><table><thead><tr><th>事故 / 目标</th><th>等级</th><th>状态</th><th>首次发现</th><th>最近失败</th><th><span class="sr-only">操作</span></th></tr></thead><tbody><tr v-for="i in data.items" :key="i.id"><td><RouterLink :to="'/incidents/'+i.id" class="row-title">{{ i.targetName }}</RouterLink><small class="cell-note incident-summary">{{ i.summary }}</small><small class="cell-note mono">{{ shortId(i.id) }}</small></td><td><span class="severity">{{ i.severity }}</span></td><td><StatusBadge :value="i.status" /></td><td class="timestamp">{{ time(i.openedAt) }}</td><td class="timestamp">{{ time(i.lastFailureAt) }}</td><td><RouterLink :to="'/incidents/'+i.id" class="text-link">处理详情<AppIcon name="chevron" :size="15" /></RouterLink></td></tr></tbody></table></div><PaginationBar :page="page" :size="20" :total="data.total" :loading="loading" @change="p=>{page=p;load()}" /></template>
    </PageState>
  </section>
  <AgentDeliveryPanel v-if="hasRole('OPS_ADMIN')" />
</template>
<style scoped>
.pending-alerts{margin-bottom:24px}
.table-scroll{position:relative}
.pending-progress{color:var(--warning);font-variant-numeric:tabular-nums;white-space:nowrap}
</style>
