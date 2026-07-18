<template>
  <section class="depot-section" data-testid="depot-section" :data-connection="depot.id">
    <header class="dp-head">
      <div class="dp-head-left">
        <span class="dp-id mono">{{ depot.id }}</span>
        <TagPill tone="gold">{{ depot.provider }}</TagPill>
        <TagPill :tone="depot.environment === 'live' ? 'crimson' : 'ash'">
          {{ depot.environment === 'live' ? t('depots.env.live') : t('depots.env.paper') }}
        </TagPill>
        <span class="dp-status">{{ depot.status }}</span>
      </div>
      <div class="dp-head-right">
        <div v-if="depot.asOf" class="dp-asof" :class="{ stale: stale }" data-testid="depot-asof">
          {{ t('depots.asOf', { time: formatAbsoluteTime(depot.asOf) }) }}
        </div>
      </div>
    </header>

    <div v-if="depot.error" class="dp-error" data-testid="depot-error">
      <i class="ph ph-warning-octagon" aria-hidden="true" />
      <span>{{ depot.error }}</span>
    </div>

    <template v-if="depot.account">
      <div class="dp-headline">
        <span class="dp-headline-value mono">{{ formatMoney(depot.account.equity, depot.account.currency) }}</span>
        <span
          class="dp-headline-day pnl-cell"
          data-testid="pnl-cell"
          :class="pnlClass(depot.aggregates?.dayChangeAbs ?? null)"
          @click="toggle()"
        >{{ fmtPl(depot.aggregates?.dayChangeAbs ?? null, depot.aggregates?.dayChangePct ?? null, mode, depot.account.currency) }}</span>
        <span
          class="dp-headline-pnl pnl-cell"
          data-testid="pnl-cell"
          :class="pnlClass(depot.aggregates?.totalUnrealizedPl ?? null)"
          @click="toggle()"
        >{{ fmtPl(depot.aggregates?.totalUnrealizedPl ?? null, depot.aggregates?.totalUnrealizedPlPct ?? null, mode, depot.account.currency) }}</span>
      </div>

      <div class="stat-grid dp-stats">
        <StatTile :label="t('depots.stat.cash')" test-id="depots-total-cash" :value="formatMoney(depot.account.cash, depot.account.currency)" />
        <StatTile :label="t('depots.stat.invested')" :value="depot.aggregates ? formatMoney(depot.aggregates.investedValue, depot.account.currency) : '—'" />
        <StatTile :label="t('depots.stat.buyingPower')" :value="formatMoney(depot.account.buyingPower, depot.account.currency)" />
      </div>

      <div class="dp-chart-block">
        <div class="dp-chart-ranges" role="tablist">
          <button
            v-for="r in ranges"
            :key="r.value"
            class="dp-range-btn"
            :class="{ active: range === r.value }"
            :data-testid="`depot-range-${r.value}`"
            @click="range = r.value"
          >{{ r.label }}</button>
        </div>
        <div v-if="chartLoading" class="dp-chart-loading">{{ t('depots.chart.loading') }}</div>
        <div v-else-if="chartError" class="dp-chart-error">{{ chartError }}</div>
        <PriceChart
          v-else-if="chartSeries.length"
          :series="chartSeries"
          :labels="chartLabels"
          :times="chartTimes"
          :value-formatter="formatChartValue"
          :area-fill="true"
          :height="160"
        />
      </div>

      <div v-if="allocation.length" class="dp-allocation" data-testid="depot-allocation">
        <div class="dp-allocation-bar">
          <span
            v-for="seg in allocation"
            :key="seg.symbol"
            class="dp-allocation-seg"
            :style="{ width: `${seg.weightPct}%`, background: seg.color }"
            :title="`${seg.symbol}: ${seg.weightPct}%`"
          />
        </div>
      </div>

      <div class="dp-metric-row">
        <label class="dp-metric-select">
          <span>{{ t('depots.metric.label') }}</span>
          <select v-model="metric" data-testid="depot-metric-select">
            <option value="sinceBuy">{{ t('depots.metric.sinceBuy') }}</option>
            <option value="today">{{ t('depots.metric.today') }}</option>
          </select>
        </label>
      </div>

      <DepotPositionsTable
        v-if="depot.positions.length"
        :positions="depot.positions"
        :mode="mode"
        :metric="metric"
        :toggle="toggle"
        @select="onSelectPosition"
      />
      <div v-else class="empty small" data-testid="depot-positions-empty">
        <div class="em-text">{{ t('depots.positions.empty') }}</div>
      </div>

      <div v-if="depot.orders.length" class="dp-orders" data-testid="depot-orders">
        <div class="section-head">{{ t('depots.orders.title') }}</div>
        <div class="dp-order-row dp-order-head">
          <span>{{ t('depots.orders.col.symbol') }}</span>
          <span>{{ t('depots.orders.col.side') }}</span>
          <span>{{ t('depots.orders.col.qty') }}</span>
          <span>{{ t('depots.orders.col.type') }}</span>
          <span>{{ t('depots.orders.col.status') }}</span>
        </div>
        <div v-for="row in orderRows" :key="row.key" class="dp-order-row">
          <TickerButton :symbol="row.symbol" class="mono" />
          <span class="dp-order-side" :class="`tone-${row.side.tone}`">
            <span v-if="row.side.arrow" class="dp-order-arrow" aria-hidden="true">{{ row.side.arrow }}</span>{{ row.side.label }}
          </span>
          <span class="mono">{{ row.qty }}</span>
          <span class="dp-order-type">{{ row.type.label }}</span>
          <span class="dp-order-status">
            <TagPill :tone="row.status.tone">{{ row.status.label }}</TagPill>
          </span>
        </div>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import TagPill from '../common/TagPill.vue'
import StatTile from '../common/StatTile.vue'
import PriceChart from '../common/PriceChart.vue'
import DepotPositionsTable from './DepotPositionsTable.vue'
import TickerButton from '../instrument/TickerButton.vue'
import { useApi } from '../../api'
import type { Depot, DepotChart, ChartRange } from '../../api/types'
import { useDisplayMode } from '../../composables/useDisplayMode'
import { fmtPl, allocationSegments, isStale, formatAbsoluteTime } from '../../lib/depotDisplay'
import { orderSideLabel, orderTypeLabel, orderStatusLabel } from '../../lib/orderDisplay'
import { formatMoney, formatNumber } from '../../utils/format'

const props = defineProps<{ depot: Depot }>()

const { t, locale } = useI18n()
const router = useRouter()
const api = useApi()
const { mode, toggle } = useDisplayMode()

const stale = computed(() => isStale(props.depot.asOf))

function pnlClass(v: number | null): string {
  if (v == null) return ''
  return v > 0 ? 'pos' : v < 0 ? 'neg' : ''
}

const metric = ref<'sinceBuy' | 'today'>('sinceBuy')

const allocation = computed(() => allocationSegments(props.depot.positions))

const orderRows = computed(() =>
  props.depot.orders.map(o => ({
    key: o.brokerOrderId,
    symbol: o.symbol,
    qty: formatNumber(o.qty, Number.isInteger(o.qty) ? 0 : 4),
    side: orderSideLabel(o.side, t),
    type: orderTypeLabel(o.type, t),
    status: orderStatusLabel(o.status, t),
  })),
)

function onSelectPosition(symbol: string) {
  router.push({ name: 'depot-position-detail', params: { connection: props.depot.id, symbol } })
}

// ── Performance chart ────────────────────────────────────────────

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

// Guards against a stale response overwriting a newer one when the range is
// switched again before the previous request has resolved: each call captures
// its own request id, and only the still-current request may write to state.
let chartRequestId = 0

async function loadChart() {
  const requestId = ++chartRequestId
  const requestedRange = range.value
  chartLoading.value = true
  chartError.value = null
  try {
    const result = await api.getDepotChart(props.depot.id, requestedRange)
    if (requestId !== chartRequestId) return // superseded by a later range switch
    chart.value = result
  } catch (e) {
    if (requestId !== chartRequestId) return
    chartError.value = e instanceof Error ? e.message : t('depots.chart.error')
  } finally {
    if (requestId === chartRequestId) chartLoading.value = false
  }
}

watch(range, loadChart)
onMounted(loadChart)

const chartSeries = computed(() => {
  if (!chart.value) return []
  const data = mode.value === 'pct' && chart.value.relative
    ? chart.value.relative.map(r => r.pct)
    : chart.value.points.map(p => p.value)
  return [{ data, color: 'var(--cathedral-gold)', fill: 'rgba(184,148,92,0.12)' }]
})

const chartLabels = computed(() => {
  if (!chart.value) return []
  const points = chart.value.points
  if (points.length === 0) return []
  const idxs = points.length > 1 ? [0, points.length - 1] : [0]
  return idxs.map(i => ({ i, t: points[i].t.slice(5) }))
})

/** Full per-point date array — supersedes chartLabels for the axis-tooltip
 *  header (the sparse labels above still drive the visible axis ticks). */
const chartTimes = computed(() =>
  (chart.value?.points ?? []).map(p => new Date(p.t).toLocaleDateString(locale.value, { day: '2-digit', month: 'short' })),
)

function formatChartValue(v: number): string {
  return mode.value === 'pct' && props.depot
    ? `${formatNumber(v, 2)}%`
    : formatMoney(v, props.depot.account?.currency ?? 'USD')
}
</script>

<style scoped>
.depot-section {
  background: var(--crypt-black-elevated);
  border: var(--hairline);
  border-radius: 4px;
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.dp-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); flex-wrap: wrap; }
.dp-head-left { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
.dp-id { color: var(--bone-ivory); font-size: var(--text-body); }
.dp-status { font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.08em; color: var(--ash-gray-light); }
.dp-head-right { display: flex; align-items: center; gap: var(--space-4); }
.dp-asof { font-size: var(--text-micro); color: var(--ash-gray); }
.dp-asof.stale { color: var(--cathedral-gold); }

.dp-error {
  display: flex; align-items: center; gap: var(--space-2);
  border-left: 2px solid var(--blood-crimson);
  background: rgba(161,29,44,0.08);
  padding: var(--space-3) var(--space-4);
  color: var(--blood-crimson-bright);
  font-size: var(--text-body-sm);
  border-radius: 0 4px 4px 0;
}

.dp-headline { display: flex; align-items: baseline; gap: var(--space-4); flex-wrap: wrap; }
.dp-headline-value { font-size: var(--text-h3); color: var(--bone-ivory); }
.pnl-cell { cursor: pointer; font-size: var(--text-body); }
.pnl-cell.pos { color: var(--signal-positive-bright); }
.pnl-cell.neg { color: var(--blood-crimson-bright); }

.dp-chart-ranges { display: flex; gap: var(--space-2); margin-bottom: var(--space-3); }
.dp-range-btn {
  background: transparent; border: 1px solid var(--ash-gray); color: var(--ash-gray-light);
  font-size: var(--text-micro); padding: 4px 10px; border-radius: 3px; cursor: pointer;
}
.dp-range-btn.active { border-color: var(--cathedral-gold); color: var(--cathedral-gold); }
.dp-chart-loading, .dp-chart-error { color: var(--ash-gray); font-size: var(--text-body-sm); padding: var(--space-4) 0; }

.dp-allocation-bar {
  display: flex; width: 100%; height: 10px; border-radius: 4px; overflow: hidden;
  background: var(--surface-2);
}
.dp-allocation-seg { height: 100%; }

.dp-metric-row { display: flex; justify-content: flex-end; }
.dp-metric-select { display: flex; align-items: center; gap: var(--space-2); font-size: var(--text-micro); color: var(--ash-gray); }
.dp-metric-select select {
  background: var(--crypt-black); border: 1px solid var(--ash-gray); color: var(--bone-ivory);
  border-radius: 3px; padding: 4px 8px; font-size: var(--text-body-sm);
}

.dp-orders { display: flex; flex-direction: column; gap: var(--space-2); }
.dp-order-row {
  display: grid; grid-template-columns: 1fr 1fr 1fr 1fr 1fr; gap: var(--space-2);
  align-items: center;
  font-size: var(--text-body-sm); color: var(--bone-ivory-dim); padding: var(--space-2) 0;
  border-bottom: 1px solid var(--rule);
}
.dp-order-head {
  font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.08em;
  color: var(--ash-gray); border-bottom: 1px solid var(--rule);
}
.dp-order-side { display: inline-flex; align-items: center; gap: 4px; }
.dp-order-side.tone-green { color: var(--signal-positive-bright); }
.dp-order-side.tone-crimson { color: var(--blood-crimson-bright); }
.dp-order-side.tone-ash { color: var(--ash-gray-light); }
.dp-order-arrow { font-size: var(--text-micro); }

@media (max-width: 600px) {
  .dp-stats { grid-template-columns: 1fr 1fr; gap: var(--space-3); }
  .dp-stats :deep(.st-value) { font-size: var(--text-body-lg); }
  .dp-headline-value { font-size: 22px; }
}
</style>
