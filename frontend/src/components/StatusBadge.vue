<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ value: string }>()
const labels: Record<string,string> = { UP:'正常',DOWN:'异常',UNKNOWN:'未知',ACTIVE:'监控中',PAUSED:'已暂停',DISABLED:'未启用',OPEN:'待确认',ACKNOWLEDGED:'已确认',INVESTIGATING:'处理中',MONITORING:'恢复观察',RESOLVED:'已解决',CLOSED:'已关闭' }
const tone = computed(() => ['UP','RESOLVED','CLOSED'].includes(props.value) ? 'good' : ['DOWN','OPEN'].includes(props.value) ? 'danger' : ['INVESTIGATING','PAUSED'].includes(props.value) ? 'warning' : ['ACTIVE','ACKNOWLEDGED','MONITORING'].includes(props.value) ? 'info' : 'neutral')
</script>
<template><span class="status" :class="tone"><span class="status-dot"></span>{{ labels[value] || value }}</span></template>
