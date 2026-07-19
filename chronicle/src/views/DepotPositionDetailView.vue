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
            :class="pnlClass(header.change)"
            @click="toggle()"
          >{{ fmtPl(header.change, header.changePct, mode, position.currency) }}</span>
        </div>
      </div>

      <InstrumentInfoPanel
        :symbol="position.symbol"
        :currency="position.currency"
        testid-prefix="pd"
        @header="onHeader"
      >
        <template #between>
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

      <!-- ── Raw transcript drilldown (heuristic symbol link) ──── -->
      <div v-if="runId" class="pd-transcript" data-testid="pd-transcript">
        <div class="pd-transcript-hint" data-testid="pd-transcript-heuristic">
          {{ t('depots.transcript.heuristic') }}
        </div>
        <RawTranscriptPanel :run-id="runId" />
      </div>
        </template>
      </InstrumentInfoPanel>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import BackLink from '../components/common/BackLink.vue'
import StatTile from '../components/common/StatTile.vue'
import MoneyDisplay from '../components/common/MoneyDisplay.vue'
import TagPill from '../components/common/TagPill.vue'
import InstrumentInfoPanel from '../components/instrument/InstrumentInfoPanel.vue'
import RawTranscriptPanel from '../components/depot/RawTranscriptPanel.vue'
import { useApi } from '../api'
import type { DepotPositionView, DepotOrderView } from '../api/types'
import { useDisplayMode } from '../composables/useDisplayMode'
import { fmtPl, isStale, formatAbsoluteTime } from '../lib/depotDisplay'
import { orderSideLabel, orderTypeLabel, orderStatusLabel } from '../lib/orderDisplay'
import { formatMoney, formatNumber, formatPercent, pctClass } from '../utils/format'
import { displayName } from '../utils/instrument'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()
const api = useApi()
const { mode, toggle } = useDisplayMode()

function goBack() { router.push({ name: 'depots' }) }

// ── Position + orders ──────────────────────────────────────────

const loading = ref(true)
const notFound = ref(false)
const brokerDown = ref(false)
const position = ref<DepotPositionView | null>(null)
const orders = ref<DepotOrderView[]>([])
const asOf = ref<string | null>(null)
const runId = ref<string | null>(null)

// Header data (company name + chart-derived change) is owned by the
// InstrumentInfoPanel and pushed up via its `header` emit.
const header = ref<{ name: string; lastPrice: number | null; change: number | null; changePct: number | null }>(
  { name: '', lastPrice: null, change: null, changePct: null },
)
function onHeader(h: { name: string; lastPrice: number | null; change: number | null; changePct: number | null }) {
  header.value = h
}

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
    runId.value = result.runId
  } catch (e) {
    if (id !== posRequestId) return
    position.value = null
    orders.value = []
    asOf.value = null
    runId.value = null
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
  return displayName(p.symbol, p.name ?? header.value.name)
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

// ── Route param changes must re-trigger the position load ───────
// The chart + info bundle are owned by InstrumentInfoPanel (it reloads on
// its own `symbol` prop watch), so only loadPosition is triggered here.

watch(() => [route.params.connection, route.params.symbol], () => {
  loadPosition()
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

.pd-transcript { margin-top: var(--space-2); }
.pd-transcript-hint { color: var(--ash-gray); font-size: var(--text-micro); margin-bottom: var(--space-1); }

@media (max-width: 600px) {
  .pd-stats { grid-template-columns: 1fr 1fr; gap: var(--space-3); }
  .pd-stats :deep(.st-value) { font-size: var(--text-body-lg); }
  .pd-symbol { font-size: 1.35rem; }
  .pd-price { font-size: 22px; }
}
</style>
