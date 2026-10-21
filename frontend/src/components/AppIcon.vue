<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 20 })
const paths: Record<string, string> = {
  overview: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  target: 'M4 3h16v7H4z M4 14h16v7H4z M7 6.5h.01 M7 17.5h.01 M11 6.5h6 M11 17.5h6',
  incident: 'M12 3 2 21h20L12 3z M12 9v5 M12 17h.01',
  users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M20 8v6 M17 11h6',
  logout: 'M9 21H4V3h5 M9 12h12 M17 8l4 4-4 4',
  plus: 'M12 5v14 M5 12h14', refresh: 'M20 8a8 8 0 1 0 1 7 M20 3v5h-5',
  arrow: 'M19 12H5 M11 6l-6 6 6 6', chevron: 'm9 5 7 7-7 7', close: 'm6 6 12 12 M18 6 6 18',
  shield: 'm12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3z m-4 9 3 3 5-5',
  pulse: 'M2 12h5l3-8 4 16 3-8h5', check: 'm5 12 4 4L19 6',
}
</script>
<template><svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.pulse" /></svg></template>
