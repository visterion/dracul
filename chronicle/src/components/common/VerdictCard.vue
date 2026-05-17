<template>
  <article class="verdict-card" data-testid="verdict-card">
    <!-- Header -->
    <div class="verdict-card__header">
      <div class="verdict-card__identity">
        <span class="verdict-card__symbol font-mono">{{ verdict.symbol }}</span>
        <span class="verdict-card__name">{{ verdict.companyName }}</span>
      </div>
      <span class="verdict-card__score font-mono tabular">
        consensus: {{ verdict.consensusScore.toFixed(2) }}
      </span>
    </div>

    <!-- Contributing Strigoi + time -->
    <div class="verdict-card__subline">
      found by
      <template v-for="(name, i) in verdict.contributingStrigoi" :key="name">
        <router-link
          :to="{ name: 'strigoi-detail', params: { name } }"
          class="verdict-card__strigoi-link"
        >{{ name }}</router-link><span v-if="i < verdict.contributingStrigoi.length - 1"> · </span>
      </template>
      · {{ relativeTime(verdict.createdAt) }}
    </div>

    <!-- Summary -->
    <p class="verdict-card__summary">{{ summary }}</p>

    <!-- Footer -->
    <div class="verdict-card__footer">
      <router-link :to="{ name: 'verdict-detail', params: { id: verdict.id } }" class="verdict-card__link">
        Read full verdict →
      </router-link>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Verdict } from '../../api/types'
import { useRelativeTime } from '../../composables/useRelativeTime'

const props = defineProps<{ verdict: Verdict }>()
const { relativeTime } = useRelativeTime()

// Truncate summary to ~200 characters for the card view
const summary = computed(() =>
  props.verdict.summary.length > 200
    ? props.verdict.summary.slice(0, 197) + '…'
    : props.verdict.summary,
)
</script>

<style scoped>
.verdict-card {
  background-color: var(--crypt-black-elevated);
  border: 1px solid rgba(184, 148, 92, 0.1);
  border-left: 3px solid var(--blood-crimson);
  border-radius: 4px;
  padding: var(--space-5);
  transition: border-color var(--transition-fast);
}

.verdict-card:hover {
  border-color: rgba(184, 148, 92, 0.3);
  border-left-color: var(--blood-crimson);
}

.verdict-card__header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: var(--space-2);
}

.verdict-card__identity {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
}

.verdict-card__symbol {
  font-size: var(--text-body-lg);
  color: var(--bone-ivory);
  font-weight: 500;
}

.verdict-card__name {
  font-size: var(--text-body);
  color: var(--bone-ivory-dim);
}

.verdict-card__score {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
}

.verdict-card__subline {
  font-size: var(--text-micro);
  color: var(--ash-gray);
  letter-spacing: 0.02em;
  margin-bottom: var(--space-4);
}

.verdict-card__summary {
  font-size: var(--text-body);
  color: var(--bone-ivory);
  line-height: 1.6;
  max-width: 70ch;
  margin: 0 0 var(--space-4) 0;
}

.verdict-card__footer {
  display: flex;
  justify-content: flex-end;
}

.verdict-card__link {
  font-size: var(--text-body-sm);
  color: var(--blood-crimson);
  border: 1px solid var(--blood-crimson);
  border-radius: 2px;
  padding: 2px 8px;
  text-decoration: none;
  background: transparent;
  transition: color var(--transition-fast), background-color var(--transition-fast);
}

.verdict-card__link:hover {
  color: var(--blood-crimson-bright);
  border-color: var(--blood-crimson-bright);
  background-color: rgba(161, 29, 44, 0.08);
}

.verdict-card__strigoi-link {
  color: var(--ash-gray);
  text-decoration: none;
  transition: color var(--transition-fast);
}

.verdict-card__strigoi-link:hover {
  color: var(--bone-ivory-dim);
}
</style>
