<template>
  <div class="backtest">
    <div class="backtest__header">
      <h1 class="font-display">{{ t('backtest.title') }}</h1>
      <p class="backtest__subtitle">{{ t('backtest.subtitle') }}</p>
    </div>

    <!-- Config panel -->
    <div class="backtest__config">
      <div class="backtest__config-row">
        <span class="backtest__config-label">{{ t('backtest.config.strigoi') }}</span>
        <div class="backtest__chips">
          <button
            v-for="s in STRIGOI_OPTIONS"
            :key="s"
            class="backtest__chip"
            :class="{ 'backtest__chip--active': selectedStrigoi.includes(s) }"
            @click="toggleStrigoi(s)"
          >{{ s }}</button>
        </div>
      </div>
      <div class="backtest__config-row">
        <span class="backtest__config-label">{{ t('backtest.config.timeRange') }}</span>
        <div class="backtest__date-row">
          <input class="backtest__input" type="date" v-model="fromDate" />
          <span class="backtest__date-sep">→</span>
          <input class="backtest__input" type="date" v-model="toDate" />
          <div class="backtest__presets">
            <button
              v-for="p in PRESETS"
              :key="p.label"
              class="backtest__preset"
              @click="applyPreset(p.years)"
            >{{ p.label }}</button>
          </div>
        </div>
      </div>
      <div class="backtest__config-row">
        <span class="backtest__config-label">{{ t('backtest.config.universe') }}</span>
        <div class="backtest__radio-row">
          <label
            v-for="u in UNIVERSES"
            :key="u"
            class="backtest__radio"
          >
            <input type="radio" :value="u" v-model="universe" />
            {{ u }}
          </label>
        </div>
      </div>
      <div class="backtest__config-row backtest__config-row--right">
        <button class="backtest__run" disabled :title="t('backtest.config.runButtonTitle')">
          {{ t('backtest.config.runButton') }}
        </button>
      </div>
    </div>

    <!-- Recent backtests -->
    <SectionHeader label="recent backtests" class="backtest__section-gap" />
    <div class="backtest__runs">
      <div
        v-for="run in BACKTEST_RUNS"
        :key="run.id"
        class="backtest__run-card"
        :class="{ 'backtest__run-card--active': activeRunId === run.id }"
        @click="activeRunId = run.id"
      >
        <div class="backtest__run-label">{{ run.label }}</div>
        <div class="backtest__run-stats">
          <span class="backtest__run-stat">{{ t('backtest.runStats.hitRate') }} <strong>{{ run.hitRate }}</strong></span>
          <span class="backtest__run-stat">{{ t('backtest.runStats.avgReturn') }} <strong>{{ run.avgReturn }}</strong></span>
        </div>
        <div class="backtest__run-meta">{{ run.ranAgo }}</div>
      </div>
    </div>

    <!-- Results -->
    <template v-if="activeRunId">
      <SectionHeader label="results" class="backtest__section-gap" />
      <div class="backtest__tabs">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          class="backtest__tab"
          :class="{ 'backtest__tab--active': activeTab === tab.key }"
          @click="activeTab = tab.key"
        >{{ tab.label }}</button>
      </div>

      <!-- Overview -->
      <div v-if="activeTab === 'overview'" class="backtest__overview">
        <div v-for="stat in overviewStats" :key="stat.label" class="backtest__stat-card">
          <div class="backtest__stat-label">{{ stat.label }}</div>
          <div class="backtest__stat-value" :class="stat.positive ? 'backtest__stat-value--pos' : ''">
            {{ stat.value }}
          </div>
        </div>
      </div>

      <!-- Trades -->
      <div v-else-if="activeTab === 'trades'" class="backtest__trades">
        <table class="backtest__table">
          <thead>
            <tr>
              <th>{{ t('backtest.table.trades.symbol') }}</th>
              <th>{{ t('backtest.table.trades.entry') }}</th>
              <th>{{ t('backtest.table.trades.exit') }}</th>
              <th>{{ t('backtest.table.trades.return') }}</th>
              <th>{{ t('backtest.table.trades.thesis') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="tr in TRADES" :key="tr.symbol + tr.entry">
              <td class="backtest__ticker">{{ tr.symbol }}</td>
              <td>{{ tr.entry }}</td>
              <td>{{ tr.exit }}</td>
              <td :class="tr.ret.startsWith('+') ? 'backtest__ret--pos' : 'backtest__ret--neg'">{{ tr.ret }}</td>
              <td>{{ tr.validated ? '✓' : '✗' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Equity Curve -->
      <div v-else-if="activeTab === 'equityCurve'" class="backtest__chart">
        <apexchart type="area" height="280" :options="equityOptions" :series="equitySeries" />
      </div>

      <!-- Comparison -->
      <div v-else-if="activeTab === 'comparison'" class="backtest__comparison">
        <table class="backtest__table">
          <thead>
            <tr>
              <th>{{ t('backtest.table.comparison.strategy') }}</th>
              <th>{{ t('backtest.table.comparison.cagr') }}</th>
              <th>{{ t('backtest.table.comparison.sharpe') }}</th>
              <th>{{ t('backtest.table.comparison.winRate') }}</th>
              <th>{{ t('backtest.table.comparison.trades') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in COMPARISON" :key="row.strategy">
              <td>{{ row.strategy }}</td>
              <td class="backtest__ret--pos">{{ row.cagr }}</td>
              <td>{{ row.sharpe }}</td>
              <td>{{ row.winRate }}</td>
              <td>{{ row.trades }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VueApexCharts from 'vue3-apexcharts'
import SectionHeader from '../components/common/SectionHeader.vue'

const { t } = useI18n()
const apexchart = VueApexCharts

const STRIGOI_OPTIONS = ['spin', 'insider', 'echo', 'lazarus', 'index', 'merger']
const PRESETS = [{ label: '1Y', years: 1 }, { label: '2Y', years: 2 }, { label: '5Y', years: 5 }, { label: 'Max', years: 10 }]
const UNIVERSES = ['S&P 500', 'Russell 2000', 'NASDAQ 100', 'Custom']

const BACKTEST_RUNS = [
  { id: 'r1', label: 'Strigoi-Spin · Russell 2000 · 2024–2026', hitRate: '67%', avgReturn: '+14.2%', ranAgo: '2h ago' },
  { id: 'r2', label: 'Strigoi-Echo · S&P 500 · 2023–2026',      hitRate: '54%', avgReturn: '+9.1%',  ranAgo: '1d ago' },
  { id: 'r3', label: 'Strigoi-Spin · NASDAQ 100 · 2022–2026',   hitRate: '61%', avgReturn: '+11.8%', ranAgo: '3d ago' },
]

const tabs = computed(() => [
  { key: 'overview',    label: t('backtest.tabs.overview') },
  { key: 'trades',      label: t('backtest.tabs.trades') },
  { key: 'equityCurve', label: t('backtest.tabs.equityCurve') },
  { key: 'comparison',  label: t('backtest.tabs.comparison') },
])

const overviewStats = computed(() => [
  { label: t('backtest.overview.hitRate'),    value: '67%',    positive: true },
  { label: t('backtest.overview.avgReturn'),  value: '+14.2%', positive: true },
  { label: t('backtest.overview.sharpe'),     value: '1.34',   positive: true },
  { label: t('backtest.overview.maxDrawdown'),value: '-8.1%',  positive: false },
])

const TRADES = [
  { symbol: 'AVGO', entry: '2024-03-15', exit: '2024-09-15', ret: '+28.4%', validated: true },
  { symbol: 'MELI', entry: '2024-04-02', exit: '2024-10-02', ret: '+19.7%', validated: true },
  { symbol: 'NVDA', entry: '2024-05-20', exit: '2024-08-20', ret: '+6.1%',  validated: true },
  { symbol: 'PANW', entry: '2024-06-10', exit: '2024-12-10', ret: '-4.2%',  validated: false },
  { symbol: 'CRWD', entry: '2024-07-01', exit: '2025-01-01', ret: '+22.3%', validated: true },
  { symbol: 'DDOG', entry: '2024-08-15', exit: '2025-02-15', ret: '+11.0%', validated: true },
  { symbol: 'SHOP', entry: '2024-09-03', exit: '2025-03-03', ret: '-2.8%',  validated: false },
  { symbol: 'TTD',  entry: '2024-10-12', exit: '2025-04-12', ret: '+16.5%', validated: true },
]

const COMPARISON = [
  { strategy: 'Strigoi-Spin', cagr: '18.4%', sharpe: '1.34', winRate: '67%', trades: '12' },
  { strategy: 'SPY',          cagr: '11.2%', sharpe: '0.82', winRate: '—',   trades: '—'  },
]

const EQUITY_DATES = [
  '2024-01-01','2024-02-01','2024-03-01','2024-04-01','2024-05-01','2024-06-01',
  '2024-07-01','2024-08-01','2024-09-01','2024-10-01','2024-11-01','2024-12-01',
  '2025-01-01','2025-02-01','2025-03-01','2025-04-01','2025-05-01','2025-06-01',
  '2025-07-01','2025-08-01','2025-09-01','2025-10-01','2025-11-01','2025-12-01',
  '2026-01-01','2026-02-01','2026-03-01','2026-04-01',
]
const SPIN_EQUITY = [100,104,107,111,109,114,118,122,120,126,131,134,138,136,141,145,148,152,150,155,159,163,161,166,170,168,174,178]
const SPY_EQUITY  = [100,102,105,104,107,110,108,111,113,112,115,118,117,120,122,121,124,126,125,128,130,129,132,134,133,136,138,137]

const CHART_CRIMSON = '#A11D2C' // var(--blood-crimson)
const CHART_SILVER  = '#C4C4CA' // var(--moonlight-silver)
const CHART_MUTED   = '#6B6B70' // var(--ash-gray)

const equitySeries = [
  { name: 'Strigoi-Spin', data: EQUITY_DATES.map((d, i) => ({ x: d, y: SPIN_EQUITY[i] })) },
  { name: 'SPY',          data: EQUITY_DATES.map((d, i) => ({ x: d, y: SPY_EQUITY[i]  })) },
]

const equityOptions = {
  chart: {
    background: 'transparent', toolbar: { show: false }, zoom: { enabled: false },
    fontFamily: 'JetBrains Mono, IBM Plex Mono, Consolas, monospace', foreColor: CHART_MUTED,
  },
  colors: [CHART_CRIMSON, CHART_SILVER],
  stroke: { width: 2, curve: 'smooth' as const },
  fill: { type: 'gradient', gradient: { shadeIntensity: 1, opacityFrom: 0.2, opacityTo: 0.02 } },
  grid: { borderColor: 'rgba(255,255,255,0.04)', xaxis: { lines: { show: false } } },
  xaxis: { type: 'datetime' as const, labels: { style: { fontSize: '10px', colors: CHART_MUTED } } },
  yaxis: {
    labels: {
      style: { fontSize: '10px', colors: CHART_MUTED },
      formatter: (v: number) => v.toFixed(0),
    },
  },
  tooltip: { theme: 'dark' as const, x: { format: 'MMM yyyy' } },
  dataLabels: { enabled: false },
  legend: { labels: { colors: CHART_MUTED } },
}

const selectedStrigoi = ref(['spin'])
const fromDate = ref('2024-01-01')
const toDate = ref(new Date().toISOString().slice(0, 10))
const universe = ref('Russell 2000')
const activeRunId = ref(BACKTEST_RUNS[0].id)
const activeTab = ref('overview')

function toggleStrigoi(s: string) {
  const idx = selectedStrigoi.value.indexOf(s)
  if (idx === -1) selectedStrigoi.value = [...selectedStrigoi.value, s]
  else selectedStrigoi.value = selectedStrigoi.value.filter(x => x !== s)
}

function applyPreset(years: number) {
  const d = new Date()
  d.setFullYear(d.getFullYear() - years)
  fromDate.value = d.toISOString().slice(0, 10)
}
</script>

<style scoped>
.backtest {
  max-width: 1280px;
  margin: 0 auto;
  padding: var(--space-8) var(--space-6);
}
.backtest__header { margin-bottom: var(--space-8); }
.backtest__header h1 {
  font-size: var(--text-h1); line-height: 1.15; letter-spacing: -0.01em;
  color: var(--bone-ivory); margin: 0 0 var(--space-2) 0;
}
.backtest__subtitle { color: var(--bone-ivory-dim); margin: 0; font-size: var(--text-body); }

.backtest__config {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 6px;
  padding: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}
.backtest__config-row {
  display: flex;
  align-items: center;
  gap: var(--space-4);
}
.backtest__config-row--right { justify-content: flex-end; }
.backtest__config-label {
  width: 90px;
  font-size: var(--text-micro);
  color: var(--ash-gray);
  letter-spacing: 0.05em;
  text-transform: uppercase;
  flex-shrink: 0;
}
.backtest__chips { display: flex; flex-wrap: wrap; gap: var(--space-2); }
.backtest__chip {
  padding: 3px 12px;
  border-radius: 12px;
  font-size: var(--text-micro);
  background: transparent;
  border: 1px solid rgba(255,255,255,0.12);
  color: var(--bone-ivory-dim);
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;
}
.backtest__chip--active {
  border-color: var(--cathedral-gold);
  color: var(--cathedral-gold);
}
.backtest__date-row { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
.backtest__input {
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 4px;
  color: var(--bone-ivory);
  padding: 4px 8px;
  font-size: var(--text-micro);
  font-family: var(--font-mono);
}
.backtest__date-sep { color: var(--ash-gray); }
.backtest__presets { display: flex; gap: var(--space-1); }
.backtest__preset {
  padding: 2px 10px;
  border-radius: 10px;
  font-size: var(--text-micro);
  background: transparent;
  border: 1px solid rgba(255,255,255,0.08);
  color: var(--ash-gray);
  cursor: pointer;
}
.backtest__preset:hover { border-color: rgba(255,255,255,0.2); color: var(--bone-ivory-dim); }
.backtest__radio-row { display: flex; gap: var(--space-5); }
.backtest__radio {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-micro);
  color: var(--bone-ivory-dim);
  cursor: pointer;
}
.backtest__run {
  padding: 8px 20px;
  background: var(--blood-crimson);
  border: none;
  border-radius: 4px;
  color: var(--bone-ivory);
  font-size: var(--text-body);
  cursor: not-allowed;
  opacity: 0.45;
}

.backtest__section-gap { margin-top: var(--space-8); }

.backtest__runs { display: flex; gap: var(--space-4); flex-wrap: wrap; margin-top: var(--space-4); }
.backtest__run-card {
  flex: 1;
  min-width: 220px;
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 6px;
  padding: var(--space-4);
  cursor: pointer;
  transition: border-color 0.15s;
}
.backtest__run-card--active { border-color: var(--blood-crimson); }
.backtest__run-label { font-size: var(--text-micro); color: var(--bone-ivory); margin-bottom: var(--space-2); }
.backtest__run-stats { display: flex; gap: var(--space-4); margin-bottom: var(--space-2); }
.backtest__run-stat { font-size: var(--text-micro); color: var(--ash-gray); }
.backtest__run-stat strong { color: var(--bone-ivory); }
.backtest__run-meta { font-size: var(--text-micro); color: var(--ash-gray); }

.backtest__tabs { display: flex; gap: 0; margin-top: var(--space-4); border-bottom: 1px solid rgba(255,255,255,0.06); }
.backtest__tab {
  padding: var(--space-2) var(--space-5);
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  color: var(--ash-gray);
  font-size: var(--text-body);
  cursor: pointer;
  margin-bottom: -1px;
  transition: color 0.15s, border-color 0.15s;
}
.backtest__tab--active { color: var(--bone-ivory); border-bottom-color: var(--blood-crimson); }

.backtest__overview {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-4);
  margin-top: var(--space-6);
}
.backtest__stat-card {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 6px;
  padding: var(--space-5);
}
.backtest__stat-label { font-size: var(--text-micro); color: var(--ash-gray); margin-bottom: var(--space-2); }
.backtest__stat-value { font-size: var(--text-h3); font-family: var(--font-mono); color: var(--bone-ivory); }
.backtest__stat-value--pos { color: var(--signal-positive); }

.backtest__trades, .backtest__comparison { margin-top: var(--space-6); }
.backtest__chart { margin-top: var(--space-4); }
.backtest__table { width: 100%; border-collapse: collapse; font-size: var(--text-body); }
.backtest__table th {
  text-align: left; color: var(--ash-gray);
  border-bottom: 1px solid rgba(255,255,255,0.06);
  padding: var(--space-2) var(--space-3) var(--space-2) 0;
  font-weight: 400;
}
.backtest__table td {
  color: var(--bone-ivory-dim);
  padding: var(--space-2) var(--space-3) var(--space-2) 0;
  border-bottom: 1px solid rgba(255,255,255,0.03);
}
.backtest__ticker { font-family: var(--font-mono); color: var(--bone-ivory); }
.backtest__ret--pos { color: var(--signal-positive); font-family: var(--font-mono); }
.backtest__ret--neg { color: var(--blood-crimson); font-family: var(--font-mono); }
</style>
