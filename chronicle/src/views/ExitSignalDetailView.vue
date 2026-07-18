<template>
  <div class="content-inner prose-width exit-detail">
    <BackLink data-testid="exit-back" @click="goBack">{{ t('exitSignal.back') }}</BackLink>

    <template v-if="loading">
      <v-skeleton-loader type="heading" />
      <v-skeleton-loader type="paragraph" class="mt-4" />
    </template>

    <div v-else-if="!signal" class="empty small" data-testid="exit-notfound">
      <div class="em-text">{{ t('exitSignal.notFound') }}</div>
    </div>

    <template v-else>
      <div class="ed-head">
        <TagPill :tone="badgeTone" class="ed-action">{{ signal.action }}</TagPill>
        <div class="ed-id">
          <h1 class="ed-sym mono"><TickerButton :symbol="signal.symbol" /></h1>
        </div>
        <span class="ed-runat mono">{{ signal.runAt }}</span>
      </div>

      <SectionHeader :label="t('exitSignal.rationale')" />
      <p class="ed-rationale">{{ signal.rationale }}</p>

      <SectionHeader :label="t('exitSignal.rulesTitle')" />
      <ul class="ed-rules">
        <li v-for="r in signal.firedRules" :key="r" class="ed-rule">
          <span class="ed-rule-k mono">{{ r }}</span>
          <span class="ed-rule-v">{{ ruleText(r) }}</span>
        </li>
      </ul>

      <div class="ed-meta">
        <div v-if="signal.thesisStatus" class="ed-chip">
          <span class="ed-chip-k">{{ t('exitSignal.thesisStatus') }}</span>
          <span class="ed-chip-v mono">{{ signal.thesisStatus }}</span>
        </div>
        <div v-if="signal.confidence != null" class="ed-conf">
          <span class="ed-chip-k">{{ t('exitSignal.confidence') }}</span>
          <ConfidenceBar :score="signal.confidence" />
        </div>
      </div>

      <template v-if="position">
        <SectionHeader :label="t('exitSignal.position')" />
        <div class="ed-pos mono">
          <span>{{ t('exitSignal.posEntry') }} {{ formatMoney(position.avgEntryPrice, position.currency) }}</span>
          <span>{{ t('exitSignal.posSize') }} {{ fmt(position.qty) }}</span>
          <span>{{ t('exitSignal.posCurrent') }} {{ formatMoney(position.price ?? position.avgEntryPrice, position.currency) }}</span>
          <span class="ed-pnl" :class="pnlPct >= 0 ? 'pos' : 'neg'">{{ formatPercent(pnlPct) }}</span>
        </div>
      </template>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import BackLink from '../components/common/BackLink.vue'
import SectionHeader from '../components/common/SectionHeader.vue'
import ConfidenceBar from '../components/common/ConfidenceBar.vue'
import TagPill from '../components/common/TagPill.vue'
import TickerButton from '../components/instrument/TickerButton.vue'
import { useApi } from '../api'
import type { ExitSignal, DepotPositionView } from '../api/types'
import { formatMoney, formatNumber, formatPercent } from '../utils/format'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const api = useApi()

const loading = ref(true)
const signal = ref<ExitSignal | null>(null)
const position = ref<DepotPositionView | null>(null)

const badgeTone = computed<'gold' | 'crimson' | 'green' | 'ash'>(() => {
  switch (signal.value?.action) {
    case 'SELL': return 'crimson'
    case 'TRIM': return 'gold'
    default: return 'ash'
  }
})
const pnlPct = computed(() => {
  const p = position.value
  if (!p || p.unrealizedPlPct == null) return 0
  return p.unrealizedPlPct
})

function ruleText(r: string): string {
  const key = `exitSignal.rules.${r}`
  const txt = t(key)
  return txt === key ? r : txt
}
function fmt(n: number | null): string { return n == null ? '—' : formatNumber(n, 0) }
function goBack() { router.push({ name: 'depots' }) }

let requestId = 0

/** gropar exit signals are depot-sourced and keyed by symbol (not a watchlist-item
 *  id) — resolve the position by scanning every depot connection's positions for
 *  the signal's symbol. */
function findPositionBySymbol(depots: DepotPositionView[][], symbol: string): DepotPositionView | null {
  for (const positions of depots) {
    const found = positions.find(p => p.symbol === symbol)
    if (found) return found
  }
  return null
}

watch(() => route.params.id, async (raw) => {
  const current = ++requestId
  loading.value = true
  signal.value = null
  position.value = null
  try {
    const id = String(raw)
    const [sigs, depotsRes] = await Promise.all([api.getExitSignals(), api.getDepots()])
    if (current !== requestId) return
    const s = sigs.find(x => x.id === id) ?? null
    signal.value = s
    if (s) {
      position.value = findPositionBySymbol(depotsRes.depots.map(d => d.positions), s.symbol)
    }
  } finally {
    if (current === requestId) loading.value = false
  }
}, { immediate: true })
</script>

<style scoped>
.ed-head { display: flex; align-items: center; gap: var(--space-4); margin-bottom: var(--space-4); }
.ed-id { flex: 1 1 auto; }
.ed-sym { color: var(--bone-ivory); font-size: 1.5rem; margin: 0; }
.ed-runat { color: var(--ash-gray); font-size: var(--text-micro); }
.ed-rationale { color: var(--bone-ivory); line-height: 1.6; }
.ed-rules { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: var(--space-2); }
.ed-rule { display: flex; gap: var(--space-3); align-items: baseline; }
.ed-rule-k { color: var(--cathedral-gold); flex: 0 0 12rem; }
.ed-rule-v { color: var(--ash-gray); font-size: var(--text-body-sm); }
.ed-meta { display: flex; gap: var(--space-6); align-items: center; margin: var(--space-4) 0; flex-wrap: wrap; }
.ed-chip-k { color: var(--ash-gray); font-size: var(--text-micro); margin-right: var(--space-2); }
.ed-chip-v { color: var(--bone-ivory); }
.ed-pos { display: flex; gap: var(--space-4); color: var(--ash-gray); flex-wrap: wrap; }
.ed-pnl.pos { color: var(--cathedral-gold); }
.ed-pnl.neg { color: var(--blood-crimson); }

/* Mobile: header and rule rows wrap instead of clipping. */
@media (max-width: 959.98px) {
  .ed-head { flex-wrap: wrap; }
  .ed-rule { flex-wrap: wrap; }
  .ed-rule-k { flex: 0 0 auto; }
}
</style>
