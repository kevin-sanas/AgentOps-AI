<script setup lang="ts">
import { cn } from '../../lib/utils'
defineProps<{modelValue?:string;class?:string}>()
defineEmits<{'update:modelValue':[value:string]}>()
</script>
<template><input :value="modelValue" :class="cn('ui-input',$props.class)" @input="$emit('update:modelValue',($event.target as HTMLInputElement).value)" /></template>
