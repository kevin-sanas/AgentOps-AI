<script setup lang="ts">
import { computed,onUnmounted,ref,watch } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { auth,clearSession,errorText,hasRole,logout } from './api'
import { roleNames } from './composables'
import { useWorkspace,ranges } from './stores/workspace'
import AppIcon from './components/AppIcon.vue'
import Button from './components/ui/Button.vue'
import Dialog from './components/ui/Dialog.vue'
import GlobalSearch from './components/GlobalSearch.vue'
const route=useRoute(),router=useRouter(),workspace=useWorkspace(),leaving=ref(false),error=ref(''),search=ref(false),mobileNav=ref(false)
const roleLabel=computed(()=>auth.user?.roles.map((role:string)=>roleNames[role]||role).join(' · ')||'')
const nav=computed(()=>[
 {path:'/overview',icon:'overview',label:'运维总览'}, {path:'/targets',icon:'target',label:'服务监控'},
 {path:'/incidents',icon:'incident',label:'告警与事件'}, {path:'/diagnosis',icon:'diagnosis',label:'AI 故障诊断'},
 {path:'/agents',icon:'agents',label:'执行与审批'}, {path:'/runbooks',icon:'book',label:'运维知识库'},
 ...(hasRole('OPS_ADMIN')?[{path:'/admin/users',icon:'users',label:'用户与角色'}]:[]),
])
function selectedNav(path:string){return route.path.startsWith(path)||(path==='/diagnosis'&&route.path.startsWith('/agent-runs/'))}
const expiryTimer=setInterval(()=>{if(auth.token&&Date.parse(auth.expiresAt)<=Date.now())clearSession('登录已过期，请重新登录。')},1000)
function key(event:KeyboardEvent){if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='k'&&auth.user&&!route.meta.public){event.preventDefault();search.value=!search.value}}
window.addEventListener('keydown',key)
onUnmounted(()=>{clearInterval(expiryTimer);window.removeEventListener('keydown',key)})
watch(()=>auth.token,value=>{if(!value){search.value=false;mobileNav.value=false;if(!route.meta.public)void router.replace('/login')}})
watch(()=>route.path,()=>{mobileNav.value=false})
async function signOut(){leaving.value=true;error.value='';try{await logout();await router.replace('/login')}catch(e){error.value=errorText(e)}finally{leaving.value=false}}
</script>
<template>
 <template v-if="route.meta.public"><RouterView /><Button class="login-theme" variant="outline" size="icon" :aria-label="workspace.theme==='dark'?'切换浅色主题':'切换深色主题'" @click="workspace.toggleTheme"><AppIcon :name="workspace.theme==='dark'?'sun':'moon'" /></Button></template>
 <div v-else-if="auth.user" class="workspace" :class="{'sidebar-is-collapsed':workspace.collapsed}">
 <a href="#main-content" class="skip-link">跳到主要内容</a>
 <aside class="sidebar"><RouterLink to="/overview" class="brand" aria-label="AgentOps AI 首页"><span class="brand-symbol"><AppIcon name="pulse" :size="23" /></span><span class="brand-word">AgentOps<span class="brand-ai">AI</span></span></RouterLink><div class="workspace-label"><span class="workspace-dot"></span><span>Operations workspace</span></div><nav aria-label="主要导航"><RouterLink v-for="item in nav" :key="item.path" :to="item.path" :title="item.label" :aria-current="selectedNav(item.path)?'page':undefined" :class="{selected:selectedNav(item.path)}"><AppIcon :name="item.icon" /><span>{{ item.label }}</span></RouterLink></nav><div class="sidebar-footer"><AppIcon name="shield" :size="16" /><span>操作可追溯 · 人工掌控</span></div><button class="icon-button sidebar-collapse" :aria-label="workspace.collapsed?'展开导航':'收起导航'" @click="workspace.collapsed=!workspace.collapsed"><AppIcon :name="workspace.collapsed?'expand':'collapse'" /></button></aside>
 <div class="workspace-body"><header class="topbar"><div class="breadcrumb"><button class="icon-button mobile-only" aria-label="打开导航" @click="mobileNav=true"><AppIcon name="menu" /></button><span>工作空间</span><span>/</span><strong>{{ route.meta.title }}</strong></div><div class="topbar-tools"><button class="search-trigger" aria-label="全局搜索，Ctrl K" @click="search=true"><AppIcon name="search" :size="15" /><span>搜索工作空间</span><kbd>⌘ / Ctrl K</kbd></button><label class="time-control" title="筛选事件、任务和图表；服务状态始终为当前快照"><AppIcon name="clock" :size="14" /><span class="sr-only">全局时间范围</span><select v-model="workspace.range" aria-label="全局时间范围"><option v-for="range in ranges" :key="range.value" :value="range.value">{{ range.label }}</option></select></label><button class="icon-button" :aria-label="workspace.theme==='dark'?'切换浅色主题':'切换深色主题'" @click="workspace.toggleTheme"><AppIcon :name="workspace.theme==='dark'?'sun':'moon'" :size="17" /></button><div class="account"><span class="avatar">{{ auth.user.displayName.slice(0,1).toUpperCase() }}</span><span class="account-name">{{ auth.user.displayName }}<small>{{ roleLabel }}</small></span><button class="icon-button" aria-label="退出登录" :disabled="leaving" @click="signOut"><AppIcon name="logout" :size="16" /></button></div></div></header>
 <main id="main-content" tabindex="-1"><div v-if="error" class="error-message" role="alert">{{ error }}</div><RouterView :key="route.fullPath.split('#')[0]" /></main><footer class="page-footer"><span>AgentOps AI · 智能运维与故障诊断</span><span>实时状态来自检查记录 · 高风险建议须人工审阅</span></footer></div>
 <GlobalSearch v-model:open="search" /><Dialog v-model:open="mobileNav" title="工作空间导航" description="选择运维功能"><nav class="mobile-nav" aria-label="移动导航"><RouterLink v-for="item in nav" :key="item.path" :to="item.path" :class="{selected:selectedNav(item.path)}"><AppIcon :name="item.icon" />{{ item.label }}</RouterLink></nav></Dialog>
 </div>
</template>
