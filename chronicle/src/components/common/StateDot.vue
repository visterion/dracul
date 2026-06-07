<template>
  <span :class="['state-dot', dotClass]" role="img" :aria-label="`State: ${state}`" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { StrigoiState } from '@/api/types'

const props = defineProps<{
  state: StrigoiState
}>()

// Map 'budget-hit' → 'blocked'; all others map 1:1
const dotClass = computed(() => props.state === 'budget-hit' ? 'blocked' : props.state)
</script>

<style scoped>
.state-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex: 0 0 9px;
  display: inline-block;
}
.state-dot.hunting {
  background: var(--blood-crimson);
  box-shadow: 0 0 8px rgba(161, 29, 44, 0.55);
}
.state-dot.resting {
  background: transparent;
  border: 1.5px solid var(--ash-gray);
}
.state-dot.paused {
  background: linear-gradient(90deg, var(--cathedral-gold) 0 50%, transparent 50% 100%);
  border: 1.5px solid var(--cathedral-gold);
}
.state-dot.blocked {
  background: var(--blood-crimson-muted);
  position: relative;
}
</style>
