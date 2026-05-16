<template>
  <div class="alert-row" role="listitem">
    <span class="alert-row__icon" aria-hidden="true">⚠</span>
    <div class="alert-row__content">
      <span class="alert-row__symbol font-mono">{{ alert.symbol }}</span>
      <span class="alert-row__description">{{ alert.description }}</span>
    </div>
    <span class="alert-row__time font-mono tabular">{{ formatTime(alert.triggeredAt) }}</span>
  </div>
</template>

<script setup lang="ts">
import type { DaywalkerAlert } from '../../api/types'
import { useRelativeTime } from '../../composables/useRelativeTime'

defineProps<{ alert: DaywalkerAlert }>()
const { formatTime } = useRelativeTime()
</script>

<style scoped>
.alert-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  transition: background var(--transition-fast);
}

.alert-row:last-child {
  border-bottom: none;
}

.alert-row:hover {
  background: rgba(255, 255, 255, 0.03);
}

.alert-row__icon {
  color: var(--cathedral-gold);
  font-size: var(--text-body-sm);
  flex-shrink: 0;
}

.alert-row__content {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex: 1;
  min-width: 0;
}

.alert-row__symbol {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory);
  font-weight: 500;
  flex-shrink: 0;
}

.alert-row__description {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.alert-row__time {
  font-size: var(--text-micro);
  color: var(--ash-gray);
  flex-shrink: 0;
}
</style>
