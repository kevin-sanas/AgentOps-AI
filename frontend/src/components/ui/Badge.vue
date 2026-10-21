<script setup lang="ts">
import { cn } from '../../lib/utils'
withDefaults(defineProps<{tone?:'neutral'|'good'|'warning'|'danger'|'info'|'ai';class?:string}>(),{tone:'neutral'})
</script>
<template><span :class="cn('status',tone,$props.class)"><slot /></span></template>
