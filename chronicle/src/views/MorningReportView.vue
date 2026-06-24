<template>
  <div class="content-inner morning-report" data-testid="morning-report">
    <PageHead :sub="t('report.subtitle')">
      <template #eyebrow>
        <span class="eb-glyph"><BatGlyph :size="13" /></span>
        {{ t('report.eyebrow') }}
      </template>
      <template #title>{{ t('report.title') }}</template>
    </PageHead>

    <p class="report-note">{{ t('report.readonlyNote') }}</p>

    <template v-if="loading">
      <v-skeleton-loader v-for="i in 3" :key="i" type="list-item-two-line" />
    </template>

    <div v-else-if="error" class="empty small">
      <div class="em-text">{{ error }}</div>
    </div>

    <div v-else-if="!report || report.positions.length === 0" class="empty small" data-testid="report-empty">
      <div class="em-text">{{ t('report.empty') }}</div>
    </div>

    <ul v-else class="report-list" data-testid="report-list">
      <li v-for="line in report.positions" :key="line.symbol" class="report-row">
        <div class="report-main">
          <span class="report-action tag-pill" :class="actionPillClass(line.action)">{{ line.action }}</span>
          <span class="report-symbol mono">{{ line.symbol }}</span>
          <span class="report-name">{{ line.companyName }}</span>
        </div>
        <div class="report-metrics mono">
          <span><span class="metric-label">{{ t('report.cols.stop') }}</span> {{ fmt(line.activeStop) }}</span>
          <span><span class="metric-label">{{ t('report.cols.target') }}</span> {{ line.targetReached ? t('report.targetReached') : fmt(line.nextTarget2r) }}</span>
          <span><span class="metric-label">{{ t('report.cols.price') }}</span> {{ fmt(line.currentClose) }}</span>
          <span><span class="metric-label">{{ t('report.cols.distance') }}</span> {{ pct(line.distanceToStopPct) }}</span>
        </div>
        <p v-if="line.rationale" class="report-rationale">{{ line.rationale }}</p>
        <OrderTicketCard :ticket="line.ticket" />
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import PageHead from '../components/common/PageHead.vue'
import BatGlyph from '../components/common/BatGlyph.vue'
import OrderTicketCard from '../components/report/OrderTicketCard.vue'
import { useApi } from '../api'
import type { MorningReport } from '../api/types'

const { t } = useI18n()
const api = useApi()

const report = ref<MorningReport | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    report.value = await api.getMorningReport()
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('report.empty')
  } finally {
    loading.value = false
  }
})

const fmt = (v: number | null) => (v == null ? '—' : v.toLocaleString())
const pct = (v: number | null) => (v == null ? '—' : `${v >= 0 ? '+' : ''}${v.toFixed(1)}%`)

function actionPillClass(action: string): string {
  switch (action.toUpperCase()) {
    case 'SELL': return 'crimson'
    case 'TRIM': return 'gold'
    default:     return 'ash'
  }
}
</script>

<style scoped>
.report-note {
  font-size: var(--text-body-sm);
  font-style: italic;
  color: var(--ash-gray-light);
  margin: 0 0 var(--space-6);
}
.report-list {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.report-row {
  background: var(--crypt-black-elevated);
  border: var(--hairline);
  border-radius: 4px;
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  transition: border-color var(--transition-fast);
}
.report-row:hover {
  border-color: rgba(184,148,92,0.30);
}
.report-main {
  display: flex;
  gap: var(--space-3);
  align-items: baseline;
  flex-wrap: wrap;
}
.report-symbol {
  font-size: var(--text-body);
  color: var(--bone-ivory);
  font-weight: 500;
}
.report-name {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
}
.report-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1) var(--space-5);
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
}
.metric-label {
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--ash-gray);
  margin-right: var(--space-1);
}
.report-rationale {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
  margin: 0;
  font-style: italic;
}
</style>
