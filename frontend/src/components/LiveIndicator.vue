<script setup lang="ts">
import AppIcon from './AppIcon.vue'
defineProps<{seconds:number;loading?:boolean;updated?:string|null}>()
</script>
<template><span class="live-label" :title="updated?'最近成功更新 '+new Date(updated).toLocaleString():undefined"><AppIcon name="live" :size="13" />{{ loading?'正在更新':'每 '+seconds+' 秒刷新' }}</span></template>
