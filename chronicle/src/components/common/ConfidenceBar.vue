<template>
  <div class="confidence-bar" role="meter" :aria-valuenow="score" :aria-valuemin="0" :aria-valuemax="1">
    <span class="confidence-bar__score font-mono tabular">{{ score.toFixed(2) }}</span>
    <div class="confidence-bar__track">
      <div class="confidence-bar__fill" :style="{ width: `${score * 100}%` }" />
    </div>
    <span class="confidence-bar__label">{{ label }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ score: number }>()

const label = computed(() => {
  if (props.score >= 0.75) return 'high'
  if (props.score >= 0.5) return 'medium'
  return 'low'
})
</script>

<style scoped>
.confidence-bar {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.confidence-bar__score {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory);
  min-width: 3ch;
  flex-shrink: 0;
}

.confidence-bar__track {
  flex: 1;
  height: 4px;
  background: rgba(255, 255, 255, 0.08);
  border-radius: 2px;
  overflow: hidden;
}

.confidence-bar__fill {
  height: 100%;
  background-color: var(--blood-crimson);
  border-radius: 2px;
  transition: width var(--transition-base);
}

.confidence-bar__label {
  font-size: var(--text-micro);
  color: var(--ash-gray);
  letter-spacing: 0.05em;
  text-transform: uppercase;
  min-width: 5ch;
  flex-shrink: 0;
}
</style>
