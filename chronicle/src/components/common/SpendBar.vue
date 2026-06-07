<template>
  <span class="spend-bar">
    <span class="spend-fill" :style="{ width: pct + '%' }" />
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  value: number
  max: number
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
</style>
