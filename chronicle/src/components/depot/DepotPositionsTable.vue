<template>
  <div class="table-wrap">
    <table class="dt" data-testid="depot-positions-table">
      <thead>
        <tr>
          <th @click="sortBy('symbol')">{{ t('depots.table.symbol') }}</th>
          <th class="num" @click="sortBy('qty')">{{ t('depots.table.qty') }}</th>
          <th class="num" @click="sortBy('avgEntryPrice')">{{ t('depots.table.avgEntry') }}</th>
          <th class="num" @click="sortBy('price')">{{ t('depots.table.price') }}</th>
          <th class="num" @click="sortBy('marketValue')">{{ t('depots.table.marketValue') }}</th>
          <th class="num" @click="sortBy('change')">{{ metric === 'sinceBuy' ? t('depots.table.pnl') : t('depots.table.dayChange') }}</th>
          <th class="num" @click="sortBy('weightPct')">{{ t('depots.table.weight') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="p in sorted"
          :key="p.symbol"
          class="depot-position-row"
          data-testid="depot-position-row"
          @click="$emit('select', p.symbol)"
        >
          <td class="tkr">{{ p.symbol }}</td>
          <td class="num">{{ formatNumber(p.qty, Number.isInteger(p.qty) ? 0 : 4) }}</td>
          <td class="num">{{ formatMoney(p.avgEntryPrice, p.currency) }}</td>
          <td class="num">{{ p.price == null ? '—' : formatMoney(p.price, p.currency) }}</td>
          <td class="num">{{ formatMoney(p.marketValue, p.currency) }}</td>
          <td
            class="num pnl-cell"
            data-testid="change-cell"
            :class="pnlClass(changeSortValue(p))"
            @click.stop="toggle()"
          >{{ fmtPl(changeAbs(p), changePct(p), mode, p.currency) }}</td>
          <td class="num">{{ p.weightPct == null ? '—' : formatPercent(p.weightPct) }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DepotPositionView } from '../../api/types'
import type { DisplayMode } from '../../composables/useDisplayMode'
import { fmtPl, positionDayChangeAbs } from '../../lib/depotDisplay'
import { formatMoney, formatNumber, formatPercent } from '../../utils/format'

const props = defineProps<{
  positions: DepotPositionView[]
  mode: DisplayMode
  metric: 'sinceBuy' | 'today'
  toggle: () => void
}>()

defineEmits<{ select: [symbol: string] }>()

const { t } = useI18n()

type SortKey = 'symbol' | 'qty' | 'avgEntryPrice' | 'price' | 'marketValue' | 'change' | 'weightPct'
const sortKey = ref<SortKey>('weightPct')
const sortDir = ref<1 | -1>(-1)

function sortBy(key: SortKey) {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 1 ? -1 : 1
  } else {
    sortKey.value = key
    sortDir.value = -1
  }
}

/** The change column follows the selected metric: "Seit Kauf" sorts/shows
 *  unrealizedPl(Pct), "Heute" sorts/shows the day change. */
function changeSortValue(p: DepotPositionView): number | null {
  return props.metric === 'sinceBuy' ? p.unrealizedPl : p.dayChangePercent
}
function changeAbs(p: DepotPositionView): number | null {
  return props.metric === 'sinceBuy' ? p.unrealizedPl : positionDayChangeAbs(p)
}
function changePct(p: DepotPositionView): number | null {
  return props.metric === 'sinceBuy' ? p.unrealizedPlPct : p.dayChangePercent
}

const sorted = computed(() => {
  const key = sortKey.value
  const dir = sortDir.value
  return [...props.positions].sort((a, b) => {
    const av = key === 'change' ? changeSortValue(a) : a[key]
    const bv = key === 'change' ? changeSortValue(b) : b[key]
    if (av == null && bv == null) return 0
    if (av == null) return 1
    if (bv == null) return -1
    if (typeof av === 'string' || typeof bv === 'string') {
      return dir * String(av).localeCompare(String(bv))
    }
    return dir * ((av as number) - (bv as number))
  })
})

function pnlClass(v: number | null): string {
  if (v == null) return ''
  return v > 0 ? 'pos' : v < 0 ? 'neg' : ''
}
</script>

<style scoped>
.pnl-cell { cursor: pointer; }
.pnl-cell.pos { color: var(--signal-positive-bright); }
.pnl-cell.neg { color: var(--blood-crimson-bright); }
th { cursor: pointer; user-select: none; }
.depot-position-row { cursor: pointer; }
</style>
