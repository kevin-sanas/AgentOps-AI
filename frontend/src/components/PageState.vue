<script setup lang="ts">
import AppIcon from './AppIcon.vue'
defineProps<{ loading?: boolean; error?: string; empty?: boolean; title?: string; description?: string }>()
defineEmits<{ retry: [] }>()
</script>
<template>
  <div v-if="error" class="error-message" role="alert"><div><strong>请求未完成</strong><p>{{ error }}</p></div><button class="btn" @click="$emit('retry')">重试</button></div>
  <div v-else-if="loading" class="loading-lines" aria-label="正在加载" aria-busy="true"><div v-for="n in 4" :key="n"></div></div>
  <div v-else-if="empty" class="empty-state"><AppIcon name="pulse" :size="32" /><h3>{{ title || '暂无记录' }}</h3><p>{{ description }}</p><slot name="action"></slot></div>
  <slot v-else></slot>
</template>
