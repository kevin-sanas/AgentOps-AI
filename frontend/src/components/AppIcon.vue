<script setup lang="ts">
import { Activity,LayoutDashboard,Server,TriangleAlert,Users,LogOut,Plus,RefreshCw,ArrowLeft,ChevronRight,X,ShieldCheck,Check,Search,Sun,Moon,Menu,PanelLeftClose,PanelLeftOpen,BrainCircuit,Workflow,BookOpen,Clock,ArrowUpDown,ExternalLink,ChevronDown,Radio,FileSearch,KeyRound } from '@lucide/vue'
import type { Component } from 'vue'
withDefaults(defineProps<{name:string;size?:number}>(),{size:18})
const icons:Record<string,Component>={pulse:Activity,overview:LayoutDashboard,target:Server,incident:TriangleAlert,users:Users,logout:LogOut,plus:Plus,refresh:RefreshCw,arrow:ArrowLeft,chevron:ChevronRight,close:X,shield:ShieldCheck,check:Check,search:Search,sun:Sun,moon:Moon,menu:Menu,collapse:PanelLeftClose,expand:PanelLeftOpen,diagnosis:BrainCircuit,agents:Workflow,book:BookOpen,clock:Clock,sort:ArrowUpDown,external:ExternalLink,down:ChevronDown,live:Radio,evidence:FileSearch,model:KeyRound}
</script>
<template><component :is="icons[name]||Activity" :size="size" :stroke-width="1.7" aria-hidden="true" /></template>
