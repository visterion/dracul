<template>
  <span class="spend-bar" role="meter" :aria-valuenow="value" :aria-valuemin="0" :aria-valuemax="max" :aria-label="`Spend ${value} of ${max}`">
    <span class="spend-fill" :class="`spend-fill--${level ?? 'ok'}`" :style="{ width: pct + '%' }" />
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  value: number
  max: number
  level?: 'ok' | 'warn' | 'over'
}>()

const pct = computed(() => props.max ? Math.min(100, (props.value / props.max) * 100) : 0)
</script>

<style scoped>
/* .spend-bar and .spend-fill are NOT in global.css — defined here */
.spend-bar {
  height: 6px;
  background: rgba(245, 241, 232, 0.06);
  border-radius: 3px;
  overflow: hidden;
  display: block;
}

.spend-fill {
  display: block;
  height: 100%;
  background: var(--cathedral-gold);
  border-radius: 3px;
  transition: width var(--transition-slow);
}

.spend-fill--warn { background: var(--signal-warning); }
.spend-fill--over { background: var(--signal-danger); }
</style>
