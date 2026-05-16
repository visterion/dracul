<template>
  <div class="lesson-row" role="listitem">
    <span class="lesson-row__icon" aria-label="Lesson scroll">📜</span>
    <div class="lesson-row__content">
      <span class="lesson-row__strigoi">{{ pattern.appliesToStrigoi }}</span>
      <span class="lesson-row__statement">{{ truncated }}</span>
    </div>
    <router-link :to="{ name: 'pattern-library' }" class="lesson-row__link">
      review →
    </router-link>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Pattern } from '../../api/types'

const props = defineProps<{ pattern: Pattern }>()

const truncated = computed(() =>
  props.pattern.statement.length > 120
    ? props.pattern.statement.slice(0, 117) + '…'
    : props.pattern.statement,
)
</script>

<style scoped>
.lesson-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.lesson-row:last-child {
  border-bottom: none;
}

.lesson-row__icon {
  font-size: var(--text-body-sm);
  flex-shrink: 0;
}

.lesson-row__content {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex: 1;
  min-width: 0;
}

.lesson-row__strigoi {
  font-family: var(--font-mono);
  font-size: var(--text-micro);
  color: var(--cathedral-gold);
  flex-shrink: 0;
}

.lesson-row__statement {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lesson-row__link {
  font-size: var(--text-body-sm);
  color: var(--blood-crimson);
  text-decoration: none;
  flex-shrink: 0;
  transition: color var(--transition-fast);
}

.lesson-row__link:hover {
  color: var(--blood-crimson-bright);
}
</style>
