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
    // Reuse the exact points array from the "counts weekday gaps" case (Mon 05.01, Wed 07.01,
    // Fri 09.01 — a real, well-formed gap that `weekdaysBetween` can parse either way) and mount
    // it twice, flipping only `granularity`. This isolates the DAILY-only guard as the *sole*
    // variable: with `granularity: 'DAILY'` the same data produces the gap hint (positive
    // control — proves the guard isn't just masking a chartHint that never fires), and with
    // `granularity: 'INTRADAY'` it must not. Deleting the guard collapses both branches to the
    // same (hint-showing) behavior, which the second assertion below then catches.
    //
    // A "real" 36-point same-day INTRADAY fixture (see "formats intraday points with distinct
    // times") is deliberately not reused here: `weekdaysBetween` mis-parses full ISO instants
    // (a separate, parked issue — see the brief's deferred-Minors list), which would make the
    // guard's removal invisible for that shape of data and defeat the point of this test.
    const points = [
      { t: '2026-01-05', value: 100, source: 'MEASURED' as const },
      { t: '2026-01-07', value: 105, source: 'MEASURED' as const },
      { t: '2026-01-09', value: 103, source: 'MEASURED' as const },
    ]

    curveResponse = { granularity: 'DAILY', points, relative: null, currency: 'EUR' }
    const daily = mountSection()
    await flush()
    expect(daily.find('[data-testid="depot-chart-hint"]').exists()).toBe(true)

    curveResponse = { granularity: 'INTRADAY', points, relative: null, currency: 'EUR' }
    const w = mountSection()
    await flush()
    expect(w.find('[data-testid="depot-chart-hint"]').exists()).toBe(false)
  })

  it('counts weekday gaps between the first and last point', async () => {
    // Mon 05.01., Wed 07.01., Fri 09.01., Mon 12.01. -> the span crosses a weekend (Sat
    // 10.01./Sun 11.01.), so weekdays (6: Mon,Tue,Wed,Thu,Fri,Mon) and calendar days (8) differ —
    // this is what distinguishes the weekend filter from an unfiltered calendar-day count. 4
    // points -> "2 von 6". Without the `day !== 0 && day !== 6` filter the denominator would be
    // the 8 calendar days and the count would read "4 von 8" instead.
    curveResponse = {
      granularity: 'DAILY',
      points: [
        { t: '2026-01-05', value: 100, source: 'MEASURED' },
        { t: '2026-01-07', value: 105, source: 'MEASURED' },
        { t: '2026-01-09', value: 103, source: 'MEASURED' },
        { t: '2026-01-12', value: 108, source: 'MEASURED' },
      ],
      relative: [
        { t: '2026-01-05', pct: 0 },
        { t: '2026-01-07', pct: 5 },
        { t: '2026-01-09', pct: 3 },
        { t: '2026-01-12', pct: 8 },
      ],
      currency: 'EUR',
    }
    const w = mountSection()
    await flush()
    const hint = w.get('[data-testid="depot-chart-hint"]')
    // "Wochentagen", not "Handelstagen": weekdaysBetween counts Mon-Fri calendar days, not
    // trading days, so a week with a public holiday would still count as gap-free here.
    expect(hint.text()).toContain('2 von 6 Wochentagen')
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

  it('prefers the curve currency over the account currency when both are set', async () => {
    // Curve says EUR, account says JPY — deleting `chart.value?.currency ??` in
    // formatChartValue would fall straight through to the account currency and this would
    // format as JPY (no decimals, ¥ symbol) instead of EUR (2 decimals, € symbol).
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
      currency: 'EUR',
    }
    const w = mountSection({
      account: { equity: 10000, cash: 5000, buyingPower: 5000, currency: 'JPY', status: 'OK', asOf: '2026-01-09T20:04:23Z' },
    })
    await flush()
    const chart = w.findComponent(PriceChart)
    const formatted = chart.props('valueFormatter')!(110)
    expect(formatted).toContain('€')
    expect(formatted).not.toContain('¥')
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
    expect(formatted).toContain('¥')
  })

  it('draws the reconstructed part as a separate dashed series', async () => {
    curveResponse = {
      granularity: 'DAILY',
      currency: 'EUR',
      relative: null,
      points: [
        { t: '2026-03-03', value: 100, source: 'RECONSTRUCTED' },
        { t: '2026-03-04', value: 110, source: 'RECONSTRUCTED' },
        { t: '2026-03-05', value: 120, source: 'MEASURED' },
      ],
    }
    const w = mountSection()
    await flush()

    const series = w.findComponent(PriceChart).props('series') as
      { data: (number | null)[]; dashed?: boolean }[]

    expect(series).toHaveLength(2)
    expect(series[0].dashed).toBe(true)
    expect(series[0].data).toEqual([100, 110, null])
    expect(series[1].dashed).toBeFalsy()
    // The seam point belongs to both, otherwise the line breaks in two
    expect(series[1].data).toEqual([null, 110, 120])
    expect(w.find('[data-testid="depot-chart-reconstructed"]').exists()).toBe(true)
  })

  it('does not count reconstructed days as gaps', async () => {
    // Mon and Wed reconstructed (Tue absent), Thu+Fri measured. The measured stretch is
    // complete, so no hint is correct. Without the MEASURED filter the whole span
    // 02.03–06.03 would be counted — five weekdays against four points — and a gap
    // would be reported for a curve that has a point on every day it covers.
    curveResponse = {
      granularity: 'DAILY',
      currency: 'EUR',
      relative: null,
      points: [
        { t: '2026-03-02', value: 100, source: 'RECONSTRUCTED' },
        { t: '2026-03-04', value: 102, source: 'RECONSTRUCTED' },
        { t: '2026-03-05', value: 103, source: 'MEASURED' },
        { t: '2026-03-06', value: 104, source: 'MEASURED' },
      ],
    }
    const w = mountSection()
    await flush()

    expect(w.find('[data-testid="depot-chart-hint"]').exists()).toBe(false)
    expect(w.find('[data-testid="depot-chart-reconstructed"]').exists()).toBe(true)
  })

  it('still reports a gap between two measured days', async () => {
    curveResponse = {
      granularity: 'DAILY',
      currency: 'EUR',
      relative: null,
      points: [
        { t: '2026-03-02', value: 100, source: 'MEASURED' },
        { t: '2026-03-06', value: 104, source: 'MEASURED' },
      ],
    }
    const w = mountSection()
    await flush()

    expect(w.get('[data-testid="depot-chart-hint"]').text()).toContain('3 von 5')
    expect(w.find('[data-testid="depot-chart-reconstructed"]').exists()).toBe(false)
  })
})
