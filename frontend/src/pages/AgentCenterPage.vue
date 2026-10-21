<script setup lang="ts">
import Select from '../components/ui/Select.vue'
import { onMounted,onUnmounted,ref,watch,computed } from 'vue'
import { useRoute } from 'vue-router'
import { api,hasRole } from '../api'
import { useRestoreScroll,usePageBounds,useLoad,time,shortId } from '../composables'
import { useWorkspace } from '../stores/workspace'
import { useNavigation } from '../stores/navigation'
import { runAction } from '../navigation-model'
import { agentStatus } from '../diagnosis'
import type { Page } from '../types'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import FilterToolbar from '../components/FilterToolbar.vue'
import AppIcon from '../components/AppIcon.vue'
import LiveIndicator from '../components/LiveIndicator.vue'
import AgentDeliveryPanel from '../components/AgentDeliveryPanel.vue'
type RunRow={id:string;incidentId:string;targetId:string;targetName:string;status:string;modelName:string;createdAt:string;completedAt:string|null;approvalStatus:string|null;deliveryState:string;errorCode:string|null;attempts:number;canOperate:boolean}
const route=useRoute(),workspace=useWorkspace(),navigation=useNavigation(),center=computed(()=>route.path==='/agents'),{page,query,sort,status}=navigation.listState(route.path,{page:0,query:'',sort:'created_desc',status:''})
const {data,loading,error,load}=useLoad(()=>api<Page<RunRow>>('/workspace/agent-runs?'+new URLSearchParams({page:String(page.value),size:'20',q:query.value,sort:sort.value,...(status.value?{status:status.value}:{})})+'&'+workspace.windowQuery()))
usePageBounds(data,page,load);useRestoreScroll(data)
let debounce:ReturnType<typeof setTimeout>|undefined
watch(query,()=>{clearTimeout(debounce);debounce=setTimeout(()=>{page.value=0;void load()},250)});watch([sort,status,()=>workspace.range],()=>{page.value=0;void load()})
onMounted(load);const timer=setInterval(()=>{if(!document.hidden&&!loading.value)void load()},5000);onUnmounted(()=>{clearTimeout(debounce);clearInterval(timer)})
const approvalLabels:Record<string,string>={PENDING:'待人工审批',APPROVED:'已批准线下处理',REJECTED:'已拒绝',EXPIRED:'已过期'}
</script>
<template><div class="page-heading"><div><div class="eyebrow">{{ center?'Agent control center':'AI diagnosis' }}</div><h1>{{ center?'执行与审批中心':'AI 故障诊断' }}</h1><p>{{ center?'查看持久化任务、人工审批与线下处理回填。':'从事件进入诊断，沿观测、推断和证据核对结论。' }}</p></div><div class="actions"><LiveIndicator :seconds="5" :loading="loading" /><button class="btn" :disabled="loading" @click="load"><AppIcon name="refresh" :size="15" />刷新</button></div></div><div class="notice"><AppIcon :name="center?'shield':'diagnosis'" :size="15" style="display:inline;vertical-align:middle;margin-right:6px" />{{ center?'当前 Agent 仅执行只读诊断工具。批准后由人员在线下操作，再如实回填；平台保留审计。':'报告包含诊断结论、关联证据、工具记录、修复建议和审阅状态；不会自动执行修复。' }} {{ workspace.rangeLabel }} · 以任务创建时间筛选。</div><section class="panel"><FilterToolbar v-model:query="query" v-model:sort="sort" placeholder="搜索服务名称或任务编号"><label class="compact-label"><span class="sr-only">任务状态</span><Select v-model="status" aria-label="任务状态" :options="[{value:'',label:'全部任务状态'},...Object.entries(agentStatus).map(([value,label])=>({value,label}))]" /></label></FilterToolbar><PageState :loading="loading&&!data" :error="error" :empty="!!data&&!data.items.length" title="当前范围没有可见诊断任务" description="值班人员可从事件详情发起诊断。报告读取权限沿用目标授权与角色约束。" @retry="load"><template #action><RouterLink to="/incidents" class="btn">查看事件中心<AppIcon name="chevron" :size="14" /></RouterLink></template><template v-if="data"><div class="table-scroll" tabindex="0" aria-label="诊断任务列表，可横向滚动"><table><thead><tr><th>任务 / 服务</th><th>诊断状态</th><th>人工审批</th><th>{{ center?'投递状态':'模型模式' }}</th><th>创建时间</th><th><span class="sr-only">操作</span></th></tr></thead><tbody><tr v-for="run in data.items" :key="run.id"><td><RouterLink :to="'/agent-runs/'+run.id" class="row-title">{{ run.targetName }}</RouterLink><small class="cell-note mono">{{ shortId(run.id) }}</small></td><td><span class="status" :class="['FAILED','REJECTED'].includes(run.status)?'danger':run.status==='WAITING_HUMAN'?'warning':'info'">{{ agentStatus[run.status]||run.status }}</span><small v-if="run.errorCode" class="cell-note mono">{{ run.errorCode }}</small></td><td>{{ run.approvalStatus?approvalLabels[run.approvalStatus]||run.approvalStatus:'尚无审阅请求' }}</td><td v-if="center"><span class="mono">{{ run.deliveryState }}</span><small class="cell-note">已尝试 {{ run.attempts }} 次</small></td><td v-else><span class="env-tag">{{ run.modelName }}</span></td><td class="timestamp">{{ time(run.createdAt) }}</td><td><RouterLink :to="'/agent-runs/'+run.id+runAction({status:run.status,approvalStatus:run.approvalStatus,canOperate:run.canOperate}).hash" class="btn small" :class="{primary:['WAITING_HUMAN','APPROVED_FOR_MANUAL'].includes(run.status)}">{{ runAction({status:run.status,approvalStatus:run.approvalStatus,canOperate:run.canOperate}).label }}<AppIcon name="chevron" :size="14" /></RouterLink></td></tr></tbody></table></div><PaginationBar :page="page" :size="20" :total="data.total" :loading="loading" @change="p=>{page=p;load()}" /></template></PageState></section><AgentDeliveryPanel v-if="center&&hasRole('OPS_ADMIN')" /></template>
