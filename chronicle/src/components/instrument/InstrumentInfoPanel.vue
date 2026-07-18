<template>
  <div class="ip-panel">
    <!-- ── Chart ──────────────────────────────────────────── -->
    <div class="pd-chart-block">
      <div class="pd-chart-ranges" role="tablist">
        <button
          v-for="r in ranges"
          :key="r.value"
          class="dp-range-btn"
          :class="{ active: range === r.value }"
          :data-testid="`${testidPrefix}-range-${r.value}`"
          @click="range = r.value"
        >{{ r.label }}</button>
      </div>
      <div v-if="chartLoading" class="dp-chart-loading">{{ t('depots.chart.loading') }}</div>
      <div v-else-if="chartError" class="dp-chart-error">{{ chartError }}</div>
      <PriceChart
        v-else-if="chartSeries.length"
        :series="chartSeries"
        :times="chartTimes"
        :value-formatter="formatChartValue"
        :baseline="chartSeries[0]?.data[0] ?? null"
        :height="180"
      />
    </div>

    <!-- Caller-provided middle content (depot: stat tiles + orders + as-of). -->
    <slot name="between" />

    <!-- ── News ───────────────────────────────────────────── -->
    <InfoCardRow v-if="newsItems.length" :title="t('depots.detail.news.title')" :testid="`${testidPrefix}-section-news`">
      <component
        :is="n.url ? 'a' : 'div'"
        v-for="(n, i) in newsItems"
        :key="i"
        class="icr-card icr-news"
        :href="n.url"
        :target="n.url ? '_blank' : undefined"
        :rel="n.url ? 'noopener noreferrer' : undefined"
      >
        <div class="icr-card-title">{{ n.headline }}</div>
        <div v-if="n.summary" class="icr-card-summary">{{ n.summary }}</div>
        <div class="icr-card-sub">{{ n.source }}<span v-if="n.publishedAt"> · {{ relativeTime(n.publishedAt) }}</span><span v-if="n.url" class="icr-news-arrow"> ↗</span></div>
      </component>
    </InfoCardRow>

    <!-- ── Ereignisse ─────────────────────────────────────── -->
    <InfoCardRow v-if="eventItems.length" :title="t('depots.detail.events.title')" :testid="`${testidPrefix}-section-events`">
      <div v-for="(ev, i) in eventItems" :key="i" class="icr-card">
        <div class="icr-card-badge mono">{{ ev.reportDate }}</div>
        <div class="icr-card-title">{{ ev.period ?? symbol }}</div>
        <div class="icr-card-sub">{{ eventDaysLabel(ev.reportDate) }}</div>
      </div>
    </InfoCardRow>

    <!-- ── Insights ───────────────────────────────────────── -->
    <InfoCardRow v-if="insightCards.length" :title="t('depots.detail.insights.title')" :testid="`${testidPrefix}-section-insights`">
      <div v-for="c in insightCards" :key="c.key" class="icr-card">
        <div class="icr-card-title">{{ c.title }}</div>
        <div class="icr-card-value mono" :class="c.valueTone">{{ c.value }}</div>
        <div v-if="c.sub" class="icr-card-sub">{{ c.sub }}</div>
        <div v-if="c.breakdown" class="icr-card-sub">{{ c.breakdown }}</div>
        <a v-if="c.sourceUrl" class="icr-card-source" :href="c.sourceUrl" target="_blank" rel="noopener noreferrer">{{ c.sourceLabel }}</a>
      </div>
    </InfoCardRow>

    <!-- ── Finanzen ───────────────────────────────────────── -->
    <InfoCardRow v-if="financeRows.length" :title="t('depots.detail.finance.title')" :testid="`${testidPrefix}-section-finance`">
      <div class="icr-card icr-finance-table">
        <div v-for="row in financeRows" :key="row.label" class="icr-finance-row">
          <span class="icr-finance-k">{{ row.label }}</span>
          <span class="icr-finance-v mono">{{ row.value }}</span>
        </div>
      </div>
    </InfoCardRow>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import PriceChart from '../common/PriceChart.vue'
import InfoCardRow from '../depot/InfoCardRow.vue'
import { useApi } from '../../api'
import type { DepotChart, InstrumentInfo, ChartRange } from '../../api/types'
import { useRelativeTime } from '../../composables/useRelativeTime'
import { formatMoney, formatNumber, formatPercent } from '../../utils/format'
import { displayName } from '../../utils/instrument'

const props = withDefaults(
  defineProps<{ symbol: string; currency?: string; testidPrefix?: string }>(),
  { testidPrefix: 'ip' },
)
const emit = defineEmits<{
  header: [{ name: string; lastPrice: number | null; change: number | null; changePct: number | null }]
}>()

const { t, locale } = useI18n()
const api = useApi()
const { relativeTime } = useRelativeTime()

// ── Timeframe chart ──────────────────────────────────────────
const ranges: { value: ChartRange; label: string }[] = [
  { value: '1d', label: '1T' }, { value: '1w', label: '1W' }, { value: '1m', label: '1M' },
  { value: '1y', label: '1J' }, { value: 'max', label: 'Max' },
]
const range = ref<ChartRange>('1m')
const chart = ref<DepotChart | null>(null)
const chartLoading = ref(false)
const chartError = ref<string | null>(null)
let chartRequestId = 0

async function loadChart() {
  const id = ++chartRequestId
  const symbol = props.symbol
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
/** Short localized date per point (e.g. "14. Mai") for the axis-tooltip header —
 *  replaces the raw point index the chart falls back to without this prop. */
const chartTimes = computed(() =>
  (chart.value?.points ?? []).map(p => new Date(p.t).toLocaleDateString(locale.value, { day: '2-digit', month: 'short' })),
)
/** The instrument chart carries no currency (it's the raw instrument price
 *  series, not a depot value) — a plain 2-decimal number, no invented symbol. */
function formatChartValue(v: number): string { return formatNumber(v, 2) }

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

// ── Instrument info bundle ───────────────────────────────────
const info = ref<InstrumentInfo | null>(null)
let infoRequestId = 0

async function loadInfo() {
  const id = ++infoRequestId
  const symbol = props.symbol
  try {
    const result = await api.getInstrumentInfo(symbol)
    if (id !== infoRequestId) return
    info.value = result
  } catch {
    // A failed info bundle must never error the panel — the sections that
    // depend on it simply stay hidden (info.value stays null).
    if (id !== infoRequestId) return
    info.value = null
  }
}

function asRecord(v: unknown): Record<string, unknown> | null { return v !== null && typeof v === 'object' ? (v as Record<string, unknown>) : null }
function asArray(v: unknown): unknown[] { return Array.isArray(v) ? v : [] }
function asNumber(v: unknown): number | null { return typeof v === 'number' && Number.isFinite(v) ? v : null }
function asString(v: unknown): string { return typeof v === 'string' ? v : '' }

const profileRecord = computed(() => asRecord(info.value?.profile))

interface NewsRow { headline: string; source: string; publishedAt?: string; url?: string; summary?: string }
const newsItems = computed<NewsRow[]>(() => {
  const rec = asRecord(info.value?.news)
  return asArray(rec?.news)
    .map(row => asRecord(row))
    .filter((row): row is Record<string, unknown> => row !== null && typeof row.headline === 'string')
    .map(row => ({
      headline: asString(row.headline),
      source: asString(row.source),
      // Agora's get_company_news emits `datetime` (ISO instant), not `publishedAt`.
      publishedAt: typeof row.datetime === 'string' ? row.datetime : undefined,
      // Only accept http(s) URLs — a javascript:/data: scheme in an href would be an
      // XSS vector, and news urls come from an external source (Finnhub).
      url: typeof row.url === 'string' && /^https?:\/\//i.test(row.url) ? asString(row.url) : undefined,
      summary: typeof row.summary === 'string' && row.summary ? asString(row.summary) : undefined,
    }))
})

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

interface InsightCard { key: string; title: string; value: string; sub?: string; valueTone?: string; breakdown?: string; sourceLabel?: string; sourceUrl?: string }
const insightCards = computed<InsightCard[]>(() => {
  const cards: InsightCard[] = []
  const analyst = asRecord(info.value?.analystEstimates)
  const recommendations = asArray(analyst?.recommendations).map(r => asRecord(r)).filter((r): r is Record<string, unknown> => r !== null)
  const latestRec = recommendations[recommendations.length - 1]
  if (latestRec) {
    const strongBuy = asNumber(latestRec.strongBuy) ?? 0
    const buyN = asNumber(latestRec.buy) ?? 0
    const hold = asNumber(latestRec.hold) ?? 0
    const sellN = asNumber(latestRec.sell) ?? 0
    const strongSell = asNumber(latestRec.strongSell) ?? 0
    const total = strongBuy + buyN + hold + sellN + strongSell
    const buyCount = strongBuy + buyN
    const sellCount = sellN + strongSell
    let rating = '—'; let valueTone = ''
    if (total > 0) {
      const score = (strongBuy * 5 + buyN * 4 + hold * 3 + sellN * 2 + strongSell * 1) / total
      if (score >= 4.5) { rating = t('depots.detail.insights.rating.strongBuy'); valueTone = 'pos' }
      else if (score >= 3.5) { rating = t('depots.detail.insights.rating.buy'); valueTone = 'pos' }
      else if (score >= 2.5) { rating = t('depots.detail.insights.rating.hold'); valueTone = '' }
      else if (score >= 1.5) { rating = t('depots.detail.insights.rating.sell'); valueTone = 'neg' }
      else { rating = t('depots.detail.insights.rating.strongSell'); valueTone = 'neg' }
    }
    const priceTarget = asNumber(analyst?.priceTarget) ?? asNumber(analyst?.averagePriceTarget)
    const symbol = props.symbol
    const symbolIsSafe = /^[A-Za-z.\-]{1,12}$/.test(symbol)
    cards.push({
      key: 'analyst',
      title: t('depots.detail.insights.analystConsensus'),
      value: rating, valueTone,
      sub: priceTarget != null
        ? `${t('depots.detail.insights.priceTarget')}: ${props.currency ? formatMoney(priceTarget, props.currency) : formatNumber(priceTarget, 2)}`
        : undefined,
      breakdown: total > 0 ? t('depots.detail.insights.analystBreakdown', { total, buy: buyCount, hold, sell: sellCount }) : undefined,
      sourceLabel: symbolIsSafe ? t('depots.detail.insights.analystSource') : undefined,
      sourceUrl: symbolIsSafe ? `https://finance.yahoo.com/quote/${symbol}/analysis` : undefined,
    })
  }
  const earningsEst = asRecord(info.value?.earningsEstimates)
  const estimates = asArray(earningsEst?.estimates).map(r => asRecord(r)).filter((r): r is Record<string, unknown> => r !== null)
  const latestEst = estimates[estimates.length - 1]
  const epsAvg = latestEst ? asNumber(latestEst.epsAvg) : null
  if (epsAvg != null) {
    const epsLow = asNumber(latestEst?.epsLow); const epsHigh = asNumber(latestEst?.epsHigh)
    cards.push({ key: 'eps', title: t('depots.detail.insights.epsEstimate'), value: formatNumber(epsAvg, 2),
      sub: epsLow != null && epsHigh != null ? `${formatNumber(epsLow, 2)} – ${formatNumber(epsHigh, 2)}` : undefined })
  }
  const score = asNumber(asRecord(info.value?.fundamentalScore)?.score)
  if (score != null) cards.push({ key: 'score', title: t('depots.detail.insights.fundamentalScore'), value: formatNumber(score, 0) })
  const insider = asRecord(info.value?.insiderActivity)
  const transactions = asArray(insider?.transactions).map(r => asRecord(r)).filter((r): r is Record<string, unknown> => r !== null)
  if (transactions.length > 0) {
    const buys = transactions.filter(tx => asString(tx.type).toLowerCase() === 'buy').length
    const sells = transactions.filter(tx => asString(tx.type).toLowerCase() === 'sell').length
    cards.push({ key: 'insider', title: t('depots.detail.insights.insiderActivity'),
      value: t('depots.detail.insights.buys', { n: buys }), sub: t('depots.detail.insights.sells', { n: sells }) })
  }
  return cards
})

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

// ── Header emit ──────────────────────────────────────────────
const headerData = computed(() => ({
  name: displayName(props.symbol, asString(profileRecord.value?.name)),
  lastPrice: chart.value?.points.length ? chart.value.points[chart.value.points.length - 1].value : null,
  change: headerChange.value.abs,
  changePct: headerChange.value.pct,
}))
watch(headerData, v => emit('header', v), { immediate: true })

// ── Symbol change drives (re)load ────────────────────────────
watch(() => props.symbol, () => { range.value = '1m'; loadChart(); loadInfo() }, { immediate: true })
</script>

<style scoped>
.ip-panel { display: flex; flex-direction: column; gap: var(--space-5); }
.pd-chart-block { display: flex; flex-direction: column; gap: var(--space-2); }
.pd-chart-ranges { display: flex; gap: var(--space-2); }

.icr-card { background: var(--crypt-black-elevated); border: var(--hairline); border-radius: 4px; padding: var(--space-3) var(--space-4); min-width: 220px; max-width: min(78vw, 300px); display: flex; flex-direction: column; gap: var(--space-1); }
.icr-card-title { color: var(--bone-ivory); font-size: var(--text-body-sm); display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; white-space: normal; line-height: 1.35; }
.icr-card-sub { color: var(--ash-gray); font-size: var(--text-micro); margin-top: auto; }
.icr-news { min-height: 138px; text-decoration: none; color: inherit; }
.icr-news:hover { border-color: var(--cathedral-gold); }
.icr-news:focus-visible { outline: 2px solid var(--cathedral-gold); outline-offset: 2px; }
.icr-card-summary { color: var(--bone-ivory-dim); font-size: var(--text-micro); line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.icr-news-arrow { color: var(--cathedral-gold); }
.icr-card-value { color: var(--cathedral-gold); font-size: var(--text-body); }
.icr-card-value.pos { color: var(--signal-positive-bright); }
.icr-card-value.neg { color: var(--blood-crimson-bright); }
.icr-card-source { color: var(--cathedral-gold); font-size: var(--text-micro); text-decoration: none; margin-top: auto; }
.icr-card-source:hover { text-decoration: underline; }
.icr-card-source:focus-visible { outline: 2px solid var(--cathedral-gold); outline-offset: 2px; }
.icr-card-badge { color: var(--cathedral-gold); font-size: var(--text-micro); }
.icr-finance-table { min-width: 240px; }
.icr-finance-row { display: flex; justify-content: space-between; padding: var(--space-1) 0; border-bottom: 1px solid var(--rule); }
.icr-finance-row:last-child { border-bottom: none; }
.icr-finance-k { color: var(--ash-gray); font-size: var(--text-body-sm); }
.icr-finance-v { color: var(--bone-ivory); font-size: var(--text-body-sm); }
</style>
