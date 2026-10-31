import { createRouter,createWebHistory } from 'vue-router'
import { auth,clearSession,hasRole } from './api'
import { MODEL_SETTINGS_META,MODEL_SETTINGS_PATH } from './model-admin'
import { QUALITY_PATH } from './quality-center'
import { useNavigation } from './stores/navigation'
export const router=createRouter({history:createWebHistory(),scrollBehavior(to,from,saved){if(saved)return saved;const anchor=to.hash?document.getElementById(to.hash.slice(1)):null;if(anchor)return {el:anchor,top:96};if(to.path===from.path&&to.hash)return false;return useNavigation().positions[to.fullPath]||{top:0}},routes:[
 {path:'/login',component:()=>import('./pages/LoginPage.vue'),meta:{public:true,title:'登录'}},
 {path:'/',redirect:'/overview'},{path:'/overview',component:()=>import('./pages/OverviewPage.vue'),meta:{title:'运维总览'}},
 {path:'/targets',component:()=>import('./pages/TargetsPage.vue'),meta:{title:'服务监控'}},
 {path:'/targets/new',component:()=>import('./pages/CreateTargetPage.vue'),meta:{title:'登记服务目标',admin:true}},
 {path:'/targets/:id',component:()=>import('./pages/TargetDetailPage.vue'),meta:{title:'服务详情'}},
 {path:'/incidents',component:()=>import('./pages/IncidentsPage.vue'),meta:{title:'告警与事件'}},
 {path:'/incidents/:id',component:()=>import('./pages/IncidentDetailPage.vue'),meta:{title:'事件详情'}},
 {path:'/diagnosis',component:()=>import('./pages/AgentCenterPage.vue'),meta:{title:'AI 故障诊断'}},
 {path:'/agents',component:()=>import('./pages/AgentCenterPage.vue'),meta:{title:'执行与审批'}},
 {path:'/runbooks',component:()=>import('./pages/RunbooksPage.vue'),meta:{title:'运维知识库'}},
 {path:'/runbooks/:id',component:()=>import('./pages/RunbookDetailPage.vue'),meta:{title:'知识与版本'}},
 {path:'/agent-runs/:id',component:()=>import('./pages/AgentRunPage.vue'),meta:{title:'诊断工作台'}},
 {path:MODEL_SETTINGS_PATH,component:()=>import('./pages/ModelSettingsPage.vue'),meta:MODEL_SETTINGS_META},
 {path:QUALITY_PATH,component:()=>import('./pages/QualityCenterPage.vue'),meta:{title:'诊断质量中心',admin:true}},
 {path:'/admin/users',component:()=>import('./pages/UsersPage.vue'),meta:{title:'用户与角色',admin:true}},
 {path:'/:pathMatch(.*)*',component:()=>import('./pages/NotFoundPage.vue'),meta:{title:'页面不存在'}},
]})
router.beforeEach((to,from)=>{if(auth.user&&!from.meta.public)useNavigation().positions[from.fullPath]={top:window.scrollY,left:window.scrollX};if(auth.token&&Date.parse(auth.expiresAt)<=Date.now())clearSession('登录已过期，请重新登录。');if(!to.meta.public&&!auth.token)return {path:'/login',query:{redirect:to.fullPath}};if(to.meta.admin&&!hasRole('OPS_ADMIN'))return '/overview';if(to.path==='/login'&&auth.token&&auth.user)return '/overview'})
router.afterEach(to=>{document.title=`${to.meta.title||'工作台'} · Dawnwatch`})
