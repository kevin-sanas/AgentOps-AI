<script setup lang="ts">
defineProps<{ page: number; size: number; total: number; loading?: boolean }>()
defineEmits<{ change: [page: number] }>()
</script>
<template><div class="pagination"><span>共 {{ total }} 条 · 第 {{ page + 1 }} / {{ Math.max(1, Math.ceil(total / size)) }} 页</span><div><button class="btn small" :disabled="page === 0 || loading" @click="$emit('change',page - 1)">上一页</button><button class="btn small" :disabled="(page + 1) * size >= total || loading" @click="$emit('change',page + 1)">下一页</button></div></div></template>
