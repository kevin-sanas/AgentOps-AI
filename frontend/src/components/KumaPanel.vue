<script setup lang="ts">
import { onMounted,reactive,ref } from 'vue'
import { api,errorText } from '../api'
import PageState from './PageState.vue'
interface Config { id:string;targetId:string;baseUrl:string;enabled:boolean;smokePassed:boolean;version:number }
interface Delivery { id:string;kind:string;outcome:string;state:string;attempts:number;httpStatus:number|null;errorCode:string|null;createdAt:string }
const props=defineProps<{targetId:string}>(),config=ref<Config|null>(null),items=ref<Delivery[]>([])
const loading=ref(false),busy=ref(false),error=ref(''),actionError=ref(''),notice=ref('')
const form=reactive({baseUrl:'',secretRef:'env:KUMA_PUSH_TOKEN_DEMO',reason:''})
const states:Record<string,string>={PENDING:'待投递',DELIVERED:'已送达',DEAD:'已停止重试',SUPERSEDED:'已被新状态替代'}
async function load(){loading.value=true;error.value='';try{const rows=await api<Config[]>(`/integrations/uptime-kuma?targetId=${props.targetId}`);config.value=rows[0]??null;items.value=config.value?(await api<{items:Delivery[]}>(`/integrations/uptime-kuma/${config.value.id}/deliveries`)).items:[]}catch(e){error.value=errorText(e)}finally{loading.value=false}}
async function act(action:'create'|'smoke'|'toggle'){
  busy.value=true;actionError.value='';notice.value=''
  try{
    const reason=form.reason.trim();if(!reason){actionError.value='请填写操作理由。';return}
    if(action==='create')await api('/integrations/uptime-kuma',{method:'POST',body:{targetId:props.targetId,baseUrl:form.baseUrl.trim(),secretRef:form.secretRef.trim(),reason}})
    else if(config.value){
      const path=`/integrations/uptime-kuma/${config.value.id}`
      if(action==='smoke')await api(path+'/test',{method:'POST',body:{reason}})
      else await api(path,{method:'PUT',body:{version:config.value.version,enabled:!config.value.enabled,reason}})
    }
    form.reason='';notice.value=action==='smoke'?'测试已排队，请稍后刷新查看投递结果。':'配置已保存。';await load()
  }catch(e){actionError.value=errorText(e)}finally{busy.value=false}
}
onMounted(load)
</script>
<template>
  <div class="panel-heading"><div><h2>Kuma 推送</h2><p>把检查状态推送到已建好的 Push Monitor。检查历史与事故仍保存在本平台。</p></div><button class="btn" :disabled="loading||busy" @click="load">刷新</button></div>
  <PageState :loading="loading&&!config" :error="error" @retry="load">
    <div v-if="actionError" class="error-message" role="alert">{{ actionError }}<button class="text-button" @click="load">重新加载</button></div>
    <div v-if="notice" class="success-message" role="status">{{ notice }}</div>
    <form class="form-panel" @submit.prevent="act(config?'smoke':'create')">
      <template v-if="!config">
        <label>Kuma 服务地址<input v-model="form.baseUrl" type="url" required maxlength="2048" placeholder="填写部署管理员已允许的 Kuma 地址"><small>只填写协议、域名和端口，不粘贴含 token 的 Push URL。</small></label>
        <label>令牌配置引用<input v-model="form.secretRef" required maxlength="120" pattern="env:KUMA_PUSH_TOKEN_[A-Z0-9_]{1,80}"><small>填写环境变量引用，例如 env:KUMA_PUSH_TOKEN_DEMO；实际 token 由后端环境保存。</small></label>
      </template>
      <template v-else><p><strong>{{ config.enabled?'推送已启用':'推送已停用' }}</strong> · {{ config.smokePassed?'测试已通过':'需先测试通过' }} · v{{ config.version }}</p><p class="target-address"><code>{{ config.baseUrl }}</code></p></template>
      <label>操作理由<textarea v-model="form.reason" required maxlength="500" rows="2" placeholder="说明配置、测试或启停的原因"></textarea></label>
      <div class="form-actions"><template v-if="config"><button class="btn" :disabled="busy">测试推送</button><button class="btn" type="button" :disabled="busy||(!config.enabled&&!config.smokePassed)" @click="act('toggle')">{{ config.enabled?'停用推送':'启用推送' }}</button></template><button v-else class="btn primary" :disabled="busy">保存为停用状态</button></div>
    </form>
    <p class="muted">后端默认关闭 Kuma。启用部署开关并配置 token 后，先测试，再启用。多项检查只推送最新有效样本；无数据或未知状态不会显示为正常。测试会向预建 Monitor 发送一条测试 UP。</p>
    <template v-if="config"><h3>最近 20 条投递</h3><p v-if="!items.length" class="muted">暂无投递记录。</p><div v-else class="table-scroll"><table><thead><tr><th>时间</th><th>类型 / 状态</th><th>投递结果</th><th>尝试 / 响应</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td>{{ new Date(item.createdAt).toLocaleString() }}</td><td>{{ item.kind==='SMOKE'?'测试':'检查' }} / {{ item.outcome }}</td><td>{{ states[item.state]||item.state }}<small v-if="item.errorCode" class="muted"> {{ item.errorCode }}</small></td><td>{{ item.attempts }} 次 / {{ item.httpStatus??'—' }}</td></tr></tbody></table></div></template>
  </PageState>
</template>
<style scoped>
.table-scroll{max-width:100%;overflow-x:auto}small{overflow-wrap:anywhere}
</style>
