import { createRouter, createWebHistory } from 'vue-router'
import { auth, clearSession, hasRole } from './api'
import LoginPage from './pages/LoginPage.vue'
import OverviewPage from './pages/OverviewPage.vue'
import TargetsPage from './pages/TargetsPage.vue'
import TargetDetailPage from './pages/TargetDetailPage.vue'
import CreateTargetPage from './pages/CreateTargetPage.vue'
import IncidentsPage from './pages/IncidentsPage.vue'
import IncidentDetailPage from './pages/IncidentDetailPage.vue'
import UsersPage from './pages/UsersPage.vue'
import RunbooksPage from './pages/RunbooksPage.vue'
import RunbookDetailPage from './pages/RunbookDetailPage.vue'
import AgentRunPage from './pages/AgentRunPage.vue'
export const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/login', component: LoginPage, meta: { public: true, title: '登录' } },
  { path: '/', redirect: '/overview' }, { path: '/overview', component: OverviewPage, meta: { title: '运行概览' } },
  { path: '/targets', component: TargetsPage, meta: { title: '服务目标' } },
  { path: '/targets/new', component: CreateTargetPage, meta: { title: '登记服务目标', admin: true } },
  { path: '/targets/:id', component: TargetDetailPage, meta: { title: '目标详情' } },
  { path: '/incidents', component: IncidentsPage, meta: { title: '事故队列' } },
  { path: '/incidents/:id', component: IncidentDetailPage, meta: { title: '事故详情' } },
  { path: '/runbooks', component: RunbooksPage, meta: { title: '运行手册' } },
  { path: '/runbooks/:id', component: RunbookDetailPage, meta: { title: '手册版本' } },
  { path: '/agent-runs/:id', component: AgentRunPage, meta: { title: '只读诊断' } },
  { path: '/admin/users', component: UsersPage, meta: { title: '用户与角色', admin: true } },
  { path: '/:pathMatch(.*)*', redirect: '/overview' },
] })
router.beforeEach(to => {
  if (auth.token && Date.parse(auth.expiresAt) <= Date.now()) clearSession('登录已过期，请重新登录。')
  if (!to.meta.public && !auth.token) return '/login'
  if (to.meta.admin && !hasRole('OPS_ADMIN')) return '/overview'
  if (to.path === '/login' && auth.token && auth.user) return '/overview'
})
router.afterEach(to => { document.title = `${to.meta.title || '工作台'} · AgentOps AI` })
