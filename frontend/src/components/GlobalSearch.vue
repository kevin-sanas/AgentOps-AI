<script setup lang="ts">
import { ref,watch,onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { api,errorText,auth } from '../api'
import Dialog from './ui/Dialog.vue'
import Input from './ui/Input.vue'
import AppIcon from './AppIcon.vue'
defineProps<{open:boolean}>();const emit=defineEmits<{'update:open':[value:boolean]}>()
const router=useRouter(),query=ref(''),items=ref<{kind:string;id:string;title:string;subtitle:string;path:string}[]>([]),loading=ref(false),error=ref(''),selected=ref(0)
const kinds:Record<string,{label:string;icon:string}>={TARGET:{label:'服务',icon:'target'},INCIDENT:{label:'事件',icon:'incident'},RUNBOOK:{label:'知识',icon:'book'},AGENT:{label:'诊断',icon:'diagnosis'}}
let timer:ReturnType<typeof setTimeout>|undefined,generation=0
watch(query, value=>{clearTimeout(timer);const current=++generation;items.value=[];selected.value=0;error.value='';loading.value=false;if(value.trim().length<2)return;loading.value=true;timer=setTimeout(async()=>{try{const result=await api<{items:typeof items.value}>('/workspace/search?q='+encodeURIComponent(value.trim()));if(current===generation)items.value=result.items}catch(e){if(current===generation)error.value=errorText(e)}finally{if(current===generation)loading.value=false}},250)})
watch(()=>auth.token,()=>{query.value='';items.value=[];generation++})
function choose(index:number){const item=items.value[index];if(item){emit('update:open',false);void router.push(item.path)}}
function key(event:KeyboardEvent){if(event.key==='ArrowDown'){event.preventDefault();selected.value=Math.min(items.value.length-1,selected.value+1)}else if(event.key==='ArrowUp'){event.preventDefault();selected.value=Math.max(0,selected.value-1)}else if(event.key==='Enter'){event.preventDefault();choose(selected.value)}}
onUnmounted(()=>{clearTimeout(timer);generation++})
</script>
<template><Dialog :open="open" title="搜索工作空间" description="按服务、事件、手册标题或诊断任务编号搜索。仅返回你有权访问的记录，每类最多 8 项。" wide @update:open="$emit('update:open',$event)"><label class="sr-only" for="global-search">全局搜索</label><Input id="global-search" v-model="query" class="search-input" placeholder="输入至少 2 个字符…" maxlength="100" autocomplete="off" role="combobox" aria-autocomplete="list" aria-controls="search-results" :aria-expanded="items.length>0" :aria-activedescendant="items.length?'search-option-'+selected:undefined" @keydown="key" /><div v-if="error" class="error-message" role="alert">{{ error }}</div><p v-else-if="loading" class="muted" role="status">正在查询…</p><p v-else-if="query.trim().length>=2&&!items.length" class="muted" role="status">没有匹配的可访问记录。</p><div id="search-results" class="search-results" role="listbox" aria-label="搜索结果"><button v-for="(item,index) in items" :id="'search-option-'+index" :key="item.kind+item.id" class="search-result" :class="{'is-active':selected===index}" role="option" :aria-selected="selected===index" @click="choose(index)" @pointermove="selected=index"><AppIcon :name="kinds[item.kind]?.icon||'search'" /><span>{{ item.title }}<small>{{ item.subtitle }}</small></span><span class="search-kind">{{ kinds[item.kind]?.label }}</span></button></div><div class="search-footer"><span>↑ ↓ 选择 · Enter 打开 · Esc 关闭</span><span>搜索不受全局时间范围限制</span></div></Dialog></template>
