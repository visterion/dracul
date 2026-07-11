<template>
  <div class="content-inner portfolio-view">
    <PageHead :title="t('portfolio.title')" :sub="t('portfolio.subtitle')" />

    <div v-if="!loading && !error && positions.length" class="pf-summary" data-testid="pf-summary">
      <div class="pf-sum">
        <span class="pf-sum-k">{{ t('portfolio.summary.totalValue') }}</span>
        <span class="pf-sum-v mono">{{ formatMoney(summary.totalValue, summaryCurrency) }}</span>
      </div>
      <div class="pf-sum">
        <span class="pf-sum-k">{{ t('portfolio.summary.pnl') }}</span>
        <span class="pf-sum-v mono" :class="summary.totalPnl >= 0 ? 'pos' : 'neg'">
          {{ formatMoney(summary.totalPnl, summaryCurrency) }}<template v-if="summary.totalPnlPct !== null"> ({{ formatPercent(summary.totalPnlPct) }})</template>
        </span>
      </div>
      <div class="pf-sum">
        <span class="pf-sum-k">{{ t('portfolio.summary.count') }}</span>
        <span class="pf-sum-v mono">{{ summary.count }}</span>
      </div>
    </div>

    <div class="pf-toolbar">
      <button class="btn btn-crimson-ghost" data-testid="pf-open-add" @click="openAdd">{{ t('portfolio.addButton') }}</button>
    </div>

    <div v-if="error" class="empty small"><div class="em-text">{{ error }}</div></div>

    <template v-else-if="loading">
      <v-skeleton-loader v-for="i in 4" :key="i" type="list-item-two-line" />
    </template>

    <div v-else-if="positions.length === 0" class="empty small" data-testid="portfolio-empty">
      <div class="em-text">{{ t('portfolio.empty') }}</div>
    </div>

    <div v-else class="pf-rows" data-testid="portfolio-list">
      <PositionRow
        v-for="p in positions" :key="p.id" :item="p" :signal="signalFor(p)"
        @open="goToSignal" @edit="openEdit" @delete="onDelete"
      />
    </div>

    <PositionDialog
      v-model="dialogOpen" :mode="dialogMode"
      :initial-symbol="dialogSymbol" :initial-entry="dialogEntry" :initial-size="dialogSize"
      :error="dialogError" :submitting="submitting"
      @submit="onSubmit"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import PageHead from '../components/common/PageHead.vue'
import PositionRow from '../components/portfolio/PositionRow.vue'
import PositionDialog from '../components/portfolio/PositionDialog.vue'
import { useApi } from '../api'
import type { WatchlistItem, ExitSignal } from '../api/types'
import { portfolioSummary } from '../lib/portfolioDisplay'
import { formatMoney, formatPercent } from '../utils/format'

const { t } = useI18n()
const api = useApi()
const router = useRouter()

const items = ref<WatchlistItem[]>([])
const signals = ref<ExitSignal[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

const positions = computed(() => items.value.filter(i => i.entryPrice != null && i.shareCount != null))
const summary = computed(() => portfolioSummary(positions.value))
const summaryCurrency = computed(() => positions.value[0]?.currency ?? 'EUR')

function signalFor(item: WatchlistItem): ExitSignal | null {
  return signals.value.find(s => s.watchlistItemId === item.id)
    ?? signals.value.find(s => s.symbol === item.ticker)
    ?? null
}

async function load() {
  loading.value = true; error.value = null
  try {
    const [its, sigs] = await Promise.all([api.getPortfolio(), api.getExitSignals()])
    items.value = its
    signals.value = sigs
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('portfolio.loadError')
  } finally {
    loading.value = false
  }
}
onMounted(load)

function goToSignal(id: string) { router.push({ name: 'exit-signal-detail', params: { id } }) }

const dialogOpen = ref(false)
const dialogMode = ref<'add' | 'edit'>('add')
const dialogSymbol = ref('')
const dialogEntry = ref<number | null>(null)
const dialogSize = ref<number | null>(null)
const dialogError = ref<string | null>(null)
const submitting = ref(false)
let editId: string | null = null

function openAdd() {
  dialogMode.value = 'add'; dialogSymbol.value = ''; dialogEntry.value = null; dialogSize.value = null
  dialogError.value = null; editId = null; dialogOpen.value = true
}
function openEdit(item: WatchlistItem) {
  dialogMode.value = 'edit'; dialogSymbol.value = item.ticker
  dialogEntry.value = item.entryPrice; dialogSize.value = item.shareCount
  dialogError.value = null; editId = item.id; dialogOpen.value = true
}

async function onSubmit(payload: { symbol: string; entryPrice: number; shareCount: number }) {
  submitting.value = true; dialogError.value = null
  try {
    if (dialogMode.value === 'edit' && editId) {
      await api.patchWatchlistPosition(editId, { entryPrice: payload.entryPrice, shareCount: payload.shareCount })
    } else {
      const created = await api.createWatchlistItem({ symbol: payload.symbol, tag: 'HELD' })
      if (created.tag !== 'HELD') await api.patchWatchlistItem(created.id, { tag: 'HELD' })
      await api.patchWatchlistPosition(created.id, { entryPrice: payload.entryPrice, shareCount: payload.shareCount })
    }
    dialogOpen.value = false
    await load()
  } catch (e) {
    dialogError.value = e instanceof Error ? e.message : t('portfolio.dialog.error')
  } finally {
    submitting.value = false
  }
}

async function onDelete(item: WatchlistItem) {
  if (!confirm(t('portfolio.delete.confirm', { ticker: item.ticker }))) return
  try {
    await api.patchWatchlistPosition(item.id, { entryPrice: null, shareCount: null })
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('portfolio.loadError')
  }
}
</script>

<style scoped>
.pf-toolbar { display: flex; justify-content: flex-end; margin-bottom: var(--space-4); }
.pf-rows { display: flex; flex-direction: column; gap: var(--space-2); }
.pf-summary {
  display: flex; flex-wrap: wrap; gap: var(--space-2) var(--space-6);
  background: var(--crypt-black-elevated); border: var(--hairline); border-radius: 4px;
  padding: var(--space-3) var(--space-4); margin-bottom: var(--space-4);
}
.pf-sum { display: flex; flex-direction: column; gap: 2px; }
.pf-sum-k { font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.1em; color: var(--ash-gray); }
.pf-sum-v { font-size: var(--text-body); color: var(--bone-ivory); }
.pf-sum-v.pos { color: var(--cathedral-gold); }
.pf-sum-v.neg { color: var(--blood-crimson-bright); }
</style>
