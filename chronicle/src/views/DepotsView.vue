<template>
  <div class="content-inner depots-view">
    <PageHead :title="t('depots.title')" :sub="t('depots.subtitle')">
      <template #right>
        <button class="btn btn-secondary" data-testid="depots-refresh" @click="load">
          <i class="ph ph-arrow-clockwise" aria-hidden="true" /> {{ t('depots.refresh') }}
        </button>
      </template>
    </PageHead>

    <div v-if="error" class="empty small"><div class="em-text">{{ error }}</div></div>

    <template v-else-if="loading">
      <v-skeleton-loader v-for="i in 3" :key="i" type="list-item-two-line" />
    </template>

    <div v-else-if="depots.length === 0" class="empty small" data-testid="depots-empty">
      <div class="em-text">{{ t('depots.empty') }}</div>
    </div>

    <template v-else>
      <div class="depots-summary" data-testid="depots-summary">
        <div class="ds-left">
          <span class="ds-value mono">{{ formatMoney(totals.totalValue, totals.currency) }}</span>
          <span
            class="ds-day pnl-cell"
            data-testid="pnl-cell"
            :class="pnlClass(totals.totalDayChangeAbs)"
            @click="toggle()"
          >{{ fmtPl(totals.totalDayChangeAbs, totals.totalDayChangePct, mode, totals.currency) }}</span>
        </div>
        <div class="ds-right">
          <span class="ds-cash-k">{{ t('depots.summary.cash') }}</span>
          <span class="ds-cash-v mono" data-testid="depots-total-cash">{{ formatMoney(totals.totalCash, totals.currency) }}</span>
        </div>
      </div>

      <div class="depots-list">
        <DepotSection v-for="d in depots" :key="d.id" :depot="d" />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import PageHead from '../components/common/PageHead.vue'
import DepotSection from '../components/depot/DepotSection.vue'
import { useApi } from '../api'
import type { Depot } from '../api/types'
import { useDisplayMode } from '../composables/useDisplayMode'
import { depotTotals, fmtPl } from '../lib/depotDisplay'
import { formatMoney } from '../utils/format'

const { t } = useI18n()
const api = useApi()
const { mode, toggle } = useDisplayMode()

const depots = ref<Depot[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

const totals = computed(() => depotTotals(depots.value))

function pnlClass(v: number | null): string {
  if (v == null) return ''
  return v > 0 ? 'pos' : v < 0 ? 'neg' : ''
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await api.getDepots()
    depots.value = res.depots
    if (res.error) error.value = res.error
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('depots.loadError')
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<style scoped>
.depots-summary {
  display: flex; align-items: center; justify-content: space-between; gap: var(--space-5); flex-wrap: wrap;
  background: var(--crypt-black-elevated); border: var(--hairline); border-radius: 4px;
  padding: var(--space-4) var(--space-5); margin-bottom: var(--space-5);
}
.ds-left { display: flex; align-items: baseline; gap: var(--space-4); }
.ds-value { font-size: var(--text-h3); color: var(--bone-ivory); }
.pnl-cell { cursor: pointer; }
.pnl-cell.pos { color: var(--signal-positive-bright); }
.pnl-cell.neg { color: var(--blood-crimson-bright); }
.ds-right { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
.ds-cash-k { font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.1em; color: var(--ash-gray); }
.ds-cash-v { font-size: var(--text-body-lg); color: var(--bone-ivory); }

.depots-list { display: flex; flex-direction: column; gap: var(--space-5); }
</style>
