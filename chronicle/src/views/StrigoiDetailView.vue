<template>
  <div v-if="loading" class="sd-loading">
    <v-skeleton-loader v-for="n in 4" :key="n" type="card" color="surface" class="sd-loading__item" />
  </div>

  <div v-else-if="!strigoi" class="sd-notfound">
    <p>{{ t('strigoi.notFound.message') }}</p>
    <router-link to="/" class="sd-notfound__link">{{ t('strigoi.notFound.backLink') }}</router-link>
  </div>

  <article v-else class="sd">
    <!-- Breadcrumb -->
    <nav class="sd__breadcrumb" aria-label="Breadcrumb">
      <router-link to="/" class="sd__bc-link">{{ t('strigoi.breadcrumb.chronicle') }}</router-link>
      <span class="sd__bc-sep">/</span>
      <span class="sd__bc-link">{{ t('strigoi.breadcrumb.strigoi') }}</span>
      <span class="sd__bc-sep">/</span>
      <span class="sd__bc-current">{{ strigoi.name }}</span>
    </nav>

    <!-- Page header -->
    <header class="sd__header">
      <div class="sd__header-identity">
        <span class="sd__bat" aria-hidden="true">🦇</span>
        <div>
          <h1 class="sd__name font-mono">{{ strigoi.name }}</h1>
          <p class="sd__description">
            {{ strigoi.description }}
            <span class="sd__reference">{{ strigoi.reference }}</span>
          </p>
        </div>
      </div>
      <div class="sd__header-status">
        <span class="sd__state-pill" :class="`sd__state-pill--${strigoi.state}`">
          <span class="sd__state-dot" aria-hidden="true">{{ stateIcon }}</span>
          {{ strigoi.state }}
        </span>
        <div class="sd__schedule">
          <span>{{ t('strigoi.schedule.lastRun') }} {{ relativeTime(strigoi.lastRunAt) }}</span>
          <span class="sd__schedule-sep">·</span>
          <span>{{ t('strigoi.schedule.next') }} {{ formatNextRun(strigoi.nextRunAt) }}</span>
        </div>
      </div>
    </header>

    <!-- Stat cards -->
    <div class="sd__stats-row">
      <div class="sd__stat-card">
        <div class="sd__stat-value font-display tabular">{{ strigoi.huntsThisMonth }}</div>
        <div class="sd__stat-label">{{ t('strigoi.stats.huntsThisMonth') }}</div>
        <div class="sd__stat-sub">{{ t('strigoi.stats.huntsScheduled', { n: strigoi.scheduledHuntsThisMonth }) }}</div>
      </div>
      <div class="sd__stat-card">
        <div class="sd__stat-value font-display tabular">{{ strigoi.avgPreyPerHunt.toFixed(1) }}</div>
        <div class="sd__stat-label">{{ t('strigoi.stats.avgPreyPerHunt') }}</div>
        <div class="sd__stat-sub">{{ t('strigoi.stats.avgPreyDetail') }}</div>
      </div>
      <div class="sd__stat-card">
        <div
          class="sd__stat-value font-display tabular"
          :class="strigoi.hitRate90d >= 0.6 ? 'sd__stat-value--positive' : ''"
        >{{ Math.round(strigoi.hitRate90d * 100) }}%</div>
        <div class="sd__stat-label">{{ t('strigoi.stats.hitRate90d') }}</div>
        <div class="sd__stat-sub">{{ t('strigoi.stats.hitRateDetail', { num: strigoi.hitRateNumerator, den: strigoi.hitRateDenominator }) }}</div>
      </div>
    </div>

    <!-- Recent runs -->
    <SectionHeader :label="t('strigoi.sections.recentRuns')" />
    <div class="sd__runs" role="list">
      <div v-for="run in strigoi.recentRuns" :key="run.id" class="sd__run" role="listitem">
        <button
          class="sd__run-header"
          :aria-expanded="expandedRuns.has(run.id)"
          @click="toggleRun(run.id)"
        >
          <span class="sd__run-chevron" :class="{ 'sd__run-chevron--open': expandedRuns.has(run.id) }">▶</span>
          <span class="sd__run-date font-mono tabular">{{ formatRunDate(run.ranAt) }}</span>
          <span class="sd__run-sep">·</span>
          <span class="sd__run-prey font-mono tabular">{{ run.preyCount }} {{ t('strigoi.run.preyUnit') }}</span>
          <span class="sd__run-sep">·</span>
          <span class="sd__run-cost font-mono tabular">${{ run.costUsd.toFixed(3) }}</span>
          <span class="sd__run-sep">·</span>
          <span class="sd__run-model font-mono">{{ run.model }}</span>
        </button>
        <div
          v-if="expandedRuns.has(run.id)"
          class="sd__run-trace"
          role="region"
          :aria-label="t('strigoi.run.traceLabel', { id: run.id })"
        >
          <div
            v-for="(event, idx) in run.trace"
            :key="idx"
            class="sd__trace-row"
            :class="`sd__trace-row--${event.type}`"
          >
            <span class="sd__trace-offset font-mono">{{ event.offset }}</span>
            <span class="sd__trace-msg font-mono">{{ event.message }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Recent prey -->
    <SectionHeader :label="t('strigoi.sections.recentPrey')" />
    <div class="sd__prey-grid">
      <PreyCard v-for="prey in strigoi.recentPrey" :key="prey.id" :prey="prey" />
    </div>

    <!-- Configuration -->
    <SectionHeader :label="t('strigoi.sections.configuration')" />
    <div class="sd__config">
      <div class="sd__config-col">
        <div class="sd__config-title">{{ t('strigoi.config.scheduleTitle') }}</div>
        <table class="sd__config-table">
          <tbody>
            <tr>
              <td class="sd__config-label">{{ t('strigoi.config.cron') }}</td>
              <td class="sd__config-value font-mono">{{ strigoi.configuration.cron }}</td>
            </tr>
            <tr>
              <td class="sd__config-label">{{ t('strigoi.config.nextRun') }}</td>
              <td class="sd__config-value font-mono tabular">{{ formatAbsoluteDate(strigoi.configuration.nextRunAt) }}</td>
            </tr>
            <tr>
              <td class="sd__config-label">{{ t('strigoi.config.disabled') }}</td>
              <td class="sd__config-value font-mono">{{ strigoi.configuration.disabled ? t('strigoi.config.disabledYes') : t('strigoi.config.disabledNo') }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="sd__config-col">
        <div class="sd__config-title">{{ t('strigoi.config.llmTitle') }}</div>
        <table class="sd__config-table">
          <tbody>
            <tr>
              <td class="sd__config-label">{{ t('strigoi.config.tier') }}</td>
              <td class="sd__config-value font-mono">{{ strigoi.configuration.tier }}</td>
            </tr>
            <tr>
              <td class="sd__config-label">{{ t('strigoi.config.dailyBudget') }}</td>
              <td class="sd__config-value font-mono tabular">
                ${{ strigoi.configuration.dailyBudgetUsd.toFixed(2) }}
                <span class="sd__config-used">{{ t('strigoi.config.used', { n: strigoi.configuration.dailyUsedUsd.toFixed(2) }) }}</span>
              </td>
            </tr>
            <tr>
              <td class="sd__config-label">{{ t('strigoi.config.monthlyBudget') }}</td>
              <td class="sd__config-value font-mono tabular">
                ${{ strigoi.configuration.monthlyBudgetUsd.toFixed(2) }}
                <span class="sd__config-used">{{ t('strigoi.config.used', { n: strigoi.configuration.monthlyUsedUsd.toFixed(2) }) }}</span>
              </td>
            </tr>
            <tr>
              <td class="sd__config-label">{{ t('strigoi.config.primary') }}</td>
              <td class="sd__config-value font-mono">{{ strigoi.configuration.primaryProvider }}</td>
            </tr>
            <tr v-if="strigoi.configuration.fallbackProvider">
              <td class="sd__config-label">{{ t('strigoi.config.fallback') }}</td>
              <td class="sd__config-value font-mono">{{ strigoi.configuration.fallbackProvider }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Performance chart -->
    <SectionHeader :label="t('strigoi.sections.performance')" />
    <div class="sd__chart">
      <apexchart type="line" height="260" :options="chartOptions" :series="chartSeries" />
    </div>
  </article>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import VueApexCharts from 'vue3-apexcharts'
import type { StrigoiDetail } from '../api/types'
import { useApi } from '../api'
import { useRelativeTime } from '../composables/useRelativeTime'
import SectionHeader from '../components/common/SectionHeader.vue'
import PreyCard from '../components/common/PreyCard.vue'

// Required: Vue resolves <apexchart> tag by matching the variable name
const apexchart = VueApexCharts

const { t } = useI18n()
const route = useRoute()
const api = useApi()
const { relativeTime } = useRelativeTime()

const strigoi = ref<StrigoiDetail | null>(null)
const loading = ref(true)
// Use a ref<Set> and always replace it with a new Set on mutation so Vue tracks the change
const expandedRuns = ref<Set<string>>(new Set())

onMounted(async () => {
  try {
    strigoi.value = await api.getStrigoiDetail(route.params.name as string)
    if (strigoi.value?.recentRuns[0]) {
      expandedRuns.value = new Set([strigoi.value.recentRuns[0].id])
    }
  } finally {
    loading.value = false
  }
})

function toggleRun(id: string) {
  const next = new Set(expandedRuns.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  expandedRuns.value = next
}

const stateIcon = computed(() => {
  const icons: Record<string, string> = { hunting: '●', resting: '○', paused: '◐', 'budget-hit': '✕' }
  return strigoi.value ? (icons[strigoi.value.state] ?? '○') : ''
})

function formatRunDate(iso: string): string {
  const d = new Date(iso)
  const diffDays = Math.floor((Date.now() - d.getTime()) / 86_400_000)
  const time = d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false })
  if (diffDays === 0) return `${t('strigoi.run.today')} ${time} EST`
  if (diffDays === 1) return `${t('strigoi.run.yesterday')} ${time} EST`
  return `${diffDays}${t('strigoi.run.daysAgo')} ${time} EST`
}

function formatNextRun(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' }) +
    ', ' + d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false }) + ' EST'
}

function formatAbsoluteDate(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleDateString('en-US', { weekday: 'short', day: 'numeric', month: 'long', year: 'numeric' }) +
    ', ' + d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false }) + ' EST'
}

const chartOptions = computed(() => ({
  chart: {
    background: 'transparent', toolbar: { show: false }, zoom: { enabled: false },
    fontFamily: 'JetBrains Mono, IBM Plex Mono, Consolas, monospace',
    foreColor: '#6B6B70',
  },
  colors: ['#A11D2C', '#B8945C'],
  stroke: { width: 2, curve: 'smooth' as const },
  grid: { borderColor: 'rgba(255,255,255,0.04)', xaxis: { lines: { show: false } } },
  xaxis: {
    categories: strigoi.value?.weeklyPerformance.map(w => w.week) ?? [],
    labels: { style: { fontSize: '11px', colors: '#6B6B70' }, rotate: -30 },
    axisBorder: { show: false }, axisTicks: { show: false },
  },
  yaxis: [
    {
      min: 0, max: 1,
      labels: {
        formatter: (v: number) => `${Math.round(v * 100)}%`,
        style: { fontSize: '11px', colors: '#6B6B70' },
      },
    },
    {
      opposite: true, min: 0,
      labels: {
        formatter: (v: number) => String(Math.round(v)),
        style: { fontSize: '11px', colors: '#6B6B70' },
      },
    },
  ],
  legend: { labels: { colors: '#C9C5BC' }, fontSize: '12px' },
  tooltip: { theme: 'dark' as const, style: { fontSize: '12px', fontFamily: 'JetBrains Mono, monospace' } },
  markers: { size: 0 },
}))

const chartSeries = computed(() => [
  { name: t('strigoi.chart.hitRate'), data: strigoi.value?.weeklyPerformance.map(w => w.hitRate) ?? [] },
  { name: t('strigoi.chart.preyCount'), data: strigoi.value?.weeklyPerformance.map(w => w.preyCount) ?? [], yAxisIndex: 1 },
])
</script>

<style scoped>
.sd-loading { max-width: 1280px; margin: 0 auto; padding: var(--space-8) var(--space-6); }
.sd-loading__item { margin-bottom: var(--space-4); }

.sd-notfound { max-width: 1280px; margin: 0 auto; padding: var(--space-8) var(--space-6); color: var(--ash-gray); }
.sd-notfound__link { color: var(--blood-crimson); text-decoration: none; font-size: var(--text-body-sm); }
.sd-notfound__link:hover { color: var(--blood-crimson-bright); }

.sd { max-width: 1280px; margin: 0 auto; padding: var(--space-6) var(--space-6) var(--space-12); }

/* Breadcrumb */
.sd__breadcrumb { display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-6); font-size: var(--text-micro); letter-spacing: 0.02em; }
.sd__bc-link { color: var(--ash-gray); text-decoration: none; }
.sd__bc-link:hover { color: var(--bone-ivory-dim); }
.sd__bc-sep { color: var(--ash-gray); }
.sd__bc-current { color: var(--blood-crimson); }

/* Header */
.sd__header { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--space-8); margin-bottom: var(--space-8); }
.sd__header-identity { display: flex; align-items: flex-start; gap: var(--space-4); }
.sd__bat { font-size: 36px; line-height: 1; flex-shrink: 0; }
.sd__name { font-size: 28px; color: var(--bone-ivory); font-weight: 500; margin: 0 0 var(--space-2) 0; letter-spacing: 0; }
.sd__description { font-size: var(--text-body); color: var(--bone-ivory-dim); margin: 0; }
.sd__reference { font-size: var(--text-body-sm); color: var(--ash-gray); margin-left: var(--space-2); font-style: italic; }

.sd__header-status { flex-shrink: 0; text-align: right; }
.sd__state-pill {
  display: inline-flex; align-items: center; gap: var(--space-1);
  font-size: var(--text-body-sm); font-family: var(--font-mono);
  padding: 2px 10px; border-radius: 2px; border: 1px solid; margin-bottom: var(--space-2);
}
.sd__state-pill--hunting { color: var(--blood-crimson); border-color: rgba(161,29,44,0.4); background-color: rgba(161,29,44,0.08); }
.sd__state-pill--resting { color: var(--ash-gray); border-color: rgba(107,107,112,0.3); }
.sd__state-pill--paused { color: var(--cathedral-gold); border-color: rgba(184,148,92,0.4); }
.sd__state-pill--budget-hit { color: var(--blood-crimson); border-color: rgba(161,29,44,0.4); }
.sd__state-dot { font-size: 10px; }
.sd__schedule { display: flex; align-items: center; gap: var(--space-2); font-size: var(--text-micro); color: var(--ash-gray); font-family: var(--font-mono); }
.sd__schedule-sep { color: var(--ash-gray); }

/* Stat cards */
.sd__stats-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-4); margin-bottom: var(--space-2); }
.sd__stat-card { background-color: var(--crypt-black-elevated); border: 1px solid rgba(184,148,92,0.1); border-radius: 4px; padding: var(--space-6); }
.sd__stat-value { font-size: 48px; line-height: 1.1; letter-spacing: -0.02em; color: var(--bone-ivory); margin-bottom: var(--space-2); }
.sd__stat-value--positive { color: var(--signal-positive); }
.sd__stat-label { font-size: var(--text-body-sm); color: var(--bone-ivory-dim); margin-bottom: var(--space-1); }
.sd__stat-sub { font-size: var(--text-micro); color: var(--ash-gray); }

/* Run timeline */
.sd__runs { background-color: var(--crypt-black-elevated); border: 1px solid rgba(184,148,92,0.1); border-radius: 4px; overflow: hidden; }
.sd__run + .sd__run { border-top: 1px solid rgba(255,255,255,0.05); }
.sd__run-header {
  width: 100%; display: flex; align-items: center; gap: var(--space-3);
  padding: var(--space-4) var(--space-5); background: transparent; border: none;
  color: var(--bone-ivory-dim); cursor: pointer; text-align: left;
  font-size: var(--text-body-sm); transition: background-color var(--transition-fast);
}
.sd__run-header:hover { background-color: rgba(184,148,92,0.04); }
.sd__run-chevron { font-size: 10px; color: var(--ash-gray); transition: transform var(--transition-fast); flex-shrink: 0; }
.sd__run-chevron--open { transform: rotate(90deg); }
.sd__run-date, .sd__run-prey, .sd__run-cost { color: var(--bone-ivory); }
.sd__run-model { color: var(--ash-gray); }
.sd__run-sep { color: var(--ash-gray); }

.sd__run-trace {
  padding: var(--space-2) var(--space-5) var(--space-4) calc(var(--space-5) + 20px);
  display: flex; flex-direction: column; gap: 2px;
}
.sd__trace-row { display: flex; gap: var(--space-4); font-size: var(--text-micro); line-height: 1.6; }
.sd__trace-offset { color: var(--ash-gray); flex-shrink: 0; min-width: 36px; }
.sd__trace-msg { color: var(--bone-ivory-dim); }
.sd__trace-row--start .sd__trace-msg, .sd__trace-row--end .sd__trace-msg { color: var(--blood-crimson); }
.sd__trace-row--llm-call { background-color: rgba(184,148,92,0.05); border-radius: 2px; padding: 1px 4px; margin: 0 -4px; }
.sd__trace-row--llm-call .sd__trace-msg { color: var(--cathedral-gold); }

/* Prey grid */
.sd__prey-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-4); }

/* Config */
.sd__config {
  display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-6);
  background-color: var(--crypt-black-elevated);
  border: 1px solid rgba(184,148,92,0.1); border-radius: 4px; padding: var(--space-5);
}
.sd__config-title { font-size: var(--text-micro); color: var(--ash-gray); letter-spacing: 0.05em; text-transform: uppercase; margin-bottom: var(--space-3); }
.sd__config-table { width: 100%; border-collapse: collapse; }
.sd__config-table tr + tr td { padding-top: var(--space-2); }
.sd__config-label { font-size: var(--text-body-sm); color: var(--ash-gray); padding-right: var(--space-4); white-space: nowrap; vertical-align: top; }
.sd__config-value { font-size: var(--text-body-sm); color: var(--bone-ivory); }
.sd__config-used { color: var(--ash-gray); font-size: var(--text-micro); margin-left: var(--space-1); }

/* Chart */
.sd__chart { background-color: var(--crypt-black-elevated); border: 1px solid rgba(184,148,92,0.1); border-radius: 4px; padding: var(--space-4) var(--space-5); }

@media (max-width: 959.98px) {
  .sd__stats-row { grid-template-columns: 1fr; }
  .sd__prey-grid { grid-template-columns: 1fr; }
  .sd__config { grid-template-columns: 1fr; }
}
</style>
