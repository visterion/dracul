import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import DepotsView from './DepotsView.vue'
import LineChart from '../components/common/LineChart.vue'
import de from '../i18n/locales/de'
import type { Depot, DepotsResponse, DepotChart, ChartRange } from '../api/types'
import { mockDepotChart } from '../mocks/depots'

function chartFixture(markerValue: number): DepotChart {
  return {
    points: [
      { t: '2026-07-01', value: markerValue },
      { t: '2026-07-02', value: markerValue },
    ],
    relative: [
      { t: '2026-07-01', pct: markerValue },
      { t: '2026-07-02', pct: markerValue },
    ],
    partial: false,
  }
}

function depot(overrides: Partial<Depot> = {}): Depot {
  return {
    id: 'depot-1',
    provider: 'alpaca',
    environment: 'paper',
    status: 'connected',
    probedAt: '2026-07-11T08:00:00Z',
    error: null,
    account: { cash: 500, equity: 4795, buyingPower: 1000, currency: 'USD', status: 'ACTIVE', asOf: '2026-07-11T08:00:00Z' },
    aggregates: { investedValue: 4000, totalUnrealizedPl: 145, totalUnrealizedPlPct: 3.6, dayChangeAbs: 12.5, dayChangePct: 0.3 },
    positions: [
      {
        symbol: 'NVDA', qty: 10, avgEntryPrice: 120, marketValue: 1350,
        unrealizedPl: 150, unrealizedPlPct: 12.5, price: 135,
        dayChangePercent: 1.2, weightPct: 60, currency: 'USD',
      },
      {
        symbol: 'ABB', qty: 20, avgEntryPrice: 35, marketValue: 770,
        unrealizedPl: -5, unrealizedPlPct: -0.5, price: 38.5,
        dayChangePercent: null, weightPct: 40, currency: 'USD',
      },
    ],
    orders: [],
    asOf: '2026-07-11T08:00:00Z',
    ...overrides,
  }
}

let depotsResponse: DepotsResponse
let getDepotChartImpl: (connection: string, range: ChartRange) => Promise<DepotChart> =
  async () => mockDepotChart

vi.mock('../api', () => ({
  useApi: () => ({
    getDepots: vi.fn(async () => depotsResponse),
    getDepotChart: vi.fn((connection: string, range: ChartRange) => getDepotChartImpl(connection, range)),
  }),
}))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })
const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/depots', name: 'depots', component: DepotsView },
    { path: '/depots/:connection/:symbol', name: 'depot-position-detail', component: { template: '<div/>' } },
  ],
})

function mountView() {
  return mount(DepotsView, { global: { plugins: [i18n, router] } })
}

beforeEach(() => {
  localStorage.clear()
  router.push('/depots')
  getDepotChartImpl = async () => mockDepotChart
})

describe('DepotsView', () => {
  it('renders one section per depot', async () => {
    depotsResponse = { depots: [depot({ id: 'depot-1' }), depot({ id: 'saxo-live-1', environment: 'live' })], error: null }
    const w = mountView()
    await flushPromises()

    const sections = w.findAll('[data-testid="depot-section"]')
    expect(sections).toHaveLength(2)
  })

  it('shows total cash in the summary bar', async () => {
    depotsResponse = { depots: [depot({ id: 'depot-1' })], error: null }
    const w = mountView()
    await flushPromises()

    const cash = w.find('[data-testid="depots-total-cash"]')
    expect(cash.exists()).toBe(true)
    expect(cash.text()).toContain('500')
  })

  it('flips all P&L cells from currency to percent when one is clicked', async () => {
    depotsResponse = { depots: [depot({ id: 'depot-1' })], error: null }
    const w = mountView()
    await flushPromises()

    const cells = w.findAll('[data-testid="pnl-cell"]')
    expect(cells.length).toBeGreaterThan(1)
    const before = cells.map(c => c.text())
    expect(before.some(t => t.includes('$') || t.includes('US$'))).toBe(true)

    await cells[0].trigger('click')
    await flushPromises()

    const after = w.findAll('[data-testid="pnl-cell"]').map(c => c.text())
    expect(after.every(t => t.includes('%') || t === '—')).toBe(true)
  })

  it('shows an inline alert for an errored depot while the other still renders', async () => {
    depotsResponse = {
      depots: [
        depot({ id: 'depot-1', error: 'connection refused', account: null, aggregates: null, positions: [] }),
        depot({ id: 'saxo-live-1' }),
      ],
      error: null,
    }
    const w = mountView()
    await flushPromises()

    const sections = w.findAll('[data-testid="depot-section"]')
    expect(sections).toHaveLength(2)
    expect(w.find('[data-testid="depot-error"]').exists()).toBe(true)
    expect(w.find('[data-testid="depot-error"]').text()).toContain('connection refused')
    // the healthy depot still shows its positions table
    expect(w.find('[data-testid="depot-positions-table"]').exists()).toBe(true)
  })

  it('renders null day-change values as a dash, never as 0', async () => {
    depotsResponse = { depots: [depot({ id: 'depot-1' })], error: null }
    const w = mountView()
    await flushPromises()

    // ABB has dayChangePercent: null in the fixture above; switch to the "Heute" metric
    // so the change column actually renders day-change values.
    await w.find('[data-testid="depot-metric-select"]').setValue('today')
    await flushPromises()

    const changeCells = w.findAll('[data-testid="change-cell"]').map(c => c.text())
    expect(changeCells).toContain('—')
    expect(changeCells.some(t => t === '0' || t.includes('0,00'))).toBe(false)
  })

  it('metric selector switches the change column between since-buy and day-change', async () => {
    depotsResponse = { depots: [depot({ id: 'depot-1' })], error: null }
    const w = mountView()
    await flushPromises()

    // Default metric is "Seit Kauf" (sinceBuy): both rows have a real unrealizedPl value.
    const sinceBuyCells = w.findAll('[data-testid="change-cell"]').map(c => c.text())
    expect(sinceBuyCells.some(t => t === '—')).toBe(false)

    await w.find('[data-testid="depot-metric-select"]').setValue('today')
    await flushPromises()

    // "Heute": ABB's dayChangePercent is null, so its cell must now show the dash,
    // and the rendered values must differ from the since-buy figures.
    const todayCells = w.findAll('[data-testid="change-cell"]').map(c => c.text())
    expect(todayCells).toContain('—')
    expect(todayCells).not.toEqual(sinceBuyCells)
  })

  it('ignores a stale chart response when the range is switched again before it resolves', async () => {
    depotsResponse = { depots: [depot({ id: 'depot-1' })], error: null }

    const resolvers: Partial<Record<ChartRange, (v: DepotChart) => void>> = {}
    getDepotChartImpl = (_connection, range) =>
      new Promise(resolve => {
        resolvers[range] = resolve
      })

    const w = mountView()
    await flushPromises()
    resolvers['1m']?.(chartFixture(1)) // resolve the initial onMounted load ('1m' is the default range)
    await flushPromises()

    await w.find('[data-testid="depot-range-1w"]').trigger('click')
    await flushPromises()
    await w.find('[data-testid="depot-range-1y"]').trigger('click')
    await flushPromises()

    // Resolve out of order: the stale '1w' request resolves AFTER the newer '1y' one.
    resolvers['1y']?.(chartFixture(999))
    await flushPromises()
    resolvers['1w']?.(chartFixture(111))
    await flushPromises()

    const chart = w.findComponent(LineChart)
    expect(chart.props('series')[0].data).toEqual([999, 999])
  })
})
