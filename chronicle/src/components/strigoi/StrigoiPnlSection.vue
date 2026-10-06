<template>
  <section v-if="visible" class="sd-pnl" data-testid="strigoi-pnl-section">
    <div class="section-head"><span class="sh-rule" />{{ t('strigoi.pnl.title') }}</div>
    <div class="card">
      <v-skeleton-loader v-if="loading" type="list-item-two-line" />

      <div v-else-if="failed" class="empty small"><div class="em-text">{{ t('strigoi.pnl.loadError') }}</div></div>

      <template v-else-if="detail">
        <div class="sd-pnl-chips" data-testid="strigoi-pnl-chips">
          <TagPill tone="ash">{{ t('strigoi.pnl.chips.trades', { n: detail.summary.closedTrades }) }}</TagPill>
          <TagPill tone="ash">{{ t('strigoi.pnl.chips.open', { n: detail.summary.openPositions }) }}</TagPill>
          <TagPill tone="ash">{{ t('strigoi.pnl.chips.hitRate', { pct: hitRateText }) }}</TagPill>
          <TagPill :tone="signTone(detail.summary.realizedEur)">{{ t('strigoi.pnl.chips.realized', { v: formatEur(detail.summary.realizedEur) }) }}</TagPill>
          <TagPill :tone="signTone(detail.summary.unrealizedEur)">{{ t('strigoi.pnl.chips.unrealized', { v: formatEur(detail.summary.unrealizedEur) }) }}</TagPill>
          <TagPill :tone="signTone(detail.summary.totalEur)">{{ t('strigoi.pnl.chips.total', { v: formatEur(detail.summary.totalEur) }) }}<span
            v-if="detail.summary.flaggedTrades" class="pnl-flag"
            :title="t('depots.pnl.flaggedHint', { n: detail.summary.flaggedTrades })"
          > *</span></TagPill>
          <TagPill :tone="signTone(detail.summary.sumR)">{{ t('strigoi.pnl.chips.sumR', { v: formatNumber(detail.summary.sumR, 2) }) }}</TagPill>
        </div>

        <div v-if="detail.trades.length === 0" class="empty small" data-testid="strigoi-pnl-empty">
          <div class="em-text">{{ t('strigoi.pnl.empty') }}</div>
        </div>

        <div v-else class="table-scroll">
          <table class="dt">
            <thead>
              <tr>
                <th>{{ t('strigoi.pnl.cols.status') }}</th>
                <th>{{ t('strigoi.pnl.cols.symbol') }}</th>
                <th>{{ t('strigoi.pnl.cols.entryDate') }}</th>
                <th>{{ t('strigoi.pnl.cols.exitDate') }}</th>
                <th class="num">{{ t('strigoi.pnl.cols.qty') }}</th>
                <th class="num">{{ t('strigoi.pnl.cols.prices') }}</th>
                <th class="num">{{ t('strigoi.pnl.cols.result') }}</th>
                <th class="num">{{ t('strigoi.pnl.cols.r') }}</th>
                <th>{{ t('strigoi.pnl.cols.reason') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="tr in detail.trades" :key="tr.positionId" data-testid="strigoi-pnl-trade">
                <td><TagPill :tone="tr.status === 'OPEN' ? 'gold' : 'ash'">{{ t(`strigoi.pnl.status.${tr.status}`) }}</TagPill></td>
                <td class="tkr">
                  <router-link
                    v-if="tr.status === 'OPEN'"
                    :to="{ name: 'depot-position-detail', params: { connection: detail.connection, symbol: tr.symbol } }"
                  >{{ tr.symbol }}</router-link>
                  <template v-else>{{ tr.symbol }}</template>
                </td>
                <td class="mono">{{ tr.entryDate ?? '—' }}</td>
                <td class="mono">{{ tr.exitDate ?? '—' }}</td>
                <td class="num">{{ tr.qty == null ? '—' : formatNumber(tr.qty, Number.isInteger(tr.qty) ? 0 : 2) }}</td>
                <td class="num">{{ price(tr.entryPrice, tr.currency) }} / {{ price(tr.exitPrice, tr.currency) }}</td>
                <td class="num" data-col="result" :class="signClass(tradeResultEur(tr))">
                  {{ formatEur(tradeResultEur(tr)) }}<span
                    v-if="tr.flags.length" class="pnl-flag" :title="flagText(tr)"
                  > *</span>
                </td>
                <td class="num" data-col="r" :class="signClass(tr.r)">{{ tr.r == null ? '—' : formatNumber(tr.r, 2) }}</td>
                <td>{{ tr.exitReason ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="pnl-fx-note" data-testid="strigoi-pnl-fx-note">{{ t('depots.pnl.fxNote') }}</p>
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import TagPill from '../common/TagPill.vue'
import { useApi } from '../../api'
import type { StrigoiPnlDetail, StrigoiPnlTrade } from '../../api/types'
import { formatMoney, formatNumber } from '../../utils/format'
import { formatEur, signClass, signTone, tradeResultEur } from '../../utils/pnl'

const props = defineProps<{ name: string }>()

const { t } = useI18n()
const api = useApi()

const detail = ref<StrigoiPnlDetail | null>(null)
const loading = ref(true)
const failed = ref(false)
/** False when the endpoint is absent (executor disabled → null). */
const visible = ref(true)

const hitRateText = computed(() => {
  const h = detail.value?.summary.hitRate
  return h == null ? '—' : `${formatNumber(h * 100, 0)} %`
})

let requestId = 0

async function load(name: string) {
  const current = ++requestId
  loading.value = true
  failed.value = false
  visible.value = true
  detail.value = null
  try {
    const res = await api.getStrigoiPnlDetail(name)
    if (current !== requestId) return
    if (res === null) {
      visible.value = false
      return
    }
    detail.value = res
  } catch {
    if (current === requestId) failed.value = true
  } finally {
    if (current === requestId) loading.value = false
  }
}

watch(() => props.name, n => load(n), { immediate: true })

function price(v: number | null, currency: string): string {
  return v == null ? '—' : formatMoney(v, currency)
}

function flagText(tr: StrigoiPnlTrade): string {
  return tr.flags.map(f => t(`strigoi.pnl.flags.${f}`)).join(', ')
}
</script>

<style scoped>
.sd-pnl { margin-bottom: var(--space-8); }
.sd-pnl-chips { display: flex; flex-wrap: wrap; gap: var(--space-2); margin-bottom: var(--space-4); }
.pnl-flag { color: var(--ash-gray); cursor: help; }
.pnl-fx-note { margin-top: var(--space-3); font-size: var(--text-micro); color: var(--ash-gray); }
</style>
