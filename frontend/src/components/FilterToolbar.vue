<script setup lang="ts">
import Input from './ui/Input.vue'
import AppIcon from './AppIcon.vue'
withDefaults(defineProps<{query:string;sort:string;placeholder?:string;sorts?:{value:string;label:string}[]}>(),{placeholder:'搜索名称或编号',sorts:()=>[{value:'created_desc',label:'最新在前'},{value:'created_asc',label:'最早在前'},{value:'name_asc',label:'名称 A → Z'}]})
defineEmits<{'update:query':[value:string];'update:sort':[value:string]}>()
</script>
<template><div class="table-toolbar"><label class="filter-search"><span class="sr-only">{{ placeholder }}</span><AppIcon name="search" :size="16" /><Input :model-value="query" :placeholder="placeholder" maxlength="100" type="search" @update:model-value="$emit('update:query',$event)" /></label><div class="filter-controls"><slot /><label class="compact-label"><AppIcon name="sort" :size="14" /><span class="sr-only">排序方式</span><select :value="sort" aria-label="排序方式" @change="$emit('update:sort',($event.target as HTMLSelectElement).value)"><option v-for="item in sorts" :key="item.value" :value="item.value">{{ item.label }}</option></select></label></div></div></template>
