<template>
  <article class="prey-card" :class="borderClass" data-testid="prey-card">
    <!-- Header -->
    <div class="prey-card__header">
      <div class="prey-card__identity">
        <span class="prey-card__symbol font-mono">{{ prey.symbol }}</span>
        <span class="prey-card__name">{{ prey.companyName }}</span>
      </div>
      <div class="prey-card__meta-right">
        <span class="prey-card__badge">{{ prey.anomalyType }}</span>
      </div>
    </div>

    <!-- Confidence -->
    <div class="prey-card__confidence">
      <ConfidenceBar :score="prey.confidence" />
    </div>

    <!-- Thesis -->
    <p class="prey-card__thesis">{{ prey.thesis }}</p>

    <!-- Signals + Risks -->
    <div class="prey-card__signals-risks">
      <div class="prey-card__column">
        <div class="prey-card__column-label">signals</div>
        <ul class="prey-card__list prey-card__list--signals">
          <li v-for="signal in prey.signals" :key="signal">{{ signal }}</li>
        </ul>
      </div>
      <div class="prey-card__column">
        <div class="prey-card__column-label">risks</div>
        <ul class="prey-card__list prey-card__list--risks">
          <li v-for="risk in prey.risks" :key="risk">{{ risk }}</li>
        </ul>
      </div>
    </div>

    <!-- Footer -->
    <div class="prey-card__footer">
      <span class="font-mono tabular">
        discovered by {{ prey.discoveredBy }} · {{ relativeTime(prey.discoveredAt) }} · horizon: {{ prey.horizon }}
      </span>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Prey } from '../../api/types'
import ConfidenceBar from './ConfidenceBar.vue'
import { useRelativeTime } from '../../composables/useRelativeTime'

const props = defineProps<{ prey: Prey }>()
const { relativeTime } = useRelativeTime()

const borderClass = computed(() => {
  if (props.prey.confidence >= 0.75) return 'prey-card--high'
  if (props.prey.confidence >= 0.5) return 'prey-card--mid'
  return 'prey-card--low'
})
</script>

<style scoped>
.prey-card {
  background-color: var(--crypt-black-elevated);
  border: 1px solid rgba(184, 148, 92, 0.1);
  border-radius: 4px;
  border-left-width: 3px;
  padding: var(--space-5);
  transition: border-color var(--transition-fast);
}

.prey-card:hover {
  border-color: rgba(184, 148, 92, 0.3);
  border-left-color: inherit;
}

.prey-card--high { border-left-color: var(--blood-crimson); }
.prey-card--mid  { border-left-color: var(--cathedral-gold); }
.prey-card--low  { border-left-color: var(--ash-gray); }

.prey-card__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--space-3);
}

.prey-card__identity {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
}

.prey-card__symbol {
  font-size: var(--text-body-lg);
  color: var(--bone-ivory);
  font-weight: 500;
}

.prey-card__name {
  font-size: var(--text-body);
  color: var(--bone-ivory-dim);
}

.prey-card__badge {
  font-size: var(--text-micro);
  color: var(--cathedral-gold);
  border: 1px solid rgba(184, 148, 92, 0.4);
  border-radius: 2px;
  padding: 1px 6px;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.prey-card__confidence {
  margin-bottom: var(--space-4);
}

.prey-card__thesis {
  font-size: var(--text-body);
  color: var(--bone-ivory);
  line-height: 1.6;
  max-width: 70ch;
  margin: 0 0 var(--space-4) 0;
}

.prey-card__signals-risks {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.prey-card__column-label {
  font-size: var(--text-micro);
  color: var(--ash-gray);
  letter-spacing: 0.05em;
  text-transform: uppercase;
  margin-bottom: var(--space-2);
}

.prey-card__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.prey-card__list li {
  font-size: var(--text-body-sm);
  line-height: 1.5;
  padding-left: var(--space-3);
  position: relative;
}

.prey-card__list li::before {
  content: '•';
  position: absolute;
  left: 0;
}

.prey-card__list--signals li { color: var(--bone-ivory); }
.prey-card__list--signals li::before { color: var(--cathedral-gold); }
.prey-card__list--risks li { color: var(--bone-ivory-dim); }
.prey-card__list--risks li::before { color: var(--ash-gray); }

.prey-card__footer {
  font-size: var(--text-micro);
  color: var(--ash-gray);
  letter-spacing: 0.02em;
}
</style>
