<template>
  <div class="dusk-strip enter enter-1" data-testid="dusk-strip">
    <div class="dusk-left">
      <BatGlyph :size="18" :dim="false" />
      <span class="dusk-date">{{ today }}</span>
    </div>
    <div class="dusk-tally">
      <span class="dt-item"><b class="font-mono tabular">{{ prey }}</b> {{ t('chronicle.dusk.newPrey') }}</span>
      <span class="dt-sep">·</span>
      <span class="dt-item"><b class="font-mono tabular">{{ verdicts }}</b> {{ t('chronicle.dusk.verdicts', verdicts) }}</span>
      <span class="dt-sep">·</span>
      <span class="dt-item"><b class="font-mono tabular">{{ alerts }}</b> {{ t('chronicle.dusk.alerts', alerts) }}</span>
      <span class="dt-sep">·</span>
      <span class="dt-item"><b class="font-mono tabular">{{ lessons }}</b> {{ t('chronicle.dusk.lessons', lessons) }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import BatGlyph from './BatGlyph.vue'

defineProps<{
  prey: number
  verdicts: number
  alerts: number
  lessons: number
}>()

const { t, locale } = useI18n()

const today = computed(() => {
  const formatted = new Intl.DateTimeFormat(locale.value, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  }).format(new Date())
  // capitalize first letter (German weekday/month are capitalized anyway;
  // .dusk-date also applies text-transform: capitalize)
  return formatted.charAt(0).toUpperCase() + formatted.slice(1)
})
</script>

<style scoped>
/* mirrors styles.css:140-146 */
.dusk-strip {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  flex-wrap: wrap;
  padding: var(--space-4) var(--space-5);
  margin-bottom: var(--space-8);
  background: linear-gradient(100deg, rgba(161, 29, 44, 0.06), rgba(19, 19, 26, 0.6) 60%);
  border: var(--hairline);
  border-radius: 4px;
  position: relative;
  overflow: hidden;
}

.dusk-strip::after {
  content: '';
  position: absolute;
  top: -40px;
  right: -10px;
  width: 120px;
  height: 120px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(196, 196, 202, 0.1), transparent 70%);
  pointer-events: none;
}

.dusk-left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex: 0 0 auto;
}

.dusk-date {
  font-family: var(--font-display);
  font-size: var(--text-h4);
  color: var(--bone-ivory);
  text-transform: capitalize;
  white-space: nowrap;
}

.dusk-tally {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
  margin-left: auto;
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
}

.dusk-tally b {
  color: var(--bone-ivory);
  font-weight: 500;
}

.dt-sep { color: rgba(107, 107, 112, 0.5); }

/* ── Mobile (< 960px) — mirrors styles.css:525-526 ──
   Tighten the strip so it doesn't dominate a small viewport. Scoped here
   for the same specificity reason as VerdictCard's mobile rules. */
@media (max-width: 959.98px) {
  .dusk-strip {
    padding: var(--space-3) var(--space-4);
    margin-bottom: var(--space-5);
    gap: var(--space-3);
  }
}
</style>
