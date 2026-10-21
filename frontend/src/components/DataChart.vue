<script setup lang="ts">
import { computed,onMounted,onUnmounted,ref,watch } from 'vue'
import { init,use,type ECharts,type EChartsCoreOption } from 'echarts/core'
import { LineChart,BarChart,PieChart } from 'echarts/charts'
import { GridComponent,TooltipComponent,LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useWorkspace } from '../stores/workspace'
use([LineChart,BarChart,PieChart,GridComponent,TooltipComponent,LegendComponent,CanvasRenderer])
const props=defineProps<{kind:'events'|'health'|'latency';points?:[number,number|null][];segments?:{name:string;value:number;tone:string}[];label:string;compact?:boolean}>()
const el=ref<HTMLElement>(),workspace=useWorkspace();let chart:ECharts|undefined,observer:ResizeObserver|undefined
const empty=computed(()=>props.kind==='health'?!props.segments?.length:!props.points?.length)
function render(){if(!el.value)return;if(empty.value){chart?.dispose();chart=undefined;return}if(!chart)chart=init(el.value,undefined,{renderer:'canvas'});chart.resize();const css=getComputedStyle(document.documentElement),color=(key:string)=>css.getPropertyValue('--'+key).trim(),text=color('muted'),border=color('border'),accent=color('accent');let option:EChartsCoreOption
 if(props.kind==='health')option={tooltip:{trigger:'item',renderMode:'richText'},series:[{type:'pie',radius:['65%','82%'],center:['50%','50%'],label:{show:false},data:props.segments?.map(s=>({name:s.name,value:s.value,itemStyle:{color:color(s.tone)}}))}]}
 else option={tooltip:{trigger:'axis',renderMode:'richText',valueFormatter:(v:unknown)=>v===null?'未知':String(v)+(props.kind==='latency'?' ms':' 起')},grid:{left:48,right:24,top:24,bottom:36},xAxis:{type:'time',axisLabel:{color:text,fontSize:10,hideOverlap:true},axisLine:{lineStyle:{color:border}},splitLine:{show:false}},yAxis:{type:'value',min:0,minInterval:props.kind==='events'?1:undefined,axisLabel:{color:text,fontSize:10},splitLine:{lineStyle:{color:border,type:'dashed'}}},series:[{name:props.kind==='events'?'新建事件':'检查延迟',type:props.kind==='events'?'bar':'line',data:props.points,barMaxWidth:24,smooth:false,connectNulls:false,showSymbol:true,symbolSize:5,lineStyle:{width:2},itemStyle:{color:props.kind==='latency'?color('ai'):accent},areaStyle:undefined}]}
 chart.setOption({...option,animation:!window.matchMedia('(prefers-reduced-motion: reduce)').matches,backgroundColor:'transparent'},true)
}
watch(()=>[props.points,props.segments,workspace.theme],render,{deep:true,flush:'post'})
onMounted(()=>{render();observer=new ResizeObserver(()=>chart?.resize());if(el.value)observer.observe(el.value)});onUnmounted(()=>{observer?.disconnect();chart?.dispose()})
</script>
<template><div v-if="empty" class="chart-empty"><span>当前范围暂无可绘制记录</span><small>收到实际检查结果后展示</small></div><div ref="el" class="chart-frame" :class="{compact}" :style="empty?{height:'0',overflow:'hidden'}:undefined" role="img" :aria-label="label" /></template>
