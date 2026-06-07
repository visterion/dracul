<template>
  <div class="vistierie">
    <div class="vistierie__header">
      <h1 class="font-display">{{ t('vistierie.title') }}</h1>
      <p class="vistierie__subtitle">{{ t('vistierie.subtitle') }}</p>
    </div>

    <template v-if="loading">
      <div class="vistierie__grid">
        <v-skeleton-loader v-for="i in 3" :key="i" type="card" />
      </div>
    </template>

    <div v-else-if="error" class="vistierie__error">{{ error }}</div>

    <div v-else-if="data" class="vistierie__grid">
      <!-- LEFT: Tier Budgets -->
      <div class="vistierie__col">
        <SectionHeader :label="t('vistierie.sections.tierBudgets')" />
        <TierBudgetBar
          v-for="tier in data.tiers"
          :key="tier.name"
          :name="tier.name"
          :models="tier.models"
          :budget-usd="tier.budgetUsd"
          :used-usd="tier.usedUsd"
        />
        <SectionHeader :label="t('vistierie.sections.monthlyTotal')" class="vistierie__section-gap" />
        <TierBudgetBar
          name="Month"
          models=""
          :budget-usd="data.monthlyBudgetUsd"
          :used-usd="data.monthlyTotalUsd"
        />
      </div>

      <!-- MIDDLE: Spending by Agent -->
      <div class="vistierie__col">
        <SectionHeader :label="t('vistierie.sections.spendingByAgent')" />
        <div class="vistierie__agent-bars">
          <div
            v-for="agent in data.spendingByAgent"
            :key="agent.agent"
            class="vistierie__agent-row"
          >
            <div class="vistierie__agent-label">{{ agent.agent }}</div>
            <div class="vistierie__agent-track">
              <div
                class="vistierie__agent-fill"
                :style="{
                  width: Math.min(Math.max(agent.pct, 0), 100) + '%',
                  background: agentColor(agent.agent),
                }"
              />
            </div>
            <div class="vistierie__agent-amount">${{ agent.totalUsd.toFixed(2) }}</div>
          </div>
        </div>
        <SectionHeader :label="t('vistierie.sections.topSpenders')" class="vistierie__section-gap" />
        <table class="vistierie__table">
          <thead>
            <tr>
              <th>{{ t('vistierie.table.agent') }}</th>
              <th class="vistierie__table-num">{{ t('vistierie.table.usdToday') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="agent in topSpenders"
              :key="agent.agent"
            >
              <td>{{ agent.agent }}</td>
              <td class="vistierie__table-num">${{ agent.totalUsd.toFixed(2) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- RIGHT: Daily Trend -->
      <div class="vistierie__col">
        <SectionHeader :label="t('vistierie.sections.dailySpend')" />
        <apexchart
          type="area"
          height="220"
          :options="chartOptions"
          :series="chartSeries"
        />
        <div class="vistierie__stats">
          <div class="vistierie__stat">
            <span class="vistierie__stat-label">{{ t('vistierie.stats.avgPerDay') }}</span>
            <span class="vistierie__stat-value">${{ avgPerDay }}</span>
          </div>
          <div class="vistierie__stat">
            <span class="vistierie__stat-label">{{ t('vistierie.stats.monthTotal') }}</span>
            <span class="vistierie__stat-value">${{ data.monthlyTotalUsd.toFixed(2) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import VueApexCharts from 'vue3-apexcharts'
import type { VistierieData } from '../api/types'
import { useApi } from '../api'
import SectionHeader from '../components/common/SectionHeader.vue'
import TierBudgetBar from '../components/TierBudgetBar.vue'

const { t } = useI18n()
const apexchart = VueApexCharts

const api = useApi()
const data = ref<VistierieData | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    data.value = await api.getVistierieData()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to load'
  } finally {
    loading.value = false
  }
})

const topSpenders = computed(() =>
  [...(data.value?.spendingByAgent ?? [])]
    .sort((a, b) => b.totalUsd - a.totalUsd)
    .slice(0, 3)
)

const avgPerDay = computed(() => {
  const d = data.value?.dailySpend30d
  if (!d || d.length === 0) return '0.00'
  const avg = d.reduce((s, e) => s + e.totalUsd, 0) / d.length
  return avg.toFixed(2)
})

const chartSeries = computed(() => [{
  name: t('vistierie.sections.dailySpend'),
  data: (data.value?.dailySpend30d ?? []).map(e => ({ x: e.date, y: e.totalUsd })),
}])

const CHART_GOLD  = '#c9aa71' // var(--cathedral-gold)
const CHART_MUTED = '#6B6B70' // var(--ash-gray)

const chartOptions = {
  chart: {
    background: 'transparent',
    toolbar: { show: false },
    zoom: { enabled: false },
    fontFamily: 'JetBrains Mono, IBM Plex Mono, Consolas, monospace',
    foreColor: CHART_MUTED,
  },
  colors: [CHART_GOLD],
  stroke: { width: 2, curve: 'smooth' as const },
  fill: { type: 'gradient', gradient: { shadeIntensity: 1, opacityFrom: 0.3, opacityTo: 0.02 } },
  grid: { borderColor: 'rgba(255,255,255,0.04)', xaxis: { lines: { show: false } } },
  xaxis: {
    type: 'datetime' as const,
    labels: { style: { fontSize: '10px', colors: CHART_MUTED }, datetimeUTC: false },
  },
  yaxis: {
    labels: {
      style: { fontSize: '10px', colors: CHART_MUTED },
      formatter: (v: number) => '$' + v.toFixed(2),
    },
  },
  tooltip: {
    theme: 'dark' as const,
    x: { format: 'dd MMM' },
    y: { formatter: (v: number) => '$' + v.toFixed(2) },
  },
  dataLabels: { enabled: false },
}

const AGENT_COLORS: Record<string, string> = {
  'strigoi-spin':    '#A11D2C',
  'strigoi-insider': '#6b3a1a',
  'strigoi-echo':    '#5a3a6b',
  'strigoi-lazarus': '#1a4a6b',
  'strigoi-index':   '#4a4a8b',
  'strigoi-merger':  '#2a5a4a',
  'voievod':         '#7a5a1a',
  'daywalker':       '#2a5a2a',
}
function agentColor(agent: string): string {
  return AGENT_COLORS[agent] ?? '#444'
}
</script>

<style scoped>
.vistierie {
  max-width: 1400px;
  margin: 0 auto;
  padding: var(--space-8) var(--space-6);
}
.vistierie__header { margin-bottom: var(--space-8); }
.vistierie__header h1 {
  font-size: var(--text-h1);
  line-height: 1.15;
  letter-spacing: -0.01em;
  color: var(--bone-ivory);
  margin: 0 0 var(--space-2) 0;
}
.vistierie__subtitle { color: var(--bone-ivory-dim); margin: 0; font-size: var(--text-body); }
.vistierie__error { color: var(--blood-crimson); padding: var(--space-8) 0; }
.vistierie__grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-8);
}
.vistierie__col { display: flex; flex-direction: column; }
.vistierie__section-gap { margin-top: var(--space-6); }

/* Agent bars */
.vistierie__agent-bars { display: flex; flex-direction: column; gap: var(--space-3); }
.vistierie__agent-row { display: flex; align-items: center; gap: var(--space-3); }
.vistierie__agent-label {
  width: 140px;
  font-size: var(--text-micro);
  color: var(--bone-ivory-dim);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.vistierie__agent-track {
  flex: 1;
  height: 5px;
  background: rgba(255, 255, 255, 0.06);
  border-radius: 3px;
  overflow: hidden;
}
.vistierie__agent-fill { height: 100%; border-radius: 3px; transition: width 0.4s ease; }
.vistierie__agent-amount {
  width: 44px;
  text-align: right;
  font-size: var(--text-micro);
  font-family: var(--font-mono);
  color: var(--cathedral-gold);
}

/* Table */
.vistierie__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--text-micro);
}
.vistierie__table th {
  text-align: left;
  color: var(--ash-gray);
  border-bottom: 1px solid rgba(255,255,255,0.06);
  padding: var(--space-1) 0;
}
.vistierie__table td {
  color: var(--bone-ivory-dim);
  padding: var(--space-2) 0;
  border-bottom: 1px solid rgba(255,255,255,0.03);
}
.vistierie__table-num { text-align: right; font-family: var(--font-mono); }

/* Stats */
.vistierie__stats {
  display: flex;
  gap: var(--space-6);
  margin-top: var(--space-4);
}
.vistierie__stat { display: flex; flex-direction: column; gap: 2px; }
.vistierie__stat-label { font-size: var(--text-micro); color: var(--ash-gray); }
.vistierie__stat-value {
  font-size: var(--text-body);
  font-family: var(--font-mono);
  color: var(--cathedral-gold);
}
</style>
