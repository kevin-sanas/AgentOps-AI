<script setup lang="ts">
import { computed,watch } from 'vue'
import { useRoute } from 'vue-router'
import { hasRole } from '../api'
import { MODEL_SETTINGS_PATH } from '../model-admin'
import { useNavigation } from '../stores/navigation'
import { useWorkspace } from '../stores/workspace'
import AppIcon from './AppIcon.vue'
const props=defineProps<{mobile?:boolean}>()
const route=useRoute(),navigation=useNavigation(),workspace=useWorkspace()
const groups=computed(()=>{
 const c=navigation.context,admin=hasRole('OPS_ADMIN'),target=c.targetId?'/targets/'+c.targetId:'',incident=c.incidentId?'/incidents/'+c.incidentId:'',run=c.runId?'/agent-runs/'+c.runId:''
 return [
  {path:'/overview',icon:'overview',label:'运维总览',children:[]},
  {path:'/targets',icon:'target',label:'服务监控',children:[{to:'/targets',label:'服务列表'},...(admin?[{to:'/targets/new',label:'登记服务'}]:[]),...(target?[{to:target+'?tab=checks',label:'当前服务 · 检查配置'},{to:target+'?tab=history',label:'检查历史'},...(admin?[{to:target+'?tab=network',label:'网络策略'},{to:target+'?tab=access',label:'访问权限'},{to:target+'?tab=kuma',label:'可选 Kuma 集成'}]:[])]:[])]},
  {path:'/incidents',icon:'incident',label:'告警与事件',children:[{to:'/incidents',label:'事件列表'},...(incident?[{to:incident,label:'当前事件 · 处理'},{to:incident+'#timeline',label:'事件时间线'}]:[])]},
  {path:'/diagnosis',icon:'diagnosis',label:'AI 故障诊断',children:[{to:'/diagnosis',label:'诊断任务列表'},...(admin?[{to:MODEL_SETTINGS_PATH,label:'模型配置与用量'}]:[]),...(run?[{to:run+'#report',label:'当前任务 · 诊断报告'},{to:run+'#execution',label:'工具与节点记录'}]:[])]},
  {path:'/agents',icon:'agents',label:'执行与审批',children:[{to:'/agents',label:'执行与审批列表'},...(run&&c.approval?[{to:run+'#review-heading',label:'当前任务 · 人工审阅 / 回填'}]:[])]},
  {path:'/runbooks',icon:'book',label:'运维知识库',children:[{to:'/runbooks',label:'手册目录'},...(target?[{to:'/runbooks?targetId='+c.targetId,label:'当前服务手册'}]:[])]},
  ...(admin?[{path:'/admin/users',icon:'users',label:'用户与角色',children:[]}]:[]),
 ]
})
function active(path:string){return route.path.startsWith(path)||(path==='/diagnosis'&&route.path===MODEL_SETTINGS_PATH)||(route.path.startsWith('/agent-runs/')&&((path==='/agents'&&route.hash==='#review-heading')||(path==='/diagnosis'&&route.hash!=='#review-heading')))}
function opened(path:string){return navigation.groups[path]??active(path)}
function selected(to:string){const [pathAndQuery,hash]=to.split('#'),[path,query]=pathAndQuery.split('?');return route.path===path&&(hash?route.hash==='#'+hash:query?Array.from(new URLSearchParams(query)).every(([key,value])=>route.query[key]===value):!route.hash&&!route.query.tab&&!route.query.targetId)}
function toggle(path:string){if(!props.mobile&&workspace.collapsed){workspace.collapsed=false;navigation.groups[path]=true}else navigation.groups[path]=!opened(path)}
watch(()=>route.fullPath,()=>{const group=groups.value.find(g=>active(g.path));if(group)navigation.groups[group.path]=true},{immediate:true})
</script>
<template><nav :aria-label="mobile?'移动导航':'主要导航'" class="grouped-nav" :class="{'mobile-nav':mobile}"><div v-for="group in groups" :key="group.path" class="nav-group"><RouterLink v-if="!group.children.length" :to="group.path" :title="group.label" class="nav-root" :class="{selected:active(group.path)}"><AppIcon :name="group.icon" /><span>{{ group.label }}</span></RouterLink><template v-else><button class="nav-root" :class="{'group-active':active(group.path)}" :aria-expanded="opened(group.path)" :aria-controls="'nav-'+group.icon+(mobile?'-mobile':'')" :title="group.label" @click="toggle(group.path)"><AppIcon :name="group.icon" /><span>{{ group.label }}</span><AppIcon name="down" :size="13" class="nav-chevron" :class="{'is-open':opened(group.path)}" /></button><div v-show="opened(group.path)" :id="'nav-'+group.icon+(mobile?'-mobile':'')" class="nav-children"><RouterLink v-for="item in group.children" :key="item.to" :to="item.to" :class="{selected:selected(item.to)}" :aria-current="selected(item.to)?'page':undefined"><span>{{ item.label }}</span></RouterLink></div></template></div></nav></template>
