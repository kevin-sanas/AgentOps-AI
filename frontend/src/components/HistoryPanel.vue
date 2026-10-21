<script setup lang="ts">
import { onMounted,onUnmounted,ref,watch } from 'vue'
import { api } from '../api'
import { useLoad,time,shortId } from '../composables'
import type { Page,Definition,CheckRun } from '../types'
import StatusBadge from './StatusBadge.vue'
import PageState from './PageState.vue'
import PaginationBar from './PaginationBar.vue'
const props=defineProps<{targetId:string;initialCheck?:string}>(),page=ref(0),checkId=ref(props.initialCheck||'')
const definitions=ref<Definition[]>([])
const {data,loading,error,load}=useLoad(()=>api<Page<CheckRun>>(`/targets/${props.targetId}/checks/history?page=${page.value}&size=20${checkId.value?'&checkId='+encodeURIComponent(checkId.value):''}`))
onMounted(async()=>{void load();try{definitions.value=(await api<Page<Definition>>(`/targets/${props.targetId}/checks?size=100`)).items}catch{/* History errors remain independently visible. */}})
watch(checkId,()=>{page.value=0;void load()});const timer=setInterval(load,15000);onUnmounted(()=>clearInterval(timer))
</script>
<template><div class="panel-heading"><div><h2>检查历史</h2><p>只保留结果摘要，响应正文与认证信息不会保存。</p></div><div class="actions"><label class="compact-label"><span class="sr-only">按检查筛选</span><select v-model="checkId"><option value="">全部检查</option><option v-for="d in definitions" :key="d.id" :value="d.id">{{ d.method }} · {{ shortId(d.id) }}</option><option v-if="checkId&&!definitions.some(d=>d.id===checkId)" :value="checkId">{{ shortId(checkId) }}</option></select></label><button class="btn" :disabled="loading" @click="load">刷新</button></div></div><PageState :loading="loading&&!data" :error="error" :empty="!!data&&!data.items.length" title="等待第一次检查" description="启用健康检查后，结果会出现在这里。调度配额或上一次租约也可能影响执行时间。" @retry="load"><template v-if="data"><div class="table-scroll"><table><thead><tr><th>完成时间</th><th>结果</th><th>HTTP 状态</th><th>耗时</th><th>检查与版本</th><th>错误类别</th></tr></thead><tbody><tr v-for="r in data.items" :key="r.id"><td class="timestamp">{{ time(r.completedAt) }}</td><td><StatusBadge :value="r.outcome" /></td><td class="mono">{{ r.statusCode??'—' }}</td><td class="mono">{{ r.latencyMs }} ms</td><td class="mono" :title="r.checkDefinitionId">{{ shortId(r.checkDefinitionId) }}<small class="cell-note">检查 v{{ r.configVersion }} · 目标 v{{ r.targetConfigVersion }}</small></td><td class="mono error-code">{{ r.errorCode||'—' }}<small class="cell-note" :title="r.id">{{ shortId(r.id) }}</small></td></tr></tbody></table></div><PaginationBar :page="page" :size="20" :total="data.total" :loading="loading" @change="p=>{page=p;load()}" /></template></PageState></template>
