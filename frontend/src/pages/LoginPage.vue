<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { auth, errorText, login } from '../api'
import AppIcon from '../components/AppIcon.vue'
const router=useRouter(),username=ref(''),password=ref(''),busy=ref(false),error=ref('')
async function submit(){busy.value=true;error.value='';try{await login(username.value,password.value);password.value='';await router.replace('/overview')}catch(e){error.value=errorText(e)}finally{busy.value=false}}
</script>
<template><div class="login-page"><section class="login-story"><div class="brand"><span class="brand-symbol"><AppIcon name="pulse" :size="25" /></span><span>AgentOps <span class="brand-ai">AI</span></span></div><div><h1>看清服务状态，<br>推进每一次处理。</h1><p>从健康检查到恢复观察，<br>让检查证据、事故进展和协作记录留在同一个地方。</p><div class="login-features"><div><AppIcon name="target" />注册目标，持续检查</div><div><AppIcon name="incident" />跟踪事故，确认恢复</div><div><AppIcon name="shield" />按权限协作，保留审计</div></div></div><small>服务监控与事故协作工作台</small></section><section class="login-main"><form class="login-form" @submit.prevent="submit"><h2>登录工作空间</h2><p class="muted">使用管理员为你创建的账号登录。</p><div v-if="auth.reason" class="notice" role="status">{{ auth.reason }}</div><div v-if="error" class="error-message" role="alert">{{ error }}</div><label>用户名<input v-model="username" name="username" autocomplete="username" required maxlength="80" autofocus placeholder="输入用户名"></label><label>密码<input v-model="password" name="password" type="password" autocomplete="current-password" required maxlength="200" placeholder="输入密码"></label><button class="btn primary full" :disabled="busy">{{ busy?'正在登录…':'登录' }}<AppIcon name="chevron" :size="17" /></button><p class="form-hint">登录会话仅保存在当前页面。忘记密码时，请联系工作空间管理员。</p></form></section></div></template>
