<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { api,hasRole } from '../api'
import { useLoad,time } from '../composables'
import type { Target,Page } from '../types'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import AppIcon from '../components/AppIcon.vue'
const page=ref(0),{data,loading,error,load}=useLoad(()=>api<Page<Target>>(`/targets?page=${page.value}&size=20`))
function change(value:number){page.value=value;void load()};onMounted(load)
</script>
<template><div class="page-heading"><div><h1>服务目标 <span v-if="data" class="heading-count">{{ data.total }}</span></h1><p>管理已登记的服务、检查策略和目标访问权限。</p></div><div class="actions"><button class="btn" :disabled="loading" @click="load"><AppIcon name="refresh" :size="16" />刷新</button><RouterLink v-if="hasRole('OPS_ADMIN')" to="/targets/new" class="btn primary"><AppIcon name="plus" :size="17" />登记目标</RouterLink></div></div><section class="panel"><PageState :loading="loading&&!data" :error="error" :empty="!!data&&!data.items.length" title="还没有可见的服务目标" description="管理员登记服务并分配读取权限后，目标会出现在这里。" @retry="load"><template #action><RouterLink v-if="hasRole('OPS_ADMIN')" to="/targets/new" class="btn primary">登记第一个目标</RouterLink></template><template v-if="data"><div class="table-scroll"><table><thead><tr><th>目标</th><th>环境</th><th>健康检查地址</th><th>负责人</th><th>登记时间</th><th><span class="sr-only">操作</span></th></tr></thead><tbody><tr v-for="t in data.items" :key="t.id"><td><RouterLink :to="'/targets/'+t.id" class="row-title">{{ t.name }}</RouterLink><small class="cell-note mono">{{ t.code }}</small></td><td><span class="env-tag">{{ t.environment }}</span></td><td class="mono url-cell" :title="t.baseUrl">{{ t.baseUrl }}</td><td>{{ t.ownerTeam||'未指定' }}</td><td class="timestamp">{{ time(t.createdAt) }}</td><td><RouterLink :to="'/targets/'+t.id" class="text-link">查看<AppIcon name="chevron" :size="15" /></RouterLink></td></tr></tbody></table></div><PaginationBar :page="page" :total="data.total" :size="20" :loading="loading" @change="change" /></template></PageState></section></template>
