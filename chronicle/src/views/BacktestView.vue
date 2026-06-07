<template>
  <div class="content-inner backtest">
    <PageHead :sub="t('backtest.subtitle')">
      <template #eyebrow>
        <span class="eb-glyph"><BatGlyph :size="13" /></span>
        {{ t('backtest.eyebrow') }}
      </template>
      <template #title>{{ t('backtest.pageTitle') }}</template>
    </PageHead>

    <!-- config console -->
    <div class="card bt-config">
      <div class="bt-field">
        <span class="bt-label">{{ t('backtest.config.strigoi') }}</span>
        <div class="chip-row">
          <button
            v-for="n in STRIGOI_NAMES"
            :key="n"
            class="select-chip"
            :class="{ active: strat === n }"
            @click="strat = n"
          >{{ n }}</button>
        </div>
      </div>
      <div class="bt-field">
        <span class="bt-label">{{ t('backtest.config.timeRange') }}</span>
        <div class="chip-row">
          <span class="date-box mono">{{ fromDate }}</span>
          <span class="date-arrow">→</span>
          <span class="date-box mono">{{ toDate }}</span>
          <span class="preset-group">
            <button
              v-for="p in PRESETS"
              :key="p"
              class="select-chip ghost bt-preset"
              :class="{ active: preset === p }"
              @click="preset = p"
            >{{ p }}</button>
          </span>
        </div>
      </div>
      <div class="bt-field">
        <span class="bt-label">{{ t('backtest.config.universe') }}</span>
        <div class="chip-row">
          <button
            v-for="u in UNIVERSES"
            :key="u"
            class="radio-opt"
            :class="{ active: universe === u }"
            @click="universe = u"
          >
            <span class="radio-dot" /> {{ u }}
          </button>
        </div>
      </div>
      <div class="bt-run">
        <button class="btn btn-primary" :title="t('backtest.config.runButtonTitle')">
          {{ t('backtest.config.runButton') }}
        </button>
      </div>
    </div>

    <!-- recent runs -->
    <SectionHeader :label="t('backtest.sections.recentBacktests')" />
    <div class="bt-recent">
      <div
        v-for="(r, i) in RECENT"
        :key="i"
        class="bt-recent-card"
        :class="{ active: r.active }"
      >
        <div class="brc-title">{{ r.strigoi }} · {{ r.universe }} · {{ r.span }}</div>
        <div class="brc-stats">
          <span>{{ t('backtest.runStats.hitRate') }} <b class="mono">{{ Math.round(r.hit * 100) }}%</b></span>
          <span class="brc-sep">·</span>
          <span>{{ t('backtest.runStats.avgReturn') }} <b class="mono pos">+{{ (r.avg * 100).toFixed(1) }}%</b></span>
        </div>
        <div class="brc-when">{{ r.when }}</div>
      </div>
    </div>

    <!-- results -->
    <SectionHeader :label="t('backtest.sections.results')" />
    <div class="bt-restabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="restab"
        :class="{ active: resTab === tab.key }"
        @click="resTab = tab.key"
      >{{ tab.label }}</button>
    </div>

    <!-- Trades -->
    <div v-if="resTab === 'trades'" class="card bt-trades-card">
      <div class="table-wrap">
        <table class="dt">
          <thead>
            <tr>
              <th>{{ t('backtest.table.trades.symbol') }}</th>
              <th>{{ t('backtest.table.trades.entry') }}</th>
              <th>{{ t('backtest.table.trades.exit') }}</th>
              <th class="num">{{ t('backtest.table.trades.return') }}</th>
              <th class="num">{{ t('backtest.table.trades.thesis') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="tr in TRADES" :key="tr.sym">
              <td class="tkr">{{ tr.sym }}</td>
              <td class="mono">{{ tr.in }}</td>
              <td class="mono">{{ tr.out }}</td>
              <td class="num" :class="tr.ret >= 0 ? 'pos' : 'neg'">
                {{ tr.ret >= 0 ? '+' : '' }}{{ (tr.ret * 100).toFixed(1) }}%
              </td>
              <td class="num">
                <i
                  v-if="tr.thesis"
                  class="ph ph-check bt-thesis-yes"
                  aria-hidden="true"
                />
                <i v-else class="ph ph-x bt-thesis-no" aria-hidden="true" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Equity / Comparison / Overview -->
    <div v-else class="chart-card">
      <div class="chart-head">
        <span class="chart-title">{{ t('backtest.chart.title') }}</span>
        <div class="chart-legend">
          <span class="lg"><span class="swatch swatch--crimson" /> {{ t('backtest.chart.legendStrigoi') }}</span>
          <span class="lg"><span class="swatch swatch--gold" /> {{ t('backtest.chart.legendBenchmark') }}</span>
        </div>
      </div>
      <LineChart :height="260" area-fill :series="equitySeries" :aria-label="t('backtest.chart.ariaLabel')" />
      <div class="vist-foot">
        <div>
          <div class="vf-k">{{ t('backtest.chart.footStrigoi') }}</div>
          <div class="vf-v mono pos">+{{ lastStrigoi.toFixed(1) }}%</div>
        </div>
        <div>
          <div class="vf-k">{{ t('backtest.chart.footBenchmark') }}</div>
          <div class="vf-v mono">+{{ lastBench.toFixed(1) }}%</div>
        </div>
        <div>
          <div class="vf-k">{{ t('backtest.chart.footEdge') }}</div>
          <div class="vf-v mono pos">+{{ (lastStrigoi - lastBench).toFixed(1) }} pts</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import PageHead from '../components/common/PageHead.vue'
import BatGlyph from '../components/common/BatGlyph.vue'
import SectionHeader from '../components/common/SectionHeader.vue'
import LineChart from '../components/common/LineChart.vue'

const { t } = useI18n()

// Static demo data — there is no backtest compute engine. Shape mirrors the
// prototype's data.backtest (recent[], trades[], equityStrigoi[], equityBench[]).
const STRIGOI_NAMES = ['spin', 'insider', 'echo', 'lazarus', 'index', 'merger']
const PRESETS = ['1J', '2J', '5J', 'Max']
const UNIVERSES = ['S&P 500', 'Russell 2000', 'NASDAQ 100', 'Custom']

const RECENT = [
  { strigoi: 'Strigoi-Spin', universe: 'Russell 2000', span: '2024–2026', hit: 0.67, avg: 0.142, when: 'vor 2h', active: true },
  { strigoi: 'Strigoi-Echo', universe: 'S&P 500', span: '2023–2026', hit: 0.54, avg: 0.091, when: 'vor 1d', active: false },
  { strigoi: 'Strigoi-Spin', universe: 'NASDAQ 100', span: '2022–2026', hit: 0.61, avg: 0.118, when: 'vor 3d', active: false },
]

const TRADES = [
  { sym: 'AVGO', in: '2024-03-15', out: '2024-09-15', ret: 0.284, thesis: true },
  { sym: 'MELI', in: '2024-04-02', out: '2024-10-02', ret: 0.197, thesis: true },
  { sym: 'NVDA', in: '2024-05-20', out: '2024-08-20', ret: 0.061, thesis: true },
  { sym: 'PANW', in: '2024-06-10', out: '2024-12-10', ret: -0.042, thesis: false },
  { sym: 'CRWD', in: '2024-07-01', out: '2025-01-01', ret: 0.223, thesis: true },
  { sym: 'NOW', in: '2024-08-12', out: '2025-02-12', ret: 0.158, thesis: true },
  { sym: 'WDAY', in: '2024-09-05', out: '2025-03-05', ret: -0.031, thesis: false },
]

const equityStrigoi = [0, 1.2, 2.1, 1.8, 3.4, 4.9, 4.2, 6.1, 7.8, 7.1, 9.4, 11.2, 10.1, 12.7, 14.9, 13.8, 16.2, 18.1, 17.0, 19.8, 22.4, 21.1, 24.0, 26.8, 25.3, 28.1, 30.9, 29.4, 32.7, 35.2]
const equityBench = [0, 0.8, 1.4, 1.1, 2.0, 2.8, 2.3, 3.1, 3.9, 3.4, 4.2, 5.0, 4.4, 5.3, 6.1, 5.6, 6.4, 7.1, 6.7, 7.5, 8.2, 7.8, 8.6, 9.3, 8.9, 9.7, 10.4, 10.0, 10.8, 11.5]

const equitySeries = [
  { data: equityStrigoi, color: 'var(--blood-crimson)', fill: 'rgba(161,29,44,0.12)' },
  { data: equityBench, color: 'var(--cathedral-gold)', fill: 'rgba(184,148,92,0.06)' },
]

const lastStrigoi = equityStrigoi[equityStrigoi.length - 1]
const lastBench = equityBench[equityBench.length - 1]

const tabs = computed(() => [
  { key: 'overview', label: t('backtest.tabs.overview') },
  { key: 'trades', label: t('backtest.tabs.trades') },
  { key: 'equity', label: t('backtest.tabs.equityCurve') },
  { key: 'compare', label: t('backtest.tabs.comparison') },
])

const strat = ref('spin')
const universe = ref('Russell 2000')
const preset = ref('2J')
const resTab = ref('overview')
const fromDate = '01.01.2024'
const toDate = '07.06.2026'
</script>

<style scoped>
/* ---- config console ---- */
.bt-config { display: flex; flex-direction: column; gap: var(--space-5); margin-bottom: var(--space-8); }
.bt-field { display: grid; grid-template-columns: 110px 1fr; gap: var(--space-4); align-items: start; }
.bt-label { font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.1em; color: var(--ash-gray); padding-top: 8px; }
.chip-row { display: flex; flex-wrap: wrap; gap: var(--space-2); align-items: center; }
.select-chip { font-size: var(--text-body-sm); color: var(--bone-ivory-dim); background: var(--crypt-black-deep); border: 1px solid rgba(245, 241, 232, 0.08); border-radius: 4px; padding: 7px 12px; cursor: pointer; transition: border-color var(--transition-fast), color var(--transition-fast); font-family: var(--font-mono); }
.select-chip:hover { border-color: rgba(184, 148, 92, 0.3); color: var(--bone-ivory); }
.select-chip.active { border-color: var(--blood-crimson); color: var(--bone-ivory); background: rgba(161, 29, 44, 0.1); }
.select-chip.ghost { background: none; }
.date-box { background: var(--crypt-black-deep); border: 1px solid rgba(245, 241, 232, 0.08); border-radius: 4px; padding: 7px 12px; font-size: var(--text-body-sm); color: var(--bone-ivory); }
.date-arrow { color: var(--ash-gray); }
.preset-group { display: flex; gap: var(--space-1); margin-left: var(--space-2); }
.radio-opt { display: inline-flex; align-items: center; gap: var(--space-2); font-size: var(--text-body-sm); color: var(--bone-ivory-dim); background: none; border: none; cursor: pointer; padding: 6px 8px; }
.radio-opt .radio-dot { width: 13px; height: 13px; border-radius: 50%; border: 1.5px solid var(--ash-gray); transition: border-color var(--transition-fast); }
.radio-opt.active { color: var(--bone-ivory); }
.radio-opt.active .radio-dot { border-color: var(--blood-crimson); background: radial-gradient(circle, var(--blood-crimson) 0 40%, transparent 45%); }
.bt-run { display: flex; justify-content: flex-end; }

/* ---- recent runs ---- */
.bt-recent { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-4); margin-bottom: var(--space-8); }
.bt-recent-card { background: var(--crypt-black-elevated); border: var(--hairline); border-radius: 4px; padding: var(--space-4); }
.bt-recent-card.active { border-color: rgba(161, 29, 44, 0.4); }
.brc-title { font-size: var(--text-body-sm); color: var(--bone-ivory); margin-bottom: var(--space-3); }
.brc-stats { display: flex; align-items: center; gap: var(--space-2); font-size: var(--text-body-sm); color: var(--bone-ivory-dim); }
.brc-stats b { color: var(--bone-ivory); font-weight: 500; }
.brc-sep { color: rgba(107, 107, 112, 0.5); }
.brc-when { font-size: var(--text-micro); color: var(--ash-gray); margin-top: var(--space-2); }

/* ---- result tabs ---- */
.bt-restabs { display: flex; gap: var(--space-1); margin-bottom: var(--space-5); border-bottom: 1px solid var(--rule); }
.restab { font-size: var(--text-body-sm); color: var(--ash-gray); background: none; border: none; padding: var(--space-3) var(--space-4); cursor: pointer; position: relative; transition: color var(--transition-fast); }
.restab:hover { color: var(--bone-ivory-dim); }
.restab.active { color: var(--bone-ivory); }
.restab.active::after { content: ""; position: absolute; left: var(--space-3); right: var(--space-3); bottom: -1px; height: 2px; background: var(--blood-crimson); }

.bt-trades-card { padding: 0; }
.bt-thesis-yes { color: var(--signal-positive); }
.bt-thesis-no { color: var(--blood-crimson-bright); }

/* ---- chart card (not global) ---- */
.chart-card { background: var(--crypt-black-elevated); border: var(--hairline); border-radius: 4px; padding: var(--space-5); }
.chart-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: var(--space-5); gap: var(--space-4); flex-wrap: wrap; }
.chart-title { font-size: var(--text-body); color: var(--bone-ivory); }
.chart-legend { display: flex; gap: var(--space-4); font-size: var(--text-micro); color: var(--ash-gray); }
.chart-legend .lg { display: flex; align-items: center; gap: var(--space-2); }
.chart-legend .lg .swatch { width: 14px; height: 2px; }
.swatch--crimson { background: var(--blood-crimson); }
.swatch--gold { background: var(--cathedral-gold); }
.vist-foot { display: flex; gap: var(--space-8); margin-top: var(--space-5); padding-top: var(--space-4); border-top: 1px solid var(--rule); }
.vf-k { font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.1em; color: var(--ash-gray); margin-bottom: var(--space-1); }
.vf-v { font-size: var(--text-h4); color: var(--bone-ivory); }

@media (max-width: 599.98px) {
  .bt-field { grid-template-columns: 1fr; gap: var(--space-2); }
  .bt-label { padding-top: 0; }
  .bt-recent { grid-template-columns: 1fr; }
  .vist-foot { gap: var(--space-6); }
}
</style>
