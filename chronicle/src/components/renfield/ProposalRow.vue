<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { Proposal } from '../../api/types'
import { formatNumber } from '../../utils/format'
import TickerButton from '../instrument/TickerButton.vue'

defineProps<{ proposal: Proposal }>()
const { t } = useI18n()

function actionPillClass(action: string): string {
  switch (action) {
    case 'sell':
    case 'drop_from_watchlist': return 'crimson'
    case 'trim': return 'gold'
    case 'buy':
    case 'add': return 'green'
    default: return 'ash'
  }
}

// Wire sentiment is a number in [-1.0, +1.0] (schemas/renfield-review.json,
// pinned by NewsSentimentSchemaTest) — never a string label. Bucket it the
// same way the rest of the app reasons about signed scores: a dead zone
// around 0 reads as neutral rather than forcing every non-zero value into
// positive/negative.
type SentimentBucket = 'positive' | 'negative' | 'neutral'

function sentimentBucket(sentiment: number): SentimentBucket {
  if (sentiment > 0.2) return 'positive'
  if (sentiment < -0.2) return 'negative'
  return 'neutral'
}

function sentimentLabel(sentiment: number): string {
  return t(`proposals.sentiment.${sentimentBucket(sentiment)}`)
}
</script>

<template>
  <div class="proposal-row" data-testid="proposal-row">
    <div class="proposal-main">
      <span class="proposal-action tag-pill" :class="actionPillClass(proposal.action)">
        {{ t(`proposals.actions.${proposal.action}`) }}
      </span>
      <TickerButton :symbol="proposal.symbol" class="proposal-symbol mono" />
    </div>

    <div
      v-if="proposal.entryZone || proposal.stop || proposal.confidence !== null"
      class="proposal-metrics mono"
    >
      <span v-if="proposal.entryZone">
        <span class="metric-label">{{ t('proposals.entryZone') }}</span> {{ proposal.entryZone }}
      </span>
      <span v-if="proposal.stop">
        <span class="metric-label">{{ t('proposals.stop') }}</span> {{ proposal.stop }}
      </span>
      <span v-if="proposal.confidence !== null">
        <span class="metric-label">{{ t('proposals.confidence') }}</span> {{ formatNumber(proposal.confidence, 2) }}
      </span>
    </div>

    <p v-if="proposal.rationale" class="proposal-rationale">{{ proposal.rationale }}</p>

    <ul
      v-if="proposal.newsSentiment && proposal.newsSentiment.length > 0"
      class="proposal-news"
      data-testid="proposal-news"
    >
      <li v-for="(n, idx) in proposal.newsSentiment" :key="idx" class="news-item">
        <span class="news-sentiment" :class="`sentiment-${sentimentBucket(n.sentiment)}`">{{ sentimentLabel(n.sentiment) }}</span>
        <span class="news-headline">{{ n.headline }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.proposal-row {
  background: var(--crypt-black-elevated);
  border: var(--hairline);
  border-radius: 4px;
  padding: var(--space-5);
  transition: border-color var(--transition-fast);
}
.proposal-row:hover { border-color: rgba(184,148,92,0.30); }

.proposal-main {
  display: flex;
  gap: var(--space-3);
  align-items: baseline;
  flex-wrap: wrap;
  margin-bottom: var(--space-3);
}
.proposal-symbol { font-size: var(--text-body); color: var(--bone-ivory); font-weight: 500; }
.proposal-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1) var(--space-5);
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
  margin-bottom: var(--space-3);
}
.metric-label {
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--ash-gray);
  margin-right: var(--space-1);
}
.proposal-rationale {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
  margin: 0 0 var(--space-3);
  font-style: italic;
}
.proposal-news { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: var(--space-2); }
.news-item { display: flex; gap: var(--space-2); align-items: baseline; font-size: var(--text-body-sm); }
.news-sentiment {
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  flex-shrink: 0;
}
.sentiment-positive { color: var(--signal-positive-bright); }
.sentiment-negative { color: var(--blood-crimson-bright); }
.sentiment-neutral { color: var(--ash-gray-light); }
.news-headline { color: var(--bone-ivory-dim); }
</style>
