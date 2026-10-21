<script setup lang="ts">
import { onMounted,reactive,ref } from 'vue'
import { api,errorText } from '../api'
import { useLoad } from '../composables'
import type { NetworkPolicy } from '../types'
import PageState from './PageState.vue'
const props=defineProps<{targetId:string}>(),emit=defineEmits<{updated:[]}>()
const {data,loading,error,load}=useLoad(()=>api<NetworkPolicy>(`/targets/${props.targetId}/network-policy`))
const form=reactive({baseUrl:'',allowHttp:false,cidrs:'',reason:''}),busy=ref(false),saveError=ref(''),saved=ref(false)
async function refresh(){await load();if(data.value){form.baseUrl=data.value.baseUrl;form.allowHttp=data.value.allowHttp;form.cidrs=data.value.allowedPrivateCidrs.join('\n');form.reason=''}}onMounted(refresh)
async function save(){if(!data.value)return;busy.value=true;saveError.value='';saved.value=false;try{await api(`/targets/${props.targetId}/network-policy`,{method:'PUT',body:{version:data.value.version,baseUrl:form.baseUrl.trim(),allowHttp:form.allowHttp,allowedPrivateCidrs:form.cidrs.split(/[,\n]/).map(s=>s.trim()).filter(Boolean),reason:form.reason.trim()}});await refresh();saved.value=true;emit('updated')}catch(e){saveError.value=errorText(e)}finally{busy.value=false}}
</script>
<template><div class="panel-heading"><div><h2>网络策略</h2><p>URL 与访问批准一起保存；活动事故期间配置冻结。</p></div><span v-if="data" class="version-tag">目标配置 v{{ data.version }}</span></div><PageState :loading="loading&&!data" :error="error" @retry="refresh"><form v-if="data" class="form-panel" @submit.prevent="save"><div v-if="saveError" class="error-message" role="alert">{{ saveError }}<button type="button" class="text-button" @click="refresh">重新加载</button></div><div v-if="saved" class="success-message" role="status">网络策略已更新。</div><label>健康检查地址<input v-model="form.baseUrl" type="url" required maxlength="2048"><small>仅支持域名、标准 80/443 端口；不跟随重定向。</small></label><label class="check-label"><input v-model="form.allowHttp" type="checkbox">批准使用 HTTP 明文连接</label><label>允许的私网 CIDR<textarea v-model="form.cidrs" rows="3" placeholder="每行一个 CIDR，留空仅允许公网"></textarea><small>私网访问需要同时满足目标批准与部署出站白名单；metadata、回环和链路本地地址始终拒绝。</small></label><label>变更理由<textarea v-model="form.reason" required maxlength="500" rows="2" placeholder="说明新地址或访问范围的目的"></textarea></label><div class="form-actions"><button type="button" class="btn" :disabled="busy" @click="refresh">放弃修改</button><button class="btn primary" :disabled="busy">{{ busy?'保存中…':'保存网络策略' }}</button></div></form></PageState></template>
