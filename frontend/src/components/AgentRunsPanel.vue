<script setup lang="ts">
import { onMounted,onUnmounted,ref } from 'vue'
import { useRouter } from 'vue-router'
import { api,errorText,hasRole } from '../api'
import { useLoad,time } from '../composables'
import type { AgentRun,Page } from '../types'
import PageState from './PageState.vue'
import PaginationBar from './PaginationBar.vue'
import { agentStatus } from '../diagnosis'
const props=defineProps<{incidentId:string;canOperate:boolean;hideStart?:boolean}>(),router=useRouter(),page=ref(0)
const {data,loading,error,load}=useLoad(()=>api<Page<AgentRun>>(`/incidents/${props.incidentId}/agent-runs?page=${page.value}&size=10`))
const busy=ref(false),saveError=ref(''),key=ref(crypto.randomUUID())
async function start(){busy.value=true;saveError.value='';try{const r=await api<{runId:string}>(`/incidents/${props.incidentId}/agent-runs`,{method:'POST',body:{},key:key.value});key.value=crypto.randomUUID();await router.push('/agent-runs/'+r.runId)}catch(e){saveError.value=errorText(e)}finally{busy.value=false}}
onMounted(load);const timer=setInterval(()=>{if(!document.hidden&&!loading.value)void load()},5000);onUnmounted(()=>clearInterval(timer))
</script>
<template><section v-if="canOperate||hasRole('ON_CALL')||hasRole('AUDITOR')" class="panel diagnosis-panel"><div class="panel-heading"><div><h2>只读诊断</h2><p class="muted">整理最近 30 分钟的检查与已发布手册；结论需人工审阅。</p></div><button v-if="canOperate&&!hideStart" class="btn primary" :disabled="busy" @click="start">{{ busy?'正在提交…':'发起诊断' }}</button></div><div v-if="saveError" class="error-message" role="alert">{{ saveError }}</div><PageState :loading="loading&&!data" :error="error" :empty="!!data&&!data.items.length" title="尚未发起诊断" description="值班人员可在获得目标操作授权后发起。" @retry="load"><ul class="diagnosis-list"><li v-for="run in data?.items" :key="run.id"><RouterLink :to="'/agent-runs/'+run.id">{{ agentStatus[run.status]||run.status }}</RouterLink><span>{{ time(run.createdAt) }}</span><span class="muted">{{ run.errorCode?'等待处理 · '+run.errorCode:run.modelName+' · 只读' }}</span></li></ul><PaginationBar v-if="data" :page="page" :size="10" :total="data.total" :loading="loading" @change="p=>{page=p;load()}" /></PageState></section></template>
<style scoped>.diagnosis-panel{margin:24px 0}.panel-heading p{margin:8px 0 0}.diagnosis-list{list-style:none;margin:0;padding:0}.diagnosis-list li{display:flex;flex-wrap:wrap;gap:12px;padding:16px 24px;border-top:1px solid var(--border)}.diagnosis-list li a{font-weight:600}.diagnosis-list li span:last-child{margin-left:auto}@media(max-width:600px){.panel-heading{align-items:flex-start;flex-direction:column;gap:12px}.diagnosis-list li span:last-child{margin-left:0}}</style>
