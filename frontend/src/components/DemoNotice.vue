<script setup lang="ts">
import { ref } from 'vue'
import Dialog from './ui/Dialog.vue'
const open=ref(false)
</script>
<template><aside class="demo-notice" aria-label="独立演示环境"><strong>独立演示环境</strong><span>合成检查样本 · Python mock · 退出后数据清除</span><button class="text-button" @click="open=true">验收步骤</button></aside><Dialog v-model:open="open" title="交互式演示验收" description="此环境使用独立临时数据；登录账号和本次随机密码在启动终端显示。"><ol class="demo-instructions"><li>登录 demo-admin，进入“服务监控 → 服务列表”，查看预建的验收服务。</li><li>在启动终端输入 <code>down</code>，注入三条合成503。进入“告警与事件 → 事件列表”，确认事故并开始排查。</li><li>发起AI诊断，等待“立即审阅”；核对冻结证据和手册，填写批准或拒绝理由。</li><li>批准后回填实际结果。仅验收时选择“未执行”，不会自动解决事故。</li><li>终端输入 <code>up</code> 注入四条合成200，核对恢复观察、解决及人工关闭。</li><li>可换 demo-observer 核对只读权限。终端 <code>status</code> 查看状态，<code>quit</code> 退出；会话最长两小时。</li></ol><p class="form-hint">探测样本为明确标注的演示数据，不代表外部网站运行情况；诊断任务、审批、检查点和审计实际执行。</p></Dialog></template>
<style scoped>.demo-notice{display:flex;align-items:center;gap:8px 16px;flex-wrap:wrap;padding:12px 24px;border-bottom:1px solid var(--border);background:var(--surface);color:var(--muted);font-size:12px}.demo-notice strong{color:var(--warning)}.demo-notice button{margin-left:auto}.demo-instructions{padding-left:20px;margin:20px 0}.demo-instructions li{margin:12px 0}@media(max-width:600px){.demo-notice{padding:12px 16px}.demo-notice span{flex:1 0 100%;order:3}}</style>
