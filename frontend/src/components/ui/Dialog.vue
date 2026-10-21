<script setup lang="ts">
import { DialogRoot,DialogPortal,DialogOverlay,DialogContent,DialogTitle,DialogDescription,DialogClose } from 'reka-ui'
import AppIcon from '../AppIcon.vue'
defineProps<{open:boolean;title:string;description:string;wide?:boolean}>()
defineEmits<{'update:open':[value:boolean]}>()
</script>
<template><DialogRoot :open="open" @update:open="$emit('update:open',$event)"><DialogPortal><DialogOverlay class="dialog-overlay" /><DialogContent class="dialog-content" :class="{'dialog-wide':wide}"><div class="dialog-heading"><DialogTitle>{{ title }}</DialogTitle></div><DialogDescription class="dialog-description">{{ description }}</DialogDescription><slot /><DialogClose class="icon-button dialog-close" aria-label="关闭对话框"><AppIcon name="close" /></DialogClose></DialogContent></DialogPortal></DialogRoot></template>
