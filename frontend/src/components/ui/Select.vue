<script setup lang="ts" generic="T extends string|number">
import { computed } from 'vue'
import { SelectRoot,SelectTrigger,SelectValue,SelectIcon,SelectPortal,SelectContent,SelectViewport,SelectItem,SelectItemText,SelectItemIndicator,SelectScrollUpButton,SelectScrollDownButton } from 'reka-ui'
import AppIcon from '../AppIcon.vue'
defineOptions({inheritAttrs:false})
const props=withDefaults(defineProps<{modelValue:T;options:{value:T;label:string;disabled?:boolean}[];disabled?:boolean;required?:boolean;name?:string;placeholder?:string}>(),{placeholder:'请选择'})
const emit=defineEmits<{'update:modelValue':[value:T];change:[value:T]}>()
const encoded=computed(()=>JSON.stringify(props.modelValue)),label=computed(()=>props.options.find(o=>o.value===props.modelValue)?.label||props.placeholder)
function update(value:unknown){const option=props.options.find(o=>JSON.stringify(o.value)===value);if(option&&!option.disabled){emit('update:modelValue',option.value);emit('change',option.value)}}
</script>
<template><SelectRoot :model-value="encoded" :disabled="disabled" :required="required" :name="name" @update:model-value="update"><SelectTrigger v-bind="$attrs" class="ui-select-trigger"><SelectValue>{{ label }}</SelectValue><SelectIcon><AppIcon name="down" :size="14" /></SelectIcon></SelectTrigger><SelectPortal><SelectContent class="ui-select-content" position="popper" :side-offset="6" :collision-padding="12"><SelectScrollUpButton class="ui-select-scroll"><AppIcon name="down" :size="14" style="transform:rotate(180deg)" /></SelectScrollUpButton><SelectViewport class="ui-select-viewport"><SelectItem v-for="option in options" :key="JSON.stringify(option.value)" :value="JSON.stringify(option.value)" :disabled="option.disabled" class="ui-select-item"><SelectItemText>{{ option.label }}</SelectItemText><SelectItemIndicator class="ui-select-indicator"><AppIcon name="check" :size="14" /></SelectItemIndicator></SelectItem></SelectViewport><SelectScrollDownButton class="ui-select-scroll"><AppIcon name="down" :size="14" /></SelectScrollDownButton></SelectContent></SelectPortal></SelectRoot></template>
