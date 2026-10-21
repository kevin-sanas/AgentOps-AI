<script setup lang="ts">
import { Primitive } from 'reka-ui'
import { cva } from 'class-variance-authority'
import { cn } from '../../lib/utils'
withDefaults(defineProps<{variant?:'default'|'outline'|'ghost'|'danger';size?:'default'|'sm'|'icon';as?:string;asChild?:boolean;class?:string}>(),{variant:'default',size:'default',as:'button'})
const variants=cva('ui-button',{variants:{variant:{default:'ui-button-primary',outline:'ui-button-outline',ghost:'ui-button-ghost',danger:'ui-button-danger'},size:{default:'',sm:'ui-button-sm',icon:'ui-button-icon'}}})
</script>
<template><Primitive :as="as" :as-child="asChild" :class="cn(variants({variant,size}),$props.class)"><slot /></Primitive></template>
