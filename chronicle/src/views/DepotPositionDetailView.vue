<template>
  <div class="content-inner pd-view">
    <BackLink data-testid="pd-back" @click="goBack">{{ t('depots.detail.back') }}</BackLink>

    <template v-if="loading">
      <v-skeleton-loader type="heading" />
      <v-skeleton-loader type="paragraph" class="mt-4" />
    </template>

    <div v-else-if="notFound" class="empty small" data-testid="pd-notfound">
      <div class="em-text">{{ t('depots.detail.notFound') }}</div>
    </div>

    <div v-else-if="brokerDown" class="empty small" data-testid="pd-brokerdown">
      <div class="em-text">{{ t('depots.detail.brokerDown') }}</div>
      <button class="btn btn-secondary" data-testid="pd-retry" @click="loadPosition">
        {{ t('depots.detail.retry') }}
      </button>
    </div>

    <template v-else-if="position">
      <!-- ── Header ─────────────────────────────────────────── -->
      <div class="pd-head">
        <div class="pd-head-id">
          <h1 class="pd-symbol mono" data-testid="pd-symbol">{{ position.symbol }}</h1>
          <div v-if="companyName" class="pd-name">{{ companyName }}</div>
          <div v-if="position.assetType || position.valueDate" class="pd-meta">
            <span
              v-if="position.assetType"
              class="pd-pill"
              data-testid="pd-assettype"
              :title="t('depots.detail.assetClass')"
            >{{ position.assetType }}</span>
            <span v-if="position.valueDate" class="pd-heldsince" data-testid="pd-heldsince">
              {{ t('depots.detail.heldSince', { date: formatValueDate(position.valueDate) }) }}
            </span>
          </div>
        </div>
        <div class="pd-head-price">
          <span class="pd-price mono" data-testid="pd-price">
            <MoneyDisplay
              :amount="position.price ?? position.avgEntryPrice"
              :currency="position.currency"
              :native-amount="position.nativePrice"
              :native-currency="position.nativeCurrency"
            />
          </span>
          <span
            class="pd-header-change pnl-cell"
            data-testid="pd-header-change"
            :class="pnlClass(headerChange.abs)"
            @click="toggle()"
          >{{ fmtPl(headerChange.abs, headerChange.pct, mode, position.currency) }}</span>
        </div>
      </div>

      <!-- ── Chart ──────────────────────────────────────────── -->
      <div class="pd-chart-block">
        <div class="pd-chart-ranges" role="tablist">
          <button
            v-for="r in ranges"
            :key="r.value"
            class="dp-range-btn"
            :class="{ active: range === r.value }"
            :data-testid="`pd-range-${r.value}`"
            @click="range = r.value"
          >{{ r.label }}</button>
        </div>
        <div v-if="chartLoading" class="dp-chart-loading">{{ t('depots.chart.loading') }}</div>
        <div v-else-if="chartError" class="dp-chart-error">{{ chartError }}</div>
        <PriceChart
          v-else-if="chartSeries.length"
          :series="chartSeries"
          :baseline="chartSeries[0]?.data[0] ?? null"
          :height="180"
        />
      </div>

      <!-- ── Stat tiles ─────────────────────────────────────── -->
      <div class="stat-grid pd-stats">
        <StatTile data-testid="pd-stat-position" :label="t('depots.detail.stat.position')" :value="formatMoney(position.marketValue, position.currency)" />
        <StatTile
          data-testid="pd-stat-performance"
          :label="t('depots.detail.stat.performance')"
          :value="fmtPl(position.unrealizedPl, position.unrealizedPlPct, mode, position.currency)"
          :value-class="pnlClass(position.unrealizedPl)"
        />
        <StatTile data-testid="pd-stat-qty" :label="t('depots.detail.stat.qty')" :value="formatNumber(position.qty, Number.isInteger(position.qty) ? 0 : 4)" />
        <StatTile data-testid="pd-stat-entry" :label="t('depots.detail.stat.entry')" :value="formatMoney(position.avgEntryPrice, position.currency)" />
        <StatTile
          data-testid="pd-stat-weight"
          :label="t('depots.detail.stat.weight')"
          :value="position.weightPct != null ? formatPercent(position.weightPct) : '—'"
        />
        <StatTile
          data-testid="pd-stat-today"
          :label="t('depots.detail.stat.today')"
          :value="position.dayChangePercent != null ? formatPercent(position.dayChangePercent) : '—'"
          :value-class="pctClass(position.dayChangePercent)"
        />
      </div>
      <div v-if="asOf" class="pd-asof" :class="{ stale: stale }" data-testid="pd-asof">
        {{ t('depots.asOf', { time: formatAbsoluteTime(asOf) }) }}
      </div>

      <!-- ── Open orders ────────────────────────────────────── -->
      <div v-if="orders.length" class="dp-orders" data-testid="pd-orders">
        <div class="section-head">{{ t('depots.detail.orders') }}</div>
        <div class="pd-order-row pd-order-head">
          <span>{{ t('depots.orders.col.side') }}</span>
          <span>{{ t('depots.orders.col.qty') }}</span>
          <span>{{ t('depots.orders.col.type') }}</span>
          <span>{{ t('depots.orders.col.status') }}</span>
          <span>{{ t('depots.orders.col.role') }}</span>
        </div>
        <div v-for="row in orderRows" :key="row.key" class="pd-order-row">
          <span class="dp-order-side" :class="`tone-${row.side.tone}`">
            <span v-if="row.side.arrow" class="dp-order-arrow" aria-hidden="true">{{ row.side.arrow }}</span>{{ row.side.label }}
          </span>
          <span class="mono">{{ row.qty }}</span>
          <span class="dp-order-type">{{ row.type.label }}</span>
          <span class="dp-order-status">
            <TagPill :tone="row.status.tone">{{ row.status.label }}</TagPill>
          </span>
          <span class="dp-order-role">{{ row.role }}</span>
        </div>
      </div>

      <!-- ── News ───────────────────────────────────────────── -->
      <InfoCardRow v-if="newsItems.length" :title="t('depots.detail.news.title')" testid="pd-section-news">
        <div v-for="(n, i) in newsItems" :key="i" class="icr-card">
          <div class="icr-card-title">{{ n.headline }}</div>
          <div class="icr-card-sub">{{ n.source }}<span v-if="n.publishedAt"> · {{ relativeTime(n.publishedAt) }}</span></div>
        </div>
      </InfoCardRow>

      <!-- ── Ereignisse ─────────────────────────────────────── -->
      <InfoCardRow v-if="eventItems.length" :title="t('depots.detail.events.title')" testid="pd-section-events">
        <div v-for="(ev, i) in eventItems" :key="i" class="icr-card">
          <div class="icr-card-badge mono">{{ ev.reportDate }}</div>
          <div class="icr-card-title">{{ ev.period ?? position.symbol }}</div>
          <div class="icr-card-sub">{{ eventDaysLabel(ev.reportDate) }}</div>
        </div>
      </InfoCardRow>

      <!-- ── Insights ───────────────────────────────────────── -->
      <InfoCardRow v-if="insightCards.length" :title="t('depots.detail.insights.title')" testid="pd-section-insights">
        <div v-for="c in insightCards" :key="c.key" class="icr-card">
          <div class="icr-card-title">{{ c.title }}</div>
          <div class="icr-card-value mono">{{ c.value }}</div>
          <div v-if="c.sub" class="icr-card-sub">{{ c.sub }}</div>
        </div>
      </InfoCardRow>

      <!-- ── Finanzen ───────────────────────────────────────── -->
      <InfoCardRow v-if="financeRows.length" :title="t('depots.detail.finance.title')" testid="pd-section-finance">
        <div class="icr-card icr-finance-table">
          <div v-for="row in financeRows" :key="row.label" class="icr-finance-row">
            <span class="icr-finance-k">{{ row.label }}</span>
            <span class="icr-finance-v mono">{{ row.value }}</span>
          </div>
        </div>
      </InfoCardRow>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import BackLink from '../components/common/BackLink.vue'
import StatTile from '../components/common/StatTile.vue'
import PriceChart from '../components/common/PriceChart.vue'
import MoneyDisplay from '../components/common/MoneyDisplay.vue'
import TagPill from '../components/common/TagPill.vue'
import InfoCardRow from '../components/depot/InfoCardRow.vue'
import { useApi } from '../api'
import type {
  DepotPositionView, DepotOrderView, DepotChart, InstrumentInfo, ChartRange,
} from '../api/types'
import { useDisplayMode } from '../composables/useDisplayMode'
import { useRelativeTime } from '../composables/useRelativeTime'
import { fmtPl, isStale, formatAbsoluteTime } from '../lib/depotDisplay'
import { orderSideLabel, orderTypeLabel, orderStatusLabel } from '../lib/orderDisplay'
import { formatMoney, formatNumber, formatPercent, pctClass } from '../utils/format'
import { displayName } from '../utils/instrument'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()
const api = useApi()
const { mode, toggle } = useDisplayMode()
const { relativeTime } = useRelativeTime()

function goBack() { router.push({ name: 'depots' }) }

// ── Position + orders ──────────────────────────────────────────

const loading = ref(true)
const notFound = ref(false)
const brokerDown = ref(false)
const position = ref<DepotPositionView | null>(null)
const orders = ref<DepotOrderView[]>([])
const asOf = ref<string | null>(null)

const stale = computed(() => isStale(asOf.value))

function pnlClass(v: number | null): string {
  if (v == null) return ''
  return v > 0 ? 'pos' : v < 0 ? 'neg' : ''
}

let posRequestId = 0

async function loadPosition() {
  const id = ++posRequestId
  const connection = String(route.params.connection)
  const symbol = String(route.params.symbol)
  loading.value = true
  notFound.value = false
  brokerDown.value = false
  try {
    const result = await api.getDepotPosition(connection, symbol)
    if (id !== posRequestId) return
    position.value = result.position
    orders.value = result.orders
    asOf.value = result.asOf
  } catch (e) {
    if (id !== posRequestId) return
    position.value = null
    orders.value = []
    asOf.value = null
    const msg = e instanceof Error ? e.message : String(e)
    if (msg.includes('not found')) {
      notFound.value = true
    } else {
      brokerDown.value = true
    }
  } finally {
    if (id === posRequestId) loading.value = false
  }
}

const companyName = computed(() => {
  const p = position.value
  if (!p) return ''
  if (p.name) return p.name
  const profile = profileRecord.value
  const name = typeof profile?.name === 'string' ? profile.name : ''
  return displayName(p.symbol, name)
})

/** "Gehalten seit" date, day/month/year only — no time-of-day (unlike
 *  formatAbsoluteTime, which is for the probe timestamp, not this). */
function formatValueDate(isoDate: string): string {
  return new Date(isoDate).toLocaleDateString(locale.value, { day: 'numeric', month: 'numeric', year: 'numeric' })
}

const ORDER_ROLE_KEYS: Record<string, string> = { entry: 'entry', stop: 'stop', target: 'target', other: 'other' }

/** Known roles map to an i18n label; unknown roles are prettified
 *  (Title-cased) rather than shown raw or hidden. */
function orderRoleLabel(role: string | null): string {
  if (!role) return ''
  const key = ORDER_ROLE_KEYS[role.toLowerCase()]
  if (key) return t(`depots.orders.role.${key}`)
  return role.charAt(0).toUpperCase() + role.slice(1).toLowerCase()
}

const orderRows = computed(() =>
  orders.value.map(o => ({
    key: o.brokerOrderId,
    qty: formatNumber(o.qty, Number.isInteger(o.qty) ? 0 : 4),
    side: orderSideLabel(o.side, t),
    type: orderTypeLabel(o.type, t),
    status: orderStatusLabel(o.status, t),
    role: orderRoleLabel(o.role),
  })),
)

// ── Timeframe chart ─────────────────────────────────────────────

const ranges: { value: ChartRange; label: string }[] = [
  { value: '1d', label: '1T' },
  { value: '1w', label: '1W' },
  { value: '1m', label: '1M' },
  { value: '1y', label: '1J' },
  { value: 'max', label: 'Max' },
]

const range = ref<ChartRange>('1m')
const chart = ref<DepotChart | null>(null)
const chartLoading = ref(false)
const chartError = ref<string | null>(null)

let chartRequestId = 0

async function loadChart() {
  const id = ++chartRequestId
  const symbol = String(route.params.symbol)
  const requestedRange = range.value
  chartLoading.value = true
  chartError.value = null
  try {
    const result = await api.getInstrumentChart(symbol, requestedRange)
    if (id !== chartRequestId) return
    chart.value = result
  } catch (e) {
    if (id !== chartRequestId) return
    chart.value = null
    chartError.value = e instanceof Error ? e.message : t('depots.chart.error')
  } finally {
    if (id === chartRequestId) chartLoading.value = false
  }
}

const chartSeries = computed(() => {
  if (!chart.value || chart.value.points.length === 0) return []
  return [{ data: chart.value.points.map(p => p.value), color: 'var(--cathedral-gold)' }]
})

const headerChange = computed<{ abs: number | null; pct: number | null }>(() => {
  const pts = chart.value?.points ?? []
  if (pts.length < 2) return { abs: null, pct: null }
  const first = pts[0].value
  const last = pts[pts.length - 1].value
  const abs = Math.round((last - first) * 100) / 100
  const pct = first !== 0 ? Math.round(((last - first) / first) * 10000) / 100 : null
  return { abs, pct }
})

watch(range, loadChart)

// ── Instrument info bundle ──────────────────────────────────────

const info = ref<InstrumentInfo | null>(null)
let infoRequestId = 0

async function loadInfo() {
  const id = ++infoRequestId
  const symbol = String(route.params.symbol)
  try {
    const result = await api.getInstrumentInfo(symbol)
    if (id !== infoRequestId) return
    info.value = result
  } catch {
    // A failed info bundle must never error the page — the sections that
    // depend on it simply stay hidden (info.value stays null).
    if (id !== infoRequestId) return
    info.value = null
  }
}

function asRecord(v: unknown): Record<string, unknown> | null {
  return v !== null && typeof v === 'object' ? (v as Record<string, unknown>) : null
}
function asArray(v: unknown): unknown[] {
  return Array.isArray(v) ? v : []
}
function asNumber(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}
function asString(v: unknown): string {
  return typeof v === 'string' ? v : ''
}

// Profile record — used only as the companyName fallback (position.name,
// Saxo-native, is preferred; see `companyName` above). Finnhub's profile2
// carries no usable description, so no "Informationen" section exists here.
const profileRecord = computed(() => asRecord(info.value?.profile))

// News
interface NewsRow { headline: string; source: string; publishedAt?: string }
const newsItems = computed<NewsRow[]>(() => {
  const rec = asRecord(info.value?.news)
  return asArray(rec?.news)
    .map(row => asRecord(row))
    .filter((row): row is Record<string, unknown> => row !== null && typeof row.headline === 'string')
    .map(row => ({ headline: asString(row.headline), source: asString(row.source), publishedAt: typeof row.publishedAt === 'string' ? row.publishedAt : undefined }))
})

// Ereignisse (earnings rows, already server-filtered to this symbol)
interface EarningsRow { period?: string; reportDate: string }
const eventItems = computed<EarningsRow[]>(() => {
  const rec = asRecord(info.value?.earnings)
  return asArray(rec?.earnings)
    .map(row => asRecord(row))
    .filter((row): row is Record<string, unknown> => row !== null && typeof row.reportDate === 'string')
    .map(row => ({ period: typeof row.period === 'string' ? row.period : undefined, reportDate: asString(row.reportDate) }))
})

function eventDaysLabel(reportDate: string): string {
  const days = Math.ceil((new Date(reportDate).getTime() - Date.now()) / 86_400_000)
  if (days === 0) return t('depots.detail.events.today')
  if (days < 0) return reportDate
  return t('depots.detail.events.inDays', { n: days })
}

// Insights (analyst consensus + price target, EPS estimate, fundamental
// score, insider activity) — each is its own card and only appears when its
// source section has usable data.
interface InsightCard { key: string; title: string; value: string; sub?: string }
const insightCards = computed<InsightCard[]>(() => {
  const cards: InsightCard[] = []

  const analyst = asRecord(info.value?.analystEstimates)
  const recommendations = asArray(analyst?.recommendations).map(r => asRecord(r)).filter((r): r is Record<string, unknown> => r !== null)
  const latestRec = recommendations[recommendations.length - 1]
  if (latestRec) {
    const buy = (asNumber(latestRec.buy) ?? 0) + (asNumber(latestRec.strongBuy) ?? 0)
    const hold = asNumber(latestRec.hold) ?? 0
    const sell = (asNumber(latestRec.sell) ?? 0) + (asNumber(latestRec.strongSell) ?? 0)
    const total = buy + hold + sell
    const consensus = total > 0 && buy >= hold && buy >= sell
      ? 'Buy' : total > 0 && sell >= buy && sell >= hold ? 'Sell' : total > 0 ? 'Hold' : '—'
    const priceTarget = asNumber(analyst?.priceTarget) ?? asNumber(analyst?.averagePriceTarget)
    cards.push({
      key: 'analyst',
      title: t('depots.detail.insights.analystConsensus'),
      value: consensus,
      sub: priceTarget != null ? `${t('depots.detail.insights.priceTarget')}: ${formatMoney(priceTarget, position.value?.currency ?? 'USD')}` : undefined,
    })
  }

  const earningsEst = asRecord(info.value?.earningsEstimates)
  const estimates = asArray(earningsEst?.estimates).map(r => asRecord(r)).filter((r): r is Record<string, unknown> => r !== null)
  const latestEst = estimates[estimates.length - 1]
  const epsAvg = latestEst ? asNumber(latestEst.epsAvg) : null
  if (epsAvg != null) {
    const epsLow = asNumber(latestEst?.epsLow)
    const epsHigh = asNumber(latestEst?.epsHigh)
    cards.push({
      key: 'eps',
      title: t('depots.detail.insights.epsEstimate'),
      value: formatNumber(epsAvg, 2),
      sub: epsLow != null && epsHigh != null ? `${formatNumber(epsLow, 2)} – ${formatNumber(epsHigh, 2)}` : undefined,
    })
  }

  const score = asNumber(asRecord(info.value?.fundamentalScore)?.score)
  if (score != null) {
    cards.push({ key: 'score', title: t('depots.detail.insights.fundamentalScore'), value: formatNumber(score, 0) })
  }

  const insider = asRecord(info.value?.insiderActivity)
  const transactions = asArray(insider?.transactions).map(r => asRecord(r)).filter((r): r is Record<string, unknown> => r !== null)
  if (transactions.length > 0) {
    const buys = transactions.filter(tx => asString(tx.type).toLowerCase() === 'buy').length
    const sells = transactions.filter(tx => asString(tx.type).toLowerCase() === 'sell').length
    cards.push({
      key: 'insider',
      title: t('depots.detail.insights.insiderActivity'),
      value: t('depots.detail.insights.buys', { n: buys }),
      sub: t('depots.detail.insights.sells', { n: sells }),
    })
  }

  return cards
})

// Finanzen (mini table from fundamentals — only the fields actually present)
interface FinanceRow { label: string; value: string }
const financeRows = computed<FinanceRow[]>(() => {
  const fundamentals = asRecord(info.value?.fundamentals)
  if (!fundamentals) return []
  const rows: FinanceRow[] = []
  const peRatio = asNumber(fundamentals.peRatio)
  if (peRatio != null) rows.push({ label: t('depots.detail.finance.peRatio'), value: formatNumber(peRatio, 1) })
  const pbRatio = asNumber(fundamentals.pbRatio)
  if (pbRatio != null) rows.push({ label: t('depots.detail.finance.pbRatio'), value: formatNumber(pbRatio, 1) })
  const dividendYield = asNumber(fundamentals.dividendYield)
  if (dividendYield != null) rows.push({ label: t('depots.detail.finance.dividendYield'), value: formatPercent(dividendYield * 100) })
  return rows
})

// ── Route param changes must re-trigger every load ──────────────

watch(() => [route.params.connection, route.params.symbol], () => {
  loadPosition()
  loadChart()
  loadInfo()
}, { immediate: true })
</script>

<style scoped>
.pd-view { display: flex; flex-direction: column; gap: var(--space-5); }

.pd-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-4); flex-wrap: wrap; }
.pd-symbol { color: var(--bone-ivory); font-size: 1.75rem; margin: 0; }
.pd-name { color: var(--ash-gray); font-size: var(--text-body-sm); }
.pd-meta { display: flex; align-items: center; gap: var(--space-2); margin-top: var(--space-1); }
.pd-pill {
  background: var(--crypt-black-elevated); border: var(--hairline); border-radius: 999px;
  padding: 2px var(--space-2); color: var(--ash-gray-light); font-size: var(--text-micro);
}
.pd-heldsince { color: var(--ash-gray); font-size: var(--text-micro); }
.pd-head-price { display: flex; flex-direction: column; align-items: flex-end; gap: var(--space-1); }
.pd-price { color: var(--bone-ivory); font-size: var(--text-h3); }
.pd-header-change { cursor: pointer; font-size: var(--text-body); }
.pd-header-change.pos { color: var(--signal-positive-bright); }
.pd-header-change.neg { color: var(--blood-crimson-bright); }

.pd-chart-block { display: flex; flex-direction: column; gap: var(--space-2); }
.pd-chart-ranges { display: flex; gap: var(--space-2); }

.pd-stats { margin-top: var(--space-2); }
.pd-asof { font-size: var(--text-micro); color: var(--ash-gray); }
.pd-asof.stale { color: var(--cathedral-gold); }

.dp-orders { display: flex; flex-direction: column; gap: var(--space-2); }
.pd-order-row {
  display: grid; grid-template-columns: 1fr 1fr 1fr 1fr 1fr; gap: var(--space-2);
  align-items: center;
  font-size: var(--text-body-sm); color: var(--bone-ivory-dim); padding: var(--space-2) 0;
  border-bottom: 1px solid var(--rule);
}
.pd-order-head {
  font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.08em;
  color: var(--ash-gray); border-bottom: 1px solid var(--rule);
}
.dp-order-side { display: inline-flex; align-items: center; gap: 4px; }
.dp-order-side.tone-green { color: var(--signal-positive-bright); }
.dp-order-side.tone-crimson { color: var(--blood-crimson-bright); }
.dp-order-side.tone-ash { color: var(--ash-gray-light); }
.dp-order-arrow { font-size: var(--text-micro); }
.dp-order-role { color: var(--cathedral-gold); font-size: var(--text-body-sm); }

.icr-card {
  background: var(--crypt-black-elevated); border: var(--hairline); border-radius: 4px;
  padding: var(--space-3) var(--space-4); min-width: 220px; max-width: min(78vw, 300px);
  display: flex; flex-direction: column; gap: var(--space-1);
}
.icr-card-title {
  color: var(--bone-ivory); font-size: var(--text-body-sm);
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  overflow: hidden; white-space: normal; line-height: 1.35;
}
.icr-card-sub { color: var(--ash-gray); font-size: var(--text-micro); }
.icr-card-value { color: var(--cathedral-gold); font-size: var(--text-body); }
.icr-card-badge { color: var(--cathedral-gold); font-size: var(--text-micro); }

.icr-finance-table { min-width: 240px; }
.icr-finance-row { display: flex; justify-content: space-between; padding: var(--space-1) 0; border-bottom: 1px solid var(--rule); }
.icr-finance-row:last-child { border-bottom: none; }
.icr-finance-k { color: var(--ash-gray); font-size: var(--text-body-sm); }
.icr-finance-v { color: var(--bone-ivory); font-size: var(--text-body-sm); }

@media (max-width: 600px) {
  .pd-stats { grid-template-columns: 1fr 1fr; gap: var(--space-3); }
  .pd-stats :deep(.st-value) { font-size: var(--text-body-lg); }
  .pd-symbol { font-size: 1.35rem; }
  .pd-price { font-size: 22px; }
}
</style>
