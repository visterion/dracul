<template>
  <article
    class="prey-card"
    :class="confClass"
    data-testid="prey-card"
    role="button"
    tabindex="0"
    @click="$emit('open', prey)"
    @keydown.enter="$emit('open', prey)"
    @keydown.space.prevent="$emit('open', prey)"
  >
    <!-- Header -->
    <header class="prey-head">
      <TickerButton :symbol="prey.symbol" class="prey-ticker font-mono" />
      <span v-if="displayName(prey.symbol, prey.companyName)" class="prey-name">{{ displayName(prey.symbol, prey.companyName) }}</span>
      <span class="prey-head-spacer" />
      <span class="anomaly-badge">{{ anomalyTypeLabel(prey.anomalyType) }}</span>
    </header>

    <!-- Confidence -->
    <div class="conf-block">
      <ConfidenceBar :score="prey.confidence" />
    </div>

    <!-- Thesis -->
    <p class="prey-thesis">{{ prey.thesis }}</p>

    <!-- Signals + Risks -->
    <div class="sr-grid">
      <div class="sr-col">
        <div class="sr-head">{{ t('chronicle.preyCard.signals') }}</div>
        <ul class="sr-list">
          <li v-for="signal in prey.signals" :key="signal">{{ signal }}</li>
        </ul>
      </div>
      <div class="sr-col">
        <div class="sr-head">{{ t('chronicle.preyCard.risks') }}</div>
        <ul class="sr-list">
          <li v-for="risk in prey.risks" :key="risk">{{ risk }}</li>
        </ul>
      </div>
    </div>

    <!-- Footer -->
    <footer class="prey-foot">
      <span>{{ t('chronicle.preyCard.discoveredBy') }} <span class="by">{{ prey.discoveredBy }}</span></span>
      <span class="fdot">·</span>
      <span>{{ relativeTime(prey.discoveredAt) }}</span>
      <span class="fdot">·</span>
      <span>{{ t('chronicle.preyCard.horizon') }}: {{ horizonLabel(prey.horizon) }}</span>
    </footer>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Prey } from '../../api/types'
import ConfidenceBar from './ConfidenceBar.vue'
import { useRelativeTime } from '../../composables/useRelativeTime'
import { useEnumLabels } from '../../composables/useEnumLabels'
import { displayName } from '../../utils/instrument'
import TickerButton from '../instrument/TickerButton.vue'

const props = defineProps<{ prey: Prey }>()
defineEmits<{ (e: 'open', prey: Prey): void }>()

const { t } = useI18n()
const { relativeTime } = useRelativeTime()
const { anomalyTypeLabel, horizonLabel } = useEnumLabels()

// confClass per prototype: >0.75 high, >=0.5 mid, else low
const confClass = computed(() =>
  props.prey.confidence > 0.75 ? 'conf-high' : props.prey.confidence >= 0.5 ? 'conf-mid' : 'conf-low',
)
</script>

<style scoped>
/* mirrors styles.css:184-211 (sr-* 204-208) */
.prey-card {
  background: var(--crypt-black-elevated);
  border: var(--hairline);
  border-left: 3px solid var(--ash-gray);
  border-radius: 4px;
  padding: var(--space-5);
  cursor: pointer;
  transition: border-color var(--transition-fast), background var(--transition-fast);
}

.prey-card.conf-high { border-left-color: var(--blood-crimson); }
.prey-card.conf-mid { border-left-color: var(--cathedral-gold); }
.prey-card.conf-low { border-left-color: var(--ash-gray); }

.prey-card:hover {
  border-top-color: rgba(184, 148, 92, 0.3);
  border-right-color: rgba(184, 148, 92, 0.3);
  border-bottom-color: rgba(184, 148, 92, 0.3);
  background: #15151d;
}

.prey-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.prey-ticker {
  font-size: var(--text-body-lg);
  font-weight: 500;
  color: var(--bone-ivory);
}

.prey-name {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
}

.prey-head-spacer { flex: 1; }

.anomaly-badge {
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--cathedral-gold);
  border: 1px solid rgba(184, 148, 92, 0.4);
  border-radius: 3px;
  padding: 3px 8px;
  white-space: nowrap;
  line-height: 1;
}

.conf-block { margin-bottom: var(--space-4); }

.prey-thesis {
  color: var(--bone-ivory);
  font-size: var(--text-body-sm);
  line-height: 1.6;
  max-width: 72ch;
  margin: 0 0 var(--space-4);
  text-wrap: pretty;
}

.sr-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-5);
  margin-bottom: var(--space-4);
}

.sr-head {
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: var(--ash-gray);
  margin-bottom: var(--space-2);
}

.sr-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.sr-list li {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
  padding-left: var(--space-4);
  position: relative;
  line-height: 1.5;
}

.sr-list li::before {
  content: '•';
  position: absolute;
  left: 0;
  color: var(--cathedral-gold);
}

.prey-foot {
  font-family: var(--font-mono);
  font-size: var(--text-micro);
  color: var(--ash-gray);
  border-top: 1px solid var(--rule);
  padding-top: var(--space-3);
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.prey-foot .by { color: var(--bone-ivory-dim); }

.prey-card:focus-visible {
  outline: 2px solid var(--cathedral-gold);
  outline-offset: 2px;
}

/* Long unbreakable tokens in model-generated content (company names, tickers,
   URLs in thesis / signals / risks) must wrap rather than expand the card past
   its container and get clipped on the right edge. min-width:0 lets the flex
   item (name) and the grid columns (signals/risks) shrink below content size. */
.prey-name,
.prey-thesis,
.sr-list li,
.prey-foot .by {
  overflow-wrap: anywhere;
  word-break: break-word;
}
.prey-name { min-width: 0; }
.sr-col { min-width: 0; }
</style>
