<template>
  <div class="brood-mini" data-testid="brood-mini">
    <button
      v-for="st in strigoi"
      :key="st.name"
      type="button"
      class="brood-row"
      @click="$emit('open', st)"
    >
      <StateDot :state="st.state" />
      <span class="font-mono">{{ st.name }}</span>
      <span class="fc-count font-mono">{{ counts?.[st.name] ?? 0 }}</span>
      <i class="ph ph-caret-right brood-chevron" aria-hidden="true" />
    </button>
  </div>
</template>

<script setup lang="ts">
import type { StrigoiStatus } from '../../api/types'
import StateDot from './StateDot.vue'

defineProps<{
  strigoi: StrigoiStatus[]
  /** map of strigoi name → number of current prey it discovered */
  counts?: Record<string, number>
}>()
defineEmits<{ (e: 'open', strigoi: StrigoiStatus): void }>()
</script>

<style scoped>
/* mirrors styles.css:221-225 */
.brood-mini {
  display: flex;
  flex-direction: column;
}

.brood-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  min-height: 44px;
  background: transparent;
  border: none;
  color: var(--bone-ivory-dim);
  font-size: var(--text-body-sm);
  padding: var(--space-2);
  border-radius: 4px;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.brood-row:hover {
  background: rgba(184, 148, 92, 0.05);
  color: var(--bone-ivory);
}

.brood-row .fc-count {
  margin-left: auto;
  color: var(--ash-gray);
  font-family: var(--font-mono);
}

.brood-chevron {
  font-size: 12px;
  color: var(--ash-gray);
}
</style>
