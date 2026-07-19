import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import DepotsView from './DepotsView.vue'
import PriceChart from '../components/common/PriceChart.vue'
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
        name: null, assetType: null, valueDate: null, nativePrice: null, nativeCurrency: null,
      },
      {
        symbol: 'ABB', qty: 20, avgEntryPrice: 35, marketValue: 770,
        unrealizedPl: -5, unrealizedPlPct: -0.5, price: 38.5,
        dayChangePercent: null, weightPct: 40, currency: 'USD',
        name: null, assetType: null, valueDate: null, nativePrice: null, nativeCurrency: null,
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
const mockGetDepots = vi.fn(async () => depotsResponse)

vi.mock('../api', () => ({
  useApi: () => ({
    getDepots: mockGetDepots,
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
  setActivePinia(createPinia())
  router.push('/depots')
  getDepotChartImpl = async () => mockDepotChart
  mockGetDepots.mockClear()
})

describe('DepotsView', () => {
  it('renders exactly one depot section — the selected depot — via a dropdown selector', async () => {
    depotsResponse = { depots: [depot({ id: 'depot-1' }), depot({ id: 'saxo-live-1', environment: 'live' })], error: null }
    const w = mountView()
    await flushPromises()

    const sections = w.findAll('[data-testid="depot-section"]')
    expect(sections).toHaveLength(1)

    const select = w.find('[data-testid="depot-select"]')
    expect(select.exists()).toBe(true)
    const options = select.findAll('option')
    expect(options).toHaveLength(2)
    expect(options[0].text()).toContain('depot-1')
    expect(options[0].text()).toContain('alpaca')
    expect(options[1].text()).toContain('saxo-live-1')
  })

  it('switches the rendered depot when a different option is selected', async () => {
    depotsResponse = { depots: [depot({ id: 'depot-1' }), depot({ id: 'saxo-live-1', environment: 'live' })], error: null }
    const w = mountView()
    await flushPromises()

    expect(w.find('[data-testid="depot-section"]').attributes('data-connection')).toBe('depot-1')

    await w.find('[data-testid="depot-select"]').setValue('saxo-live-1')
    await flushPromises()

    const sections = w.findAll('[data-testid="depot-section"]')
    expect(sections).toHaveLength(1)
    expect(sections[0].attributes('data-connection')).toBe('saxo-live-1')
  })

  it('persists the selected depot in localStorage and restores it on next mount', async () => {
    depotsResponse = { depots: [depot({ id: 'depot-1' }), depot({ id: 'saxo-live-1', environment: 'live' })], error: null }
    const w = mountView()
    await flushPromises()

    await w.find('[data-testid="depot-select"]').setValue('saxo-live-1')
    await flushPromises()
    expect(localStorage.getItem('dracul.depots.selected')).toBe('saxo-live-1')

    const w2 = mountView()
    await flushPromises()
    expect(w2.find('[data-testid="depot-section"]').attributes('data-connection')).toBe('saxo-live-1')
  })

  it('falls back to the first depot when the persisted selection no longer exists', async () => {
    localStorage.setItem('dracul.depots.selected', 'ghost-depot')
    depotsResponse = { depots: [depot({ id: 'depot-1' }), depot({ id: 'saxo-live-1', environment: 'live' })], error: null }
    const w = mountView()
    await flushPromises()

    expect(w.find('[data-testid="depot-section"]').attributes('data-connection')).toBe('depot-1')
  })

  it('shows the selected depot cash and no cross-depot total', async () => {
    depotsResponse = {
      depots: [
        depot({ id: 'depot-1', account: { cash: 500, equity: 4795, buyingPower: 1000, currency: 'USD', status: 'ACTIVE', asOf: '2026-07-11T08:00:00Z' } }),
        depot({ id: 'saxo-live-1', environment: 'live', account: { cash: 9999, equity: 4795, buyingPower: 1000, currency: 'USD', status: 'ACTIVE', asOf: '2026-07-11T08:00:00Z' } }),
      ],
      error: null,
    }
    const w = mountView()
    await flushPromises()

    const cash = w.find('[data-testid="depots-total-cash"]')
    expect(cash.exists()).toBe(true)
    expect(cash.text()).toContain('500')
    expect(w.find('[data-testid="depots-summary"]').exists()).toBe(false)

    await w.find('[data-testid="depot-select"]').setValue('saxo-live-1')
    await flushPromises()
    expect(w.find('[data-testid="depots-total-cash"]').text()).toContain('9.999')
  })

  it('renders an absolute "Stand:" timestamp, never relative wording', async () => {
    depotsResponse = { depots: [depot({ id: 'depot-1' })], error: null }
    const w = mountView()
    await flushPromises()

    const asOf = w.find('[data-testid="depot-asof"]')
    expect(asOf.exists()).toBe(true)
    expect(asOf.text()).toMatch(/\d{2}\.\d{2}\., \d{2}:\d{2}:\d{2}/)
    expect(asOf.text()).not.toMatch(/vor|gerade eben|ago/i)
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

  it('shows an inline alert for an errored depot, and the other depot still renders once selected', async () => {
    depotsResponse = {
      depots: [
        depot({ id: 'depot-1', error: 'connection refused', account: null, aggregates: null, positions: [] }),
        depot({ id: 'saxo-live-1' }),
      ],
      error: null,
    }
    const w = mountView()
    await flushPromises()

    expect(w.findAll('[data-testid="depot-section"]')).toHaveLength(1)
    expect(w.find('[data-testid="depot-error"]').exists()).toBe(true)
    expect(w.find('[data-testid="depot-error"]').text()).toContain('connection refused')

    // switching to the healthy depot shows its positions table
    await w.find('[data-testid="depot-select"]').setValue('saxo-live-1')
    await flushPromises()
    expect(w.find('[data-testid="depot-positions-table"]').exists()).toBe(true)
    expect(w.find('[data-testid="depot-error"]').exists()).toBe(false)
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

    const chart = w.findComponent(PriceChart)
    expect(chart.props('series')[0].data).toEqual([999, 999])
  })

  it('reloads the chart when switching to a different depot (with DepotSection remount)', async () => {
    depotsResponse = { depots: [depot({ id: 'depot-1' }), depot({ id: 'saxo-live-1', environment: 'live' })], error: null }

    // Track which connections were requested
    const requestedConnections: string[] = []
    getDepotChartImpl = (connection: string, _range: ChartRange) => {
      requestedConnections.push(connection)
      return Promise.resolve(mockDepotChart)
    }

    const w = mountView()
    await flushPromises()

    // Clear the history from the initial mount
    requestedConnections.length = 0

    // Switch to the second depot
    await w.find('[data-testid="depot-select"]').setValue('saxo-live-1')
    await flushPromises()

    // Verify getDepotChart was called with the new connection ID
    expect(requestedConnections).toContain('saxo-live-1')
  })

  it('renders order rows with readable labels, a header row, and a status pill', async () => {
    depotsResponse = {
      depots: [
        depot({
          id: 'depot-1',
          orders: [
            { brokerOrderId: 'o1', symbol: 'PSMT', side: 'buy', qty: 5, type: 'limit', status: 'working', role: null, parentId: null },
            { brokerOrderId: 'o2', symbol: 'PSMT', side: null, qty: 5, type: 'stopiftraded', status: 'notworking', role: null, parentId: null },
          ],
        }),
      ],
      error: null,
    }
    const w = mountView()
    await flushPromises()

    const orders = w.find('[data-testid="depot-orders"]')
    expect(orders.exists()).toBe(true)
    const text = orders.text()

    // Column header row is present.
    expect(text).toContain('Richtung')
    expect(text).toContain('Stück')

    // Readable labels, not raw broker enums.
    expect(text).toContain('Kauf')
    expect(text).toContain('aktiv')
    expect(text).toContain('inaktiv')
    expect(text).toContain('Stop')
    expect(text).not.toContain('working') // neither 'working' nor 'notworking'
    expect(text).not.toContain('stopiftraded')

    // Missing side renders as a dash.
    expect(text).toContain('—')

    // Status is rendered as a TagPill.
    expect(orders.findAll('.tag-pill').length).toBe(2)
  })

  it('loads with the display cache on mount, and bypasses it via the refresh button', async () => {
    depotsResponse = { depots: [depot({ id: 'depot-1' })], error: null }
    const w = mountView()
    await flushPromises()

    expect(mockGetDepots).toHaveBeenCalledTimes(1)
    expect(mockGetDepots).toHaveBeenNthCalledWith(1, false)

    await w.find('[data-testid="depots-refresh"]').trigger('click')
    await flushPromises()

    expect(mockGetDepots).toHaveBeenCalledTimes(2)
    expect(mockGetDepots).toHaveBeenNthCalledWith(2, true)
  })

  it('appends " · LIVE" to the depot dropdown option text for live environments', async () => {
    depotsResponse = { depots: [depot({ id: 'depot-1', environment: 'paper' }), depot({ id: 'saxo-live-1', environment: 'live' })], error: null }
    const w = mountView()
    await flushPromises()

    const options = w.find('[data-testid="depot-select"]').findAll('option')
    expect(options[0].text()).not.toContain('LIVE')
    expect(options[1].text()).toContain('LIVE')
  })
})
