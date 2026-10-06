<template>
  <div v-if="visible" class="strigoi-pnl" data-testid="strigoi-pnl">
    <SectionHeader :label="t('depots.pnl.title')" />

    <v-skeleton-loader v-if="loading" type="list-item-two-line" />

    <div v-else-if="failed" class="empty small"><div class="em-text">{{ t('depots.pnl.loadError') }}</div></div>

    <div v-else-if="rows.length === 0" class="empty small" data-testid="strigoi-pnl-empty">
      <div class="em-text">{{ t('depots.pnl.empty') }}</div>
    </div>

    <template v-else>
      <div class="table-scroll">
        <table class="dt">
          <thead>
            <tr>
              <th>{{ t('depots.pnl.cols.strigoi') }}</th>
              <th class="num">{{ t('depots.pnl.cols.trades') }}</th>
              <th class="num">{{ t('depots.pnl.cols.hitRate') }}</th>
              <th class="num">{{ t('depots.pnl.cols.realized') }}</th>
              <th class="num">{{ t('depots.pnl.cols.unrealized') }}</th>
              <th class="num">{{ t('depots.pnl.cols.total') }}</th>
              <th class="num">{{ t('depots.pnl.cols.sumR') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in rows" :key="r.strigoi"
              data-testid="strigoi-pnl-row" :data-strigoi="r.strigoi"
              :class="{ 'pnl-row-link': r.strigoi !== UNKNOWN }"
              @click="open(r.strigoi)"
            >
              <td class="tkr">{{ r.strigoi === UNKNOWN ? t('depots.pnl.unknown') : r.strigoi }}</td>
              <td class="num" data-col="trades">
                {{ r.closedTrades }}<span v-if="r.openPositions" class="pnl-open"> + {{ r.openPositions }} {{ t('depots.pnl.openSuffix') }}</span>
              </td>
              <td class="num" data-col="hitRate">{{ r.hitRate == null ? '—' : `${formatNumber(r.hitRate * 100, 0)} %` }}</td>
              <td class="num" data-col="realized" :class="signClass(r.realizedEur)">{{ formatEur(r.realizedEur) }}</td>
              <td class="num" data-col="unrealized" :class="signClass(r.unrealizedEur)">{{ formatEur(r.unrealizedEur) }}</td>
              <td class="num" data-col="total" :class="signClass(r.totalEur)">
                {{ formatEur(r.totalEur) }}<span
                  v-if="r.flaggedTrades" class="pnl-flag"
                  :title="t('depots.pnl.flaggedHint', { n: r.flaggedTrades })"
                > *</span>
              </td>
              <td class="num" data-col="sumR" :class="signClass(r.sumR)">{{ formatNumber(r.sumR, 2) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="pnl-fx-note" data-testid="strigoi-pnl-fx-note">{{ t('depots.pnl.fxNote') }}</p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import SectionHeader from '../common/SectionHeader.vue'
import { useApi } from '../../api'
import type { StrigoiPnlSummary } from '../../api/types'
import { formatNumber } from '../../utils/format'
import { formatEur, signClass } from '../../utils/pnl'

const props = defineProps<{ connection: string }>()

const { t } = useI18n()
const api = useApi()
const router = useRouter()

const UNKNOWN = 'unknown'
const rows = ref<StrigoiPnlSummary[]>([])
const loading = ref(true)
const failed = ref(false)
/** False when the endpoint is absent (executor disabled / connection not visible → null). */
const visible = ref(true)

let requestId = 0

async function load(connection: string) {
  const current = ++requestId
  loading.value = true
  failed.value = false
  visible.value = true
  try {
    const res = await api.getStrigoiPnl(connection)
    if (current !== requestId) return
    if (res === null) {
      visible.value = false
      return
    }
    rows.value = res.strigoi
  } catch {
    if (current === requestId) failed.value = true
  } finally {
    if (current === requestId) loading.value = false
  }
}

watch(() => props.connection, c => load(c), { immediate: true })

function open(name: string) {
  if (name === UNKNOWN) return
  router.push({ name: 'strigoi-detail', params: { name } })
}
</script>

<style scoped>
.strigoi-pnl { margin-top: var(--space-8); }
.pnl-row-link { cursor: pointer; }
.pnl-row-link:hover td { background: var(--crypt-black-elevated); }
.pnl-open { color: var(--ash-gray); font-size: var(--text-micro); }
.pnl-flag { color: var(--ash-gray); cursor: help; }
.pnl-fx-note { margin-top: var(--space-3); font-size: var(--text-micro); color: var(--ash-gray); }
</style>
