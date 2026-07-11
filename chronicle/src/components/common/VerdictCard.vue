<template>
  <article class="verdict-card" data-testid="verdict-card" @click="$emit('open', verdict)">
    <div class="vc-main">
      <header class="vc-head">
        <span class="vc-ticker font-mono">{{ verdict.symbol }}</span>
        <span class="vc-name">{{ verdict.companyName }}</span>
        <span class="vc-spacer" />
        <span class="vc-meta font-mono tabular">{{ t('chronicle.verdictCard.consensus') }}: {{ formatNumber(verdict.consensusScore, 2) }}</span>
      </header>

      <div class="vc-attrib">
        {{ t('chronicle.verdictCard.foundBy') }}
        <span class="vc-strat">{{ verdict.contributingStrigoi.join(' · ') }}</span>
        · {{ relativeTime(verdict.createdAt) }}
      </div>

      <!-- summary used as teaser (no separate teaser/paras on the summary Verdict) -->
      <p class="vc-teaser">{{ verdict.summary }}</p>

      <div class="vc-foot">
        <button
          type="button"
          class="btn btn-secondary btn-crimson-ghost"
          data-testid="verdict-card-read"
          @click.stop="$emit('open', verdict)"
        >
          {{ t('chronicle.verdictCard.readVerdict') }} <i class="ph ph-arrow-right" />
        </button>
      </div>
    </div>

    <aside class="vc-side">
      <ConsensusRing :value="verdict.consensusScore" />
      <div class="vc-side-strat">
        <!-- NAMES ONLY — the summary Verdict has no per-contributor confidence -->
        <div v-for="name in verdict.contributingStrigoi" :key="name" class="vc-side-row">
          <BatGlyph :size="12" />
          <span class="font-mono">{{ name }}</span>
        </div>
      </div>
    </aside>
  </article>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { Verdict } from '../../api/types'
import { useRelativeTime } from '../../composables/useRelativeTime'
import ConsensusRing from './ConsensusRing.vue'
import BatGlyph from './BatGlyph.vue'
import { formatNumber } from '../../utils/format'

defineProps<{ verdict: Verdict }>()
defineEmits<{ (e: 'open', verdict: Verdict): void }>()

const { t } = useI18n()
const { relativeTime } = useRelativeTime()
</script>

<style scoped>
/* mirrors styles.css:164-181 */
.verdict-card {
  display: grid;
  grid-template-columns: 1fr 188px;
  background: linear-gradient(180deg, var(--surface-2), var(--crypt-black-elevated));
  border: var(--hairline);
  border-left: 3px solid var(--blood-crimson);
  border-radius: 4px;
  cursor: pointer;
  overflow: hidden;
  transition: border-color var(--transition-fast);
}

.verdict-card:hover {
  border-top-color: rgba(184, 148, 92, 0.3);
  border-right-color: rgba(184, 148, 92, 0.3);
  border-bottom-color: rgba(184, 148, 92, 0.3);
}

.vc-main {
  padding: var(--space-5);
  min-width: 0;
}

.vc-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
}

.vc-ticker {
  font-size: var(--text-body-lg);
  font-weight: 500;
  color: var(--bone-ivory);
}

.vc-name {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
}

.vc-spacer { flex: 1; }

.vc-meta {
  font-size: var(--text-body-sm);
  color: var(--cathedral-gold);
  white-space: nowrap;
}

.vc-attrib {
  font-size: var(--text-body-sm);
  color: var(--ash-gray);
  margin-bottom: var(--space-4);
}

.vc-strat {
  color: var(--bone-ivory-dim);
  font-family: var(--font-mono);
  font-size: 0.92em;
}

.vc-teaser {
  color: var(--bone-ivory);
  font-size: var(--text-body);
  line-height: 1.6;
  margin: 0 0 var(--space-4);
  max-width: 64ch;
  text-wrap: pretty;
}

.vc-foot { display: flex; }

.vc-side {
  background: rgba(5, 5, 7, 0.4);
  border-left: var(--hairline);
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-4);
  justify-content: center;
}

.vc-side-strat {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  width: 100%;
}

.vc-side-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-micro);
  color: var(--bone-ivory-dim);
}

.verdict-card .btn:focus-visible {
  outline: 2px solid var(--cathedral-gold);
  outline-offset: 2px;
}

/* ── Mobile (< 960px) — mirrors styles.css:520-524 ──
   These live here, not in global.css: the scoped base rules compile to
   `.verdict-card[data-v-…]` (specificity 0,2,0) and would beat a plain
   global `.verdict-card` (0,1,0) even inside a media query. Co-locating
   keeps the override at matching specificity so it actually wins. */
@media (max-width: 959.98px) {
  /* Side rail drops beneath the body and reflows as a wrapping row. */
  .verdict-card { grid-template-columns: 1fr; }
  .vc-side {
    flex-direction: row;
    flex-wrap: wrap;
    border-left: none;
    border-top: var(--hairline);
    justify-content: flex-start;
    gap: var(--space-4) var(--space-5);
  }
  .vc-side-strat { width: auto; flex: 1 1 140px; }
  .vc-meta { white-space: normal; }
  /* Long mono tokens (consensus meta, contributor names) must wrap rather
     than push the card past the viewport edge. */
  .vc-strat,
  .vc-side-row .font-mono {
    overflow-wrap: anywhere;
    word-break: break-word;
  }
  .vc-teaser { font-size: var(--text-body); }
}
</style>
