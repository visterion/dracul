import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import de from '../../i18n/locales/de'
import DepotSection from './DepotSection.vue'
import PriceChart from '../common/PriceChart.vue'
import type { Depot, DepotEquityCurve } from '../../api/types'

let curveResponse: DepotEquityCurve = { granularity: 'DAILY', points: [], relative: null, currency: null }

vi.mock('../../api', () => ({
  useApi: () => ({
    getDepotChart: vi.fn(async () => curveResponse),
    getDepotHistory: vi.fn(),
  }),
}))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })
const router = createRouter({ history: createMemoryHistory(), routes: [
  { path: '/depots', name: 'depots', component: { template: '<div/>' } },
  { path: '/depots/:connection/:symbol', name: 'depot-position-detail', component: { template: '<div/>' } },
] })

const baseDepot: Depot = {
  id: 'conn-1', provider: 'alpaca', environment: 'paper', status: 'ok', probedAt: null, error: null,
  account: { equity: 10000, cash: 5000, buyingPower: 5000, currency: 'EUR', status: 'OK', asOf: '2026-01-09T20:04:23Z' },
  aggregates: null, positions: [], orders: [], asOf: null,
}

function mountSection(overrides: Partial<Depot> = {}) {
  const depot: Depot = { ...baseDepot, ...overrides }
  return mount(DepotSection, { props: { depot }, global: { plugins: [i18n, router] } })
}

async function flush() {
  await Promise.resolve()
  await Promise.resolve()
}

describe('DepotSection curve — render guard, intraday, hints', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    curveResponse = { granularity: 'DAILY', points: [], relative: null, currency: null }
  })

  it('shows the empty state and no chart when the series has no points', async () => {
    curveResponse = { granularity: 'DAILY', points: [], relative: null, currency: null }
    const w = mountSection()
    await flush()
    expect(w.find('[data-testid="depot-chart-empty"]').exists()).toBe(true)
    expect(w.findComponent(PriceChart).exists()).toBe(false)
  })

  it('renders a one-point series with the too-short hint', async () => {
    curveResponse = {
      granularity: 'DAILY',
      points: [{ t: '2026-01-05', value: 100, source: 'MEASURED' }],
      relative: null,
      currency: 'EUR',
    }
    const w = mountSection()
    await flush()
    expect(w.findComponent(PriceChart).exists()).toBe(true)
    const hint = w.get('[data-testid="depot-chart-hint"]')
    expect(hint.text()).toContain('2026-01-05')
    expect(hint.text()).toContain('Reihe beginnt am')
  })

  // `useDisplayMode` reads localStorage exactly once, into a module-level
  // singleton ref, when it is first imported — setting localStorage inside an
  // `it()` body runs long after that static import already fired for every
  // other test in this file. `vi.resetModules()` plus a fully self-contained
  // dynamic import graph (Vue, i18n, router and the component under test all
  // re-imported together) forces a fresh singleton that actually observes the
  // value we just wrote, without touching or mocking the composable itself.
  it('formats money when the percent mode is stored but no relative series exists', async () => {
    vi.resetModules()
    localStorage.setItem('dracul.depots.displayMode', 'pct')

    const { mount: freshMount } = await import('@vue/test-utils')
    const { createI18n: freshCreateI18n } = await import('vue-i18n')
    const { createRouter: freshCreateRouter, createMemoryHistory: freshCreateMemoryHistory } = await import('vue-router')
    const { default: freshDe } = await import('../../i18n/locales/de')
    const { default: FreshDepotSection } = await import('./DepotSection.vue')
    const { default: FreshPriceChart } = await import('../common/PriceChart.vue')

    const freshI18n = freshCreateI18n({ legacy: false, locale: 'de', messages: { de: freshDe } })
    const freshRouter = freshCreateRouter({
      history: freshCreateMemoryHistory(),
      routes: [
        { path: '/depots', name: 'depots', component: { template: '<div/>' } },
        { path: '/depots/:connection/:symbol', name: 'depot-position-detail', component: { template: '<div/>' } },
      ],
    })

    curveResponse = {
      granularity: 'DAILY',
      points: [
        { t: '2026-01-05', value: 100, source: 'MEASURED' },
        { t: '2026-01-06', value: 110, source: 'MEASURED' },
      ],
      relative: null,
      currency: 'EUR',
    }

    const w = freshMount(FreshDepotSection, {
      props: { depot: baseDepot },
      global: { plugins: [freshI18n, freshRouter] },
    })
    await flush()
    const chart = w.findComponent(FreshPriceChart)
    expect(chart.exists()).toBe(true)
    const formatted = chart.props('valueFormatter')!(110)
    expect(formatted).not.toContain('%')
  })

  it('formats intraday points with distinct times', async () => {
    const points = []
    let hour = 13
    let minute = 5
    for (let i = 0; i < 36; i++) {
      const hh = String(hour).padStart(2, '0')
      const mm = String(minute).padStart(2, '0')
      points.push({ t: `2026-01-05T${hh}:${mm}:00Z`, value: 100 + i, source: 'MEASURED' as const })
      minute += 15
      if (minute >= 60) {
        minute -= 60
        hour += 1
      }
    }
    curveResponse = { granularity: 'INTRADAY', points, relative: null, currency: 'EUR' }
    const w = mountSection()
    await flush()
    const chart = w.findComponent(PriceChart)
    const chartTimes = chart.props('times') as string[]
    const chartLabels = (chart.props('labels') as { i: number; t: string }[]).map(l => l.t)
    expect(new Set(chartTimes)).toHaveProperty('size', 36)
    expect(chartLabels.join()).not.toContain('T')
  })

  it('never shows the gap hint for an intraday series', async () => {
    const points = []
    let hour = 13
    let minute = 5
    for (let i = 0; i < 36; i++) {
      const hh = String(hour).padStart(2, '0')
      const mm = String(minute).padStart(2, '0')
      points.push({ t: `2026-01-05T${hh}:${mm}:00Z`, value: 100 + i, source: 'MEASURED' as const })
      minute += 15
      if (minute >= 60) {
        minute -= 60
        hour += 1
      }
    }
    curveResponse = { granularity: 'INTRADAY', points, relative: null, currency: 'EUR' }
    const w = mountSection()
    await flush()
    expect(w.find('[data-testid="depot-chart-hint"]').exists()).toBe(false)
  })

  it('counts weekday gaps between the first and last point', async () => {
    // Mon 05.01., Wed 07.01., Fri 09.01. -> m = 5 weekdays, 3 points -> "2 von 5"
    curveResponse = {
      granularity: 'DAILY',
      points: [
        { t: '2026-01-05', value: 100, source: 'MEASURED' },
        { t: '2026-01-07', value: 105, source: 'MEASURED' },
        { t: '2026-01-09', value: 103, source: 'MEASURED' },
      ],
      relative: [
        { t: '2026-01-05', pct: 0 },
        { t: '2026-01-07', pct: 5 },
        { t: '2026-01-09', pct: 3 },
      ],
      currency: 'EUR',
    }
    const w = mountSection()
    await flush()
    const hint = w.get('[data-testid="depot-chart-hint"]')
    expect(hint.text()).toContain('2 von 5')
  })

  it('shows no gap hint for a short but complete series', async () => {
    // Mon 05.01., Tue 06.01., Wed 07.01. — three consecutive weekdays, no gaps.
    curveResponse = {
      granularity: 'DAILY',
      points: [
        { t: '2026-01-05', value: 100, source: 'MEASURED' },
        { t: '2026-01-06', value: 101, source: 'MEASURED' },
        { t: '2026-01-07', value: 102, source: 'MEASURED' },
      ],
      relative: [
        { t: '2026-01-05', pct: 0 },
        { t: '2026-01-06', pct: 1 },
        { t: '2026-01-07', pct: 2 },
      ],
      currency: 'EUR',
    }
    const w = mountSection()
    await flush()
    expect(w.find('[data-testid="depot-chart-hint"]').exists()).toBe(false)
  })

  it('falls back to the account currency when the curve has none', async () => {
    curveResponse = {
      granularity: 'DAILY',
      points: [
        { t: '2026-01-05', value: 100, source: 'MEASURED' },
        { t: '2026-01-06', value: 110, source: 'MEASURED' },
      ],
      relative: [
        { t: '2026-01-05', pct: 0 },
        { t: '2026-01-06', pct: 10 },
      ],
      currency: null,
    }
    const w = mountSection({
      account: { equity: 10000, cash: 5000, buyingPower: 5000, currency: 'JPY', status: 'OK', asOf: '2026-01-09T20:04:23Z' },
    })
    await flush()
    const chart = w.findComponent(PriceChart)
    const formatted = chart.props('valueFormatter')!(110)
    expect(formatted).not.toContain('USD')
    expect(formatted).not.toContain('$')
  })
})
