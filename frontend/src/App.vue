<script setup lang="ts">
import { computed,onUnmounted,ref,watch } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { auth,clearSession,errorText,logout } from './api'
import { roleNames } from './composables'
import { useWorkspace,ranges } from './stores/workspace'
import { useNavigation } from './stores/navigation'
import { safeReturnPath,viewKey } from './navigation-model'
import AppIcon from './components/AppIcon.vue'
import Button from './components/ui/Button.vue'
import Dialog from './components/ui/Dialog.vue'
import Select from './components/ui/Select.vue'
import GlobalSearch from './components/GlobalSearch.vue'
import SidebarNav from './components/SidebarNav.vue'
const route=useRoute(),router=useRouter(),workspace=useWorkspace(),navigation=useNavigation(),leaving=ref(false),error=ref(''),search=ref(false),mobileNav=ref(false)
const roleLabel=computed(()=>auth.user?.roles.map((role:string)=>roleNames[role]||role).join(' · ')||'')
const currentTitle=computed(()=>navigation.tabs.find(t=>t.key===viewKey(route.path))?.title||String(route.meta.title||'工作台'))
watch(()=>auth.user?.id,()=>{navigation.reset();if(auth.user&&!route.meta.public)navigation.visit(route.fullPath,String(route.meta.title||'工作台'))})
const removeAfter=router.afterEach((to,from)=>{if(!to.meta.public&&auth.user)navigation.visit(to.fullPath,String(to.meta.title||'工作台'),from.meta.public?undefined:from.fullPath)})
const expiryTimer=setInterval(()=>{if(auth.token&&Date.parse(auth.expiresAt)<=Date.now())clearSession('登录已过期，请重新登录。')},1000)
function key(event:KeyboardEvent){if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='k'&&auth.user&&!route.meta.public){event.preventDefault();search.value=!search.value}}
window.addEventListener('keydown',key)
onUnmounted(()=>{clearInterval(expiryTimer);window.removeEventListener('keydown',key);removeAfter()})
watch(()=>auth.token,value=>{if(!value){search.value=false;mobileNav.value=false;if(!route.meta.public)void router.replace({path:'/login',query:auth.reason?{redirect:route.fullPath}:{}})}})
watch(()=>route.fullPath,()=>{mobileNav.value=false})
async function signOut(){leaving.value=true;error.value='';try{await logout();await router.replace('/login')}catch(e){error.value=errorText(e)}finally{leaving.value=false}}
function closeTab(path:string){const next=navigation.close(path,route.fullPath);if(next!==route.fullPath)void router.replace(next)}
function back(){const previous=router.options.history.state.back;if(typeof previous==='string'&&safeReturnPath(previous,'')&&viewKey(previous)!==route.path)router.back();else{const destination=navigation.tabs.find(t=>t.key===route.path)?.returnTo;void router.push(safeReturnPath(destination,'/incidents'))}}
</script>
<template>
 <template v-if="route.meta.public"><RouterView /><Button class="login-theme" variant="outline" size="icon" :aria-label="workspace.theme==='dark'?'切换浅色主题':'切换深色主题'" @click="workspace.toggleTheme"><AppIcon :name="workspace.theme==='dark'?'sun':'moon'" /></Button></template>
 <div v-else-if="auth.user" class="workspace" :class="{'sidebar-is-collapsed':workspace.collapsed}">
 <a href="#main-content" class="skip-link">跳到主要内容</a>
 <aside class="sidebar"><RouterLink to="/overview" class="brand" aria-label="AgentOps AI 首页"><span class="brand-symbol"><AppIcon name="pulse" :size="23" /></span><span class="brand-word">AgentOps<span class="brand-ai">AI</span></span></RouterLink><div class="workspace-label"><span class="workspace-dot"></span><span>Operations workspace</span></div><SidebarNav /><div class="sidebar-footer"><AppIcon name="shield" :size="16" /><span>操作可追溯 · 人工掌控</span></div><button class="icon-button sidebar-collapse" :aria-label="workspace.collapsed?'展开导航':'收起导航'" @click="workspace.collapsed=!workspace.collapsed"><AppIcon :name="workspace.collapsed?'expand':'collapse'" /></button></aside>
 <div class="workspace-body"><header class="topbar"><div class="workspace-toolbar"><button class="icon-button mobile-only" aria-label="打开导航" @click="mobileNav=true"><AppIcon name="menu" /></button><button class="icon-button" aria-label="返回上一工作页面" @click="back"><AppIcon name="arrow" /></button><strong class="toolbar-title">{{ currentTitle }}</strong></div><div class="topbar-tools"><button class="search-trigger" aria-label="全局搜索，Ctrl K" @click="search=true"><AppIcon name="search" :size="15" /><span>搜索工作空间</span><kbd>⌘ / Ctrl K</kbd></button><label class="time-control" title="筛选事件、任务和图表；服务状态始终为当前快照"><AppIcon name="clock" :size="14" /><span class="sr-only">全局时间范围</span><Select v-model="workspace.range" :options="ranges" aria-label="全局时间范围" /></label><button class="icon-button" :aria-label="workspace.theme==='dark'?'切换浅色主题':'切换深色主题'" @click="workspace.toggleTheme"><AppIcon :name="workspace.theme==='dark'?'sun':'moon'" :size="17" /></button><div class="account"><span class="avatar">{{ auth.user.displayName.slice(0,1).toUpperCase() }}</span><span class="account-name">{{ auth.user.displayName }}<small>{{ roleLabel }}</small></span><button class="icon-button" aria-label="退出登录" :disabled="leaving" @click="signOut"><AppIcon name="logout" :size="16" /></button></div></div></header>
 <nav class="work-tabs" aria-label="已打开的工作页面"><div v-for="tab in navigation.tabs" :key="tab.key" class="work-tab" :class="{'tab-active':tab.key===route.path}"><RouterLink :to="tab.fullPath" :aria-current="tab.key===route.path?'page':undefined" :title="tab.title"><AppIcon v-if="tab.key==='/overview'" name="overview" :size="13" /><span>{{ tab.title }}</span></RouterLink><button v-if="tab.key!=='/overview'" class="tab-close" :aria-label="'关闭页签 '+tab.title" @click="closeTab(tab.key)"><AppIcon name="close" :size="12" /></button></div></nav>
 <main id="main-content" tabindex="-1"><div v-if="error" class="error-message" role="alert">{{ error }}</div><RouterView :key="route.fullPath.split('#')[0]" /></main><footer class="page-footer"><span>AgentOps AI · 智能运维与故障诊断</span><span>实时状态来自检查记录 · 高风险建议须人工审阅</span></footer></div>
 <GlobalSearch v-model:open="search" /><Dialog v-model:open="mobileNav" title="工作空间导航" description="选择列表、当前事件或诊断处理入口"><SidebarNav mobile /></Dialog>
 </div>
</template>
