<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api,errorText } from '../api'
import AppIcon from './AppIcon.vue'
const props=defineProps<{incidentId:string}>(),router=useRouter(),busy=ref(false),error=ref(''),key=ref(crypto.randomUUID())
async function start(){busy.value=true;error.value='';try{const run=await api<{runId:string}>(`/incidents/${props.incidentId}/agent-runs`,{method:'POST',body:{},key:key.value});key.value=crypto.randomUUID();await router.push('/agent-runs/'+run.runId)}catch(e){error.value=errorText(e)}finally{busy.value=false}}
</script>
<template><div class="start-diagnosis"><button class="btn" :disabled="busy" @click="start"><AppIcon name="diagnosis" :size="16" />{{ busy?'正在创建任务…':'发起 AI 诊断' }}</button><p v-if="error" class="error-message" role="alert">{{ error }}</p></div></template>
