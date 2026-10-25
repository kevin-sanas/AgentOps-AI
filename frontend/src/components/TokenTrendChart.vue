<script setup lang="ts">
import { computed,onMounted,onUnmounted,ref,watch } from 'vue'
import { init,use,type ECharts,type EChartsCoreOption } from 'echarts/core'
import { BarChart,LineChart } from 'echarts/charts'
import { GridComponent,LegendComponent,TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { tokenTrendSeries } from '../model-admin'
import { useWorkspace } from '../stores/workspace'
import type { ModelUsageTrendBucket } from '../types'

use([BarChart,LineChart,GridComponent,LegendComponent,TooltipComponent,CanvasRenderer])
const props=defineProps<{buckets:ModelUsageTrendBucket[];label:string}>()
const el=ref<HTMLElement>(),workspace=useWorkspace();let chart:ECharts|undefined,observer:ResizeObserver|undefined
const series=computed(()=>tokenTrendSeries(props.buckets))
const hasRequests=computed(()=>props.buckets.some(row=>row.requests>0))

function render(){
  if(!el.value||!hasRequests.value){chart?.dispose();chart=undefined;return}
  if(!chart)chart=init(el.value,undefined,{renderer:'canvas'})
  const css=getComputedStyle(document.documentElement),color=(key:string)=>css.getPropertyValue('--'+key).trim()
  const text=color('muted'),border=color('border'),input=color('accent'),output=color('ai'),total=color('success')
  const option:EChartsCoreOption={
    animation:!window.matchMedia('(prefers-reduced-motion: reduce)').matches,
    animationDurationUpdate:300,
    backgroundColor:'transparent',
    tooltip:{trigger:'axis',renderMode:'richText',valueFormatter:(value:unknown)=>value===null?'未知':Number(value).toLocaleString('zh-CN')+' Token'},
    legend:{top:0,right:8,textStyle:{color:text,fontSize:11},itemWidth:12,itemHeight:7},
    grid:{left:56,right:24,top:46,bottom:38},
    xAxis:{type:'time',axisLabel:{color:text,fontSize:10,hideOverlap:true},axisLine:{lineStyle:{color:border}},splitLine:{show:false}},
    yAxis:{type:'value',min:0,minInterval:1,axisLabel:{color:text,fontSize:10},splitLine:{lineStyle:{color:border,type:'dashed'}}},
    series:[
      {name:'输入 Token',type:'bar',stack:'tokens',data:series.value.prompt,barMaxWidth:22,itemStyle:{color:input}},
      {name:'输出 Token',type:'bar',stack:'tokens',data:series.value.completion,barMaxWidth:22,itemStyle:{color:output}},
      {name:'总 Token',type:'line',data:series.value.total,connectNulls:false,showSymbol:true,symbolSize:5,lineStyle:{width:2,color:total},itemStyle:{color:total}},
    ],
  }
  chart.setOption(option,true)
  chart.resize()
}
watch(()=>[props.buckets,workspace.theme],render,{deep:true,flush:'post'})
onMounted(()=>{render();observer=new ResizeObserver(()=>chart?.resize());if(el.value)observer.observe(el.value)})
onUnmounted(()=>{observer?.disconnect();chart?.dispose()})
</script>

<template>
 <div v-if="!hasRequests" class="chart-empty token-trend-empty"><span>当前范围没有模型请求</span><small>发生模型调用后展示真实 Token 用量</small></div>
 <div v-else ref="el" class="token-trend-chart" role="img" :aria-label="label" />
</template>
