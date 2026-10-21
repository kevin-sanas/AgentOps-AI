import { createRouter,createWebHistory } from 'vue-router'
import { auth,clearSession,hasRole } from './api'
export const router=createRouter({history:createWebHistory(),routes:[
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
 {path:'/admin/users',component:()=>import('./pages/UsersPage.vue'),meta:{title:'用户与角色',admin:true}},
 {path:'/:pathMatch(.*)*',redirect:'/overview'},
]})
router.beforeEach(to=>{if(auth.token&&Date.parse(auth.expiresAt)<=Date.now())clearSession('登录已过期，请重新登录。');if(!to.meta.public&&!auth.token)return '/login';if(to.meta.admin&&!hasRole('OPS_ADMIN'))return '/overview';if(to.path==='/login'&&auth.token&&auth.user)return '/overview'})
router.afterEach(to=>{document.title=`${to.meta.title||'工作台'} · AgentOps AI`})
