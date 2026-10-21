<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth, clearSession, errorText, hasRole, logout } from './api'
import { roleNames } from './composables'
import AppIcon from './components/AppIcon.vue'
const route = useRoute(), router = useRouter(), leaving = ref(false), error = ref('')
const roleLabel = computed(() => auth.user?.roles.map((role: string) => roleNames[role] || role).join(' · ') || '')
const nav = computed(() => [
  { path:'/overview', icon:'overview', label:'运行概览' }, { path:'/targets', icon:'target', label:'服务目标' },
  { path:'/incidents', icon:'incident', label:'事故队列' }, {path:'/runbooks',icon:'shield',label:'运行手册'}, ...(hasRole('OPS_ADMIN') ? [{ path:'/admin/users', icon:'users', label:'用户与角色' }] : []),
])
const expiryTimer = setInterval(() => { if (auth.token && Date.parse(auth.expiresAt) <= Date.now()) clearSession('登录已过期，请重新登录。') }, 1000)
onUnmounted(() => clearInterval(expiryTimer))
watch(() => auth.token, value => { if (!value && !route.meta.public) void router.replace('/login') })
async function signOut() { leaving.value = true; error.value = ''; try { await logout(); await router.replace('/login') } catch(e) { error.value = errorText(e) } finally { leaving.value = false } }
</script>
<template>
  <RouterView v-if="route.meta.public" />
  <div v-else-if="auth.user" class="workspace">
    <a href="#main-content" class="skip-link">跳到主要内容</a>
    <aside class="sidebar">
      <RouterLink to="/overview" class="brand" aria-label="AgentOps AI 首页"><span class="brand-symbol"><AppIcon name="pulse" :size="25" /></span><span class="brand-word">AgentOps<span class="brand-ai">AI</span></span></RouterLink>
      <div class="workspace-label"><span class="workspace-dot"></span><span>本地工作空间</span></div>
      <nav aria-label="主要导航"><RouterLink v-for="item in nav" :key="item.path" :to="item.path" :title="item.label" :class="{selected:route.path.startsWith(item.path)}"><AppIcon :name="item.icon" /><span>{{ item.label }}</span></RouterLink></nav>
      <div class="sidebar-footer"><AppIcon name="shield" :size="18" /><span>每次处理，都有迹可循</span></div>
    </aside>
    <div class="workspace-body">
      <header class="topbar"><div class="breadcrumb">工作空间 <span>/</span> <strong>{{ route.meta.title }}</strong></div><div class="account"><span class="avatar">{{ auth.user.displayName.slice(0,1).toUpperCase() }}</span><span class="account-name">{{ auth.user.displayName }}<small>{{ roleLabel }}</small></span><button class="icon-button" title="退出登录" aria-label="退出登录" :disabled="leaving" @click="signOut"><AppIcon name="logout" /></button></div></header>
      <main id="main-content" tabindex="-1"><div v-if="error" class="error-message" role="alert">{{ error }}</div><RouterView :key="route.fullPath" /></main>
      <footer class="page-footer"><span>AgentOps AI · 服务监控与事故协作</span><span>检查证据与操作审计，由本地服务记录</span></footer>
    </div>
  </div>
</template>
