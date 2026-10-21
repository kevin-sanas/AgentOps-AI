<script setup lang="ts">
import { onMounted,onUnmounted,ref,watch } from 'vue'
import { api,hasRole } from '../api'
import { usePageBounds,useLoad,time } from '../composables'
import type { Target,Page } from '../types'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import AppIcon from '../components/AppIcon.vue'
import FilterToolbar from '../components/FilterToolbar.vue'
import LiveIndicator from '../components/LiveIndicator.vue'
const page=ref(0),query=ref(''),sort=ref('created_desc'),{data,loading,error,load}=useLoad(()=>api<Page<Target>>('/workspace/targets?'+new URLSearchParams({page:String(page.value),size:'20',q:query.value,sort:sort.value})))
usePageBounds(data,page,load)
let debounce:ReturnType<typeof setTimeout>|undefined
watch(query,()=>{clearTimeout(debounce);debounce=setTimeout(()=>{page.value=0;void load()},250)});watch(sort,()=>{page.value=0;void load()})
onMounted(load);const timer=setInterval(()=>{if(!document.hidden&&!loading.value)void load()},30000);onUnmounted(()=>{clearTimeout(debounce);clearInterval(timer)})
</script>
<template><div class="page-heading"><div><div class="eyebrow">Service monitoring</div><h1>服务监控 <span v-if="data" class="heading-count">{{ data.total }}</span></h1><p>检查策略、结果历史与访问权限，从服务进入。</p></div><div class="actions"><LiveIndicator :seconds="30" :loading="loading" /><button class="btn" :disabled="loading" @click="load"><AppIcon name="refresh" :size="16" />刷新</button><RouterLink v-if="hasRole('OPS_ADMIN')" to="/targets/new" class="btn primary"><AppIcon name="plus" :size="16" />登记目标</RouterLink></div></div><section class="panel"><FilterToolbar v-model:query="query" v-model:sort="sort" placeholder="搜索服务名称、代码或团队" :sorts="[{value:'created_desc',label:'最新在前'},{value:'created_asc',label:'最早在前'},{value:'name_asc',label:'名称 A → Z'},{value:'name_desc',label:'名称 Z → A'}]" /><PageState :loading="loading&&!data" :error="error" :empty="!!data&&!data.items.length" title="没有匹配的可见服务" description="试着修改关键词，或请管理员登记服务并分配读取权限。" @retry="load"><template v-if="data"><div class="table-scroll" tabindex="0" aria-label="服务清单，可横向滚动"><table><thead><tr><th :aria-sort="sort==='name_asc'?'ascending':sort==='name_desc'?'descending':undefined"><button @click="sort=sort==='name_asc'?'name_desc':'name_asc'">服务<AppIcon name="sort" :size="12" /></button></th><th>环境</th><th>健康检查地址</th><th>负责团队</th><th>登记时间</th><th><span class="sr-only">操作</span></th></tr></thead><tbody><tr v-for="t in data.items" :key="t.id"><td><RouterLink :to="'/targets/'+t.id" class="row-title">{{ t.name }}</RouterLink><small class="cell-note mono">{{ t.code }}</small></td><td><span class="env-tag">{{ t.environment }}</span></td><td class="mono url-cell" :title="t.baseUrl">{{ t.baseUrl }}</td><td>{{ t.ownerTeam||'未指定' }}</td><td class="timestamp">{{ time(t.createdAt) }}</td><td><RouterLink :to="'/targets/'+t.id" class="text-link">详情<AppIcon name="chevron" :size="15" /></RouterLink></td></tr></tbody></table></div><PaginationBar :page="page" :total="data.total" :size="20" :loading="loading" @change="p=>{page=p;load()}" /></template></PageState><div class="panel-footnote">服务清单为当前配置快照，不受全局时间范围限制。</div></section></template>
