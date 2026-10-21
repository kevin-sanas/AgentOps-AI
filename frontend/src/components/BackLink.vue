<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useNavigation } from '../stores/navigation'
import { viewKey,safeReturnPath } from '../navigation-model'
import AppIcon from './AppIcon.vue'
const props=defineProps<{fallback:string;label?:string}>(),route=useRoute(),navigation=useNavigation()
const destination=computed(()=>safeReturnPath(navigation.tabs.find(t=>t.key===viewKey(route.path))?.returnTo,props.fallback))
const title=computed(()=>props.label||({ '/incidents':'返回事件列表','/diagnosis':'返回诊断任务','/agents':'返回执行与审批','/targets':'返回服务列表','/runbooks':'返回知识库'}[viewKey(destination.value)]||'返回上一工作页面'))
</script>
<template><RouterLink :to="destination" class="back-link"><AppIcon name="arrow" :size="15" />{{ title }}</RouterLink></template>
