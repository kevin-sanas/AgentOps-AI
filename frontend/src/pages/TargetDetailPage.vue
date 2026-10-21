<script setup lang="ts">
import { computed,onMounted,ref } from 'vue'
import { useRoute } from 'vue-router'
import { api,hasRole } from '../api'
import { useLoad } from '../composables'
import type { Target } from '../types'
import PageState from '../components/PageState.vue'
import AppIcon from '../components/AppIcon.vue'
import ChecksPanel from '../components/ChecksPanel.vue'
import HistoryPanel from '../components/HistoryPanel.vue'
import NetworkPanel from '../components/NetworkPanel.vue'
import AccessPanel from '../components/AccessPanel.vue'
import KumaPanel from '../components/KumaPanel.vue'
const route=useRoute(),id=String(route.params.id),{data,loading,error,load}=useLoad(()=>api<Target>('/targets/'+id))
const tabs=computed(()=>[{id:'checks',label:'检查配置'},{id:'history',label:'检查历史'},...(hasRole('OPS_ADMIN')?[{id:'network',label:'网络策略'},{id:'access',label:'访问权限'},{id:'kuma',label:'Kuma 推送'}]:[])])
const tab=computed(()=>tabs.value.some(t=>t.id===route.query.tab)?String(route.query.tab):'checks')
const refreshVersion=ref(0)
function refresh(){refreshVersion.value++;void load()}
onMounted(load)
</script>
<template><RouterLink to="/targets" class="back-link"><AppIcon name="arrow" :size="16" />服务目标</RouterLink><PageState :loading="loading&&!data" :error="error" @retry="load"><template v-if="data"><div class="page-heading"><div><div class="title-line"><h1>{{ data.name }}</h1><span class="env-tag">{{ data.environment }}</span></div><p><span class="mono">{{ data.code }}</span> · {{ data.ownerTeam||'未指定负责人' }}</p></div><button class="btn" :disabled="loading" @click="refresh"><AppIcon name="refresh" :size="16" />刷新</button></div><div class="target-address"><AppIcon name="target" :size="18" /><code>{{ data.baseUrl }}</code><span>{{ data.networkPolicy==='PUBLIC_ONLY'?'仅公网访问':'已批准私网范围' }}</span></div><nav class="tab-bar" aria-label="目标详情"><RouterLink v-for="t in tabs" :key="t.id" :to="{query:{tab:t.id}}" :aria-current="tab===t.id?'page':undefined" :class="{active:tab===t.id}">{{ t.label }}</RouterLink></nav><section :key="refreshVersion" class="panel"><ChecksPanel v-if="tab==='checks'" :target-id="id" /><HistoryPanel v-else-if="tab==='history'" :target-id="id" :initial-check="typeof route.query.checkId==='string'?route.query.checkId:undefined" /><NetworkPanel v-else-if="tab==='network'" :target-id="id" @updated="load" /><AccessPanel v-else-if="tab==='access'" :target-id="id" /><KumaPanel v-else-if="tab==='kuma'" :target-id="id" /></section></template></PageState></template>
