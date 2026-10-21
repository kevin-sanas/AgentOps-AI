import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
export const ranges = [{value:'1h',label:'最近 1 小时',hours:1},{value:'6h',label:'最近 6 小时',hours:6},{value:'24h',label:'最近 24 小时',hours:24},{value:'7d',label:'最近 7 天',hours:168}]
export const useWorkspace = defineStore('workspace', () => {
  let saved = 'dark'
  try { saved = localStorage.getItem('agentops.theme') || 'dark' } catch { /* restricted storage */ }
  const theme = ref(saved === 'light' ? 'light' : 'dark'), range = ref('24h'), collapsed = ref(false)
  watch(theme, value => { document.documentElement.dataset.theme = value; document.documentElement.classList.toggle('dark',value==='dark'); try { localStorage.setItem('agentops.theme',value) } catch { /* nonessential preference */ } },{immediate:true})
  const rangeLabel = computed(() => ranges.find(r=>r.value===range.value)?.label || '最近 24 小时')
  function windowQuery() { const to = new Date(), hours = ranges.find(r=>r.value===range.value)?.hours || 24; return new URLSearchParams({from:new Date(to.getTime()-hours*3600000).toISOString(),to:to.toISOString()}).toString() }
  function toggleTheme() { theme.value = theme.value==='dark'?'light':'dark' }
  return {theme,range,rangeLabel,collapsed,windowQuery,toggleTheme}
})
