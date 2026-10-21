import { onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { errorText } from './api'
import { pageAfterRefresh } from './presentation'

export function usePageBounds(data:Ref<{total:number;page:number;size:number}|null>,page:Ref<number>,load:()=>Promise<unknown>) {
  watch(data,result=>{if(!result||result.page!==page.value)return;const corrected=pageAfterRefresh(page.value,result.size,result.total);if(corrected!==page.value){page.value=corrected;void load()}})
}

export function useLoad<T>(fetcher: () => Promise<T>) {
  const data = shallowRef<T | null>(null), loading = ref(false), error = ref('')
  let generation = 0
  async function load() {
    const current = ++generation; loading.value = true; error.value = ''
    try { const result = await fetcher(); if (current === generation) data.value = result }
    catch (e) { if (current === generation) error.value = errorText(e) }
    finally { if (current === generation) loading.value = false }
  }
  onScopeDispose(() => { generation++ })
  return { data, loading, error, load }
}
export function useRequestKey() {
  let fingerprint = '', key = ''
  return {
    forBody(body: unknown) { const value = JSON.stringify(body); if (value !== fingerprint) { fingerprint = value; key = crypto.randomUUID() }; return key },
    clear() { fingerprint = ''; key = '' },
  }
}
export function time(value: string | null | undefined) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚无数据' }
export function shortId(value: string) { return value.slice(0, 8) }
export const roleNames: Record<string, string> = { OPS_ADMIN: '管理员', ON_CALL: '值班人员', OBSERVER: '观察员', AUDITOR: '审计员' }
