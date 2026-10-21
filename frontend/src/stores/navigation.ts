import { defineStore } from 'pinia'
import { reactive,ref,toRefs } from 'vue'
import { closeTab,visitTab,viewKey,type WorkTab } from '../navigation-model'
type Context={targetId?:string;targetName?:string;incidentId?:string;incidentName?:string;runId?:string;approval?:boolean}
export const useNavigation=defineStore('navigation',()=>{
  const tabs=ref<WorkTab[]>([{key:'/overview',fullPath:'/overview',title:'运维总览'}]),context=ref<Context>({}),groups=reactive<Record<string,boolean>>({}),lists=reactive<Record<string,Record<string,string|number>>>({}),positions=reactive<Record<string,{top:number;left:number}>>({})
  function visit(fullPath:string,title:string,from?:string){visitTab(tabs.value,{fullPath,title},from)}
  function close(key:string,current:string){return closeTab(tabs.value,key,current)}
  function title(path:string,value:string){const tab=tabs.value.find(t=>t.key===viewKey(path));if(tab)tab.title=value}
  function listState<T extends Record<string,string|number>>(key:string,initial:T){if(!lists[key])lists[key]={...initial};return toRefs(lists[key] as T)}
  function setContext(value:Context){context.value={...value}}
  function reset(){tabs.value=[{key:'/overview',fullPath:'/overview',title:'运维总览'}];context.value={};for(const key of Object.keys(lists))delete lists[key];for(const key of Object.keys(groups))delete groups[key];for(const key of Object.keys(positions))delete positions[key]}
  return {tabs,context,groups,positions,visit,close,title,listState,setContext,reset}
})
