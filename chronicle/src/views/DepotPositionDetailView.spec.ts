import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import DepotPositionDetailView from './DepotPositionDetailView.vue'
import de from '../i18n/locales/de'
import type {
  DepotPositionView, DepotOrderView, DepotChart, InstrumentInfo, ChartRange, RunTranscript,
} from '../api/types'

function position(overrides: Partial<DepotPositionView> = {}): DepotPositionView {
  return {
    symbol: 'NVDA', qty: 10, avgEntryPrice: 120, marketValue: 1350,
    unrealizedPl: 150, unrealizedPlPct: 12.5, price: 135,
    dayChangePercent: 1.2, weightPct: 60, currency: 'USD',
    name: null, assetType: null, valueDate: null,
    nativePrice: null, nativeCurrency: null,
    ...overrides,
  }
}

function order(overrides: Partial<DepotOrderView> = {}): DepotOrderView {
  return {
    brokerOrderId: 'o-1', symbol: 'NVDA', side: 'buy', qty: 10,
    type: 'market', status: 'filled', role: 'entry',
    ...overrides,
  }
}

function chart(values: number[]): DepotChart {
  return { points: values.map((value, i) => ({ t: `2026-07-${String(i + 1).padStart(2, '0')}`, value })) }
}

const emptyInfo: InstrumentInfo = {
  symbol: 'NVDA', profile: null, news: null, earnings: null,
  analystEstimates: null, earningsEstimates: null, fundamentalScore: null,
  fundamentals: null, insiderActivity: null,
}

const fullInfo: InstrumentInfo = {
  symbol: 'NVDA',
  profile: { symbol: 'NVDA', name: 'NVIDIA Corporation', industry: 'Semiconductors', exchange: 'NASDAQ', marketCap: 3_200_000_000_000, description: 'Designs GPUs.' },
  news: { news: [{ headline: 'NVIDIA unveils next-gen GPU', source: 'Reuters', publishedAt: '2026-07-05T12:00:00Z' }] },
  earnings: { earnings: [{ symbol: 'NVDA', period: 'Q2 2026', reportDate: '2026-08-20', epsEstimate: 0.85, epsActual: null }] },
  analystEstimates: { symbol: 'NVDA', recommendations: [{ period: '2026-07', buy: 38, hold: 5, sell: 1, strongBuy: 20, strongSell: 0 }] },
  earningsEstimates: { symbol: 'NVDA', estimates: [{ period: 'Q2 2026', epsAvg: 0.85, epsLow: 0.78, epsHigh: 0.92, revenueAvg: 45_000_000_000 }] },
  fundamentalScore: { symbol: 'NVDA', score: 8 },
  fundamentals: { symbol: 'NVDA', peRatio: 42.3, pbRatio: 30.1, dividendYield: 0.03 },
  insiderActivity: { transactions: [{ ticker: 'NVDA', insider: 'Jensen Huang', transactionDate: '2026-06-15', type: 'sell', shares: 50000, price: 132.10 }] },
}

const analystDetailInfo: InstrumentInfo = {
  ...fullInfo,
  analystEstimates: {
    symbol: 'NVDA',
    recommendations: [{ period: '2026-07', strongBuy: 8, buy: 4, hold: 3, sell: 1, strongSell: 0 }],
  },
}

let getDepotPositionImpl: (connection: string, symbol: string) => Promise<{ position: DepotPositionView; orders: DepotOrderView[]; asOf: string | null; runId: string | null }> =
  async () => ({ position: position(), orders: [order()], asOf: '2026-07-11T08:00:00Z', runId: null })
let getInstrumentChartImpl: (symbol: string, range: ChartRange) => Promise<DepotChart> =
  async () => chart([100, 110, 120])
let getInstrumentInfoImpl: (symbol: string) => Promise<InstrumentInfo> =
  async () => emptyInfo
let getRunTranscriptImpl: (runId: string) => Promise<RunTranscript> =
  async runId => ({ transcript: { runId, note: 'mock transcript' }, expired: false })

const getDepotPositionSpy = vi.fn((connection: string, symbol: string) => getDepotPositionImpl(connection, symbol))
const getInstrumentChartSpy = vi.fn((symbol: string, range: ChartRange) => getInstrumentChartImpl(symbol, range))
const getInstrumentInfoSpy = vi.fn((symbol: string) => getInstrumentInfoImpl(symbol))
const getRunTranscriptSpy = vi.fn((runId: string) => getRunTranscriptImpl(runId))

vi.mock('../api', () => ({
  useApi: () => ({
    getDepotPosition: getDepotPositionSpy,
    getInstrumentChart: getInstrumentChartSpy,
    getInstrumentInfo: getInstrumentInfoSpy,
    getRunTranscript: getRunTranscriptSpy,
  }),
}))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })
const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/depots/:connection/:symbol', name: 'depot-position-detail', component: DepotPositionDetailView },
    { path: '/depots', name: 'depots', component: { template: '<div/>' } },
  ],
})

function mountView() {
  return mount(DepotPositionDetailView, { global: { plugins: [i18n, router] } })
}

beforeEach(() => {
  localStorage.clear()
  router.push('/depots/depot-1/NVDA')
  getDepotPositionImpl = async () => ({ position: position(), orders: [order()], asOf: '2026-07-11T08:00:00Z', runId: null })
  getInstrumentChartImpl = async () => chart([100, 110, 120])
  getInstrumentInfoImpl = async () => emptyInfo
  getRunTranscriptImpl = async runId => ({ transcript: { runId, note: 'mock transcript' }, expired: false })
  getDepotPositionSpy.mockClear()
  getInstrumentChartSpy.mockClear()
  getInstrumentInfoSpy.mockClear()
  getRunTranscriptSpy.mockClear()
})

describe('DepotPositionDetailView', () => {
  it('renders the price header and stat tiles from the mock', async () => {
    const w = mountView()
    await flushPromises()

    expect(w.find('[data-testid="pd-symbol"]').text()).toContain('NVDA')
    expect(w.find('[data-testid="pd-price"]').text()).toContain('135')
    expect(w.find('[data-testid="pd-stat-position"]').exists()).toBe(true)
    expect(w.find('[data-testid="pd-stat-performance"]').exists()).toBe(true)
    expect(w.find('[data-testid="pd-stat-qty"]').text()).toContain('10')
    expect(w.find('[data-testid="pd-stat-entry"]').exists()).toBe(true)
    const asOfEl = w.find('[data-testid="pd-asof"]')
    expect(asOfEl.exists()).toBe(true)
    expect(asOfEl.text()).toMatch(/\d{2}\.\d{2}\., \d{2}:\d{2}:\d{2}/)
    expect(asOfEl.text()).not.toMatch(/vor|gerade eben|ago/i)
    const ordersEl = w.find('[data-testid="pd-orders"]')
    expect(ordersEl.exists()).toBe(true)
    // Readable German label from orderDisplay ("ausgeführt"), not the raw
    // broker status string ("filled"), plus the order role ("Einstieg").
    expect(ordersEl.text()).toContain('ausgeführt')
    expect(ordersEl.text()).not.toMatch(/\bfilled\b/)
    expect(ordersEl.text()).toContain('Einstieg')
  })

  it('hides info sections entirely when their data is null', async () => {
    getInstrumentInfoImpl = async () => emptyInfo
    const w = mountView()
    await flushPromises()

    expect(w.find('[data-testid="pd-section-news"]').exists()).toBe(false)
    expect(w.find('[data-testid="pd-section-events"]').exists()).toBe(false)
    expect(w.find('[data-testid="pd-section-insights"]').exists()).toBe(false)
    expect(w.find('[data-testid="pd-section-finance"]').exists()).toBe(false)
    expect(w.find('[data-testid="pd-info"]').exists()).toBe(false)
  })

  it('renders info sections when their data is present', async () => {
    getInstrumentInfoImpl = async () => fullInfo
    const w = mountView()
    await flushPromises()

    expect(w.find('[data-testid="pd-section-news"]').exists()).toBe(true)
    expect(w.find('[data-testid="pd-section-news"]').text()).toContain('NVIDIA unveils next-gen GPU')
    expect(w.find('[data-testid="pd-section-events"]').exists()).toBe(true)
    expect(w.find('[data-testid="pd-section-insights"]').exists()).toBe(true)
    expect(w.find('[data-testid="pd-section-finance"]').exists()).toBe(true)
    // The "Informationen" section is dead code (Finnhub never returns a
    // description) and was removed — it must never render, even with a full profile.
    expect(w.find('[data-testid="pd-info"]').exists()).toBe(false)
  })

  it('shows the 5-level analyst rating, breakdown counts, and a Finnhub source link', async () => {
    getInstrumentInfoImpl = async () => analystDetailInfo
    const w = mountView()
    await flushPromises()

    const insights = w.find('[data-testid="pd-section-insights"]')
    expect(insights.exists()).toBe(true)

    // strongBuy:8, buy:4, hold:3, sell:1, strongSell:0 → total 16
    // score = (8*5 + 4*4 + 3*3 + 1*2 + 0*1) / 16 = (40+16+9+2+0)/16 = 4.1875 → "Kaufen" (Buy bucket, de locale)
    expect(insights.text()).toContain('Kaufen')

    // breakdown: total=16, buy(strongBuy+buy)=12, hold=3, sell(sell+strongSell)=1
    expect(insights.text()).toContain('16 Analysten')
    expect(insights.text()).toContain('12 Kauf')
    expect(insights.text()).toContain('3 Halten')
    expect(insights.text()).toContain('1 Verkauf')

    const sourceLink = insights.find('a[href="https://finance.yahoo.com/quote/NVDA/analysis"]')
    expect(sourceLink.exists()).toBe(true)
    expect(sourceLink.attributes('target')).toBe('_blank')
    expect(sourceLink.attributes('rel')).toBe('noopener noreferrer')
  })

  it('renders enriched position details: name, native price, weight/today tiles, asset class, held-since, order role', async () => {
    getDepotPositionImpl = async () => ({
      position: position({
        name: 'NVIDIA Corporation',
        assetType: 'Stock',
        valueDate: '2026-03-14',
        nativePrice: 145.5,
        nativeCurrency: 'CHF',
        currency: 'EUR',
      }),
      orders: [order({ role: 'stop' })],
      asOf: '2026-07-11T08:00:00Z',
      runId: null,
    })
    const w = mountView()
    await flushPromises()

    expect(w.find('[data-testid="pd-symbol"]').text()).toContain('NVDA')
    expect(w.text()).toContain('NVIDIA Corporation')

    const priceEl = w.find('[data-testid="pd-price"]')
    expect(priceEl.text()).toContain('CHF')

    expect(w.find('[data-testid="pd-stat-weight"]').exists()).toBe(true)
    expect(w.find('[data-testid="pd-stat-weight"]').text()).toContain('60')
    expect(w.find('[data-testid="pd-stat-today"]').exists()).toBe(true)
    expect(w.find('[data-testid="pd-stat-today"]').text()).toContain('1,2')

    expect(w.text()).toContain('Stock')
    expect(w.text()).toContain('14.3.2026')

    expect(w.find('[data-testid="pd-orders"]').text()).toContain('Stop')

    expect(w.find('[data-testid="pd-info"]').exists()).toBe(false)
  })

  it('re-fetches when the route params change', async () => {
    const w = mountView()
    await flushPromises()
    expect(getDepotPositionSpy).toHaveBeenCalledWith('depot-1', 'NVDA')

    getDepotPositionImpl = async () => ({ position: position({ symbol: 'ABB', avgEntryPrice: 35 }), orders: [], asOf: '2026-07-11T08:00:00Z', runId: null })
    await router.push('/depots/depot-1/ABB')
    await flushPromises()

    expect(getDepotPositionSpy).toHaveBeenCalledWith('depot-1', 'ABB')
    expect(w.find('[data-testid="pd-symbol"]').text()).toContain('ABB')
  })

  it('shows a not-found state on 404', async () => {
    getDepotPositionImpl = async () => { throw new Error('getDepotPosition: not found: depot-1/NVDA') }
    const w = mountView()
    await flushPromises()

    expect(w.find('[data-testid="pd-notfound"]').exists()).toBe(true)
    expect(w.find('[data-testid="pd-price"]').exists()).toBe(false)
  })

  it('shows a broker-down error state with retry on 503', async () => {
    getDepotPositionImpl = async () => { throw new Error('getDepotPosition: depot unavailable: depot-1/NVDA') }
    const w = mountView()
    await flushPromises()

    expect(w.find('[data-testid="pd-brokerdown"]').exists()).toBe(true)
    expect(w.find('[data-testid="pd-retry"]').exists()).toBe(true)
  })

  it('updates the header change value when the timeframe changes', async () => {
    getInstrumentChartImpl = async (_symbol, range) =>
      range === '1m' ? chart([100, 110]) : chart([100, 200])
    const w = mountView()
    await flushPromises()

    const before = w.find('[data-testid="pd-header-change"]').text()

    await w.find('[data-testid="pd-range-1y"]').trigger('click')
    await flushPromises()

    const after = w.find('[data-testid="pd-header-change"]').text()
    expect(after).not.toEqual(before)
  })

  // ── Post-refactor regressions (InstrumentInfoPanel extraction) ──

  it('preserves DOM order: header price → chart ranges → stat tiles', async () => {
    getInstrumentInfoImpl = async () => fullInfo
    const w = mountView()
    await flushPromises()

    const html = w.html()
    const price = html.indexOf('data-testid="pd-price"')
    const chartRange = html.indexOf('data-testid="pd-range-1m"')
    const tiles = html.indexOf('data-testid="pd-stat-position"')
    const news = html.indexOf('data-testid="pd-section-news"')

    expect(price).toBeGreaterThanOrEqual(0)
    expect(chartRange).toBeGreaterThan(price)
    expect(tiles).toBeGreaterThan(chartRange)
    // Info sections (owned by the panel) render after the between-slot tiles.
    expect(news).toBeGreaterThan(tiles)
  })

  it('fetches the instrument info bundle exactly once per page view', async () => {
    const w = mountView()
    await flushPromises()

    expect(getInstrumentInfoSpy).toHaveBeenCalledTimes(1)
    expect(getInstrumentInfoSpy).toHaveBeenCalledWith('NVDA')
    expect(w.find('[data-testid="pd-price"]').exists()).toBe(true)
  })

  it('formats the analyst price target in the position currency (EUR, not USD)', async () => {
    getDepotPositionImpl = async () => ({
      position: position({ currency: 'EUR' }),
      orders: [order()],
      asOf: '2026-07-11T08:00:00Z',
      runId: null,
    })
    getInstrumentInfoImpl = async () => ({
      ...fullInfo,
      analystEstimates: {
        symbol: 'NVDA',
        priceTarget: 200,
        recommendations: [{ period: '2026-07', strongBuy: 8, buy: 4, hold: 3, sell: 1, strongSell: 0 }],
      },
    })
    const w = mountView()
    await flushPromises()

    const insights = w.find('[data-testid="pd-section-insights"]')
    expect(insights.exists()).toBe(true)
    expect(insights.text()).toContain('200')
    expect(insights.text()).toContain('€')
    expect(insights.text()).not.toContain('$')
  })

  // ── Raw transcript drilldown (Schicht 2, Task 4b) ────────────────

  it('does not render the transcript panel when the position has no linked run', async () => {
    getDepotPositionImpl = async () => ({ position: position(), orders: [order()], asOf: '2026-07-11T08:00:00Z', runId: null })
    const w = mountView()
    await flushPromises()

    expect(w.find('[data-testid="pd-transcript"]').exists()).toBe(false)
  })

  it('renders the transcript panel with a heuristic-link hint when the position has a linked run', async () => {
    getDepotPositionImpl = async () => ({ position: position(), orders: [order()], asOf: '2026-07-11T08:00:00Z', runId: 'run-open-1' })
    const w = mountView()
    await flushPromises()

    const panel = w.find('[data-testid="pd-transcript"]')
    expect(panel.exists()).toBe(true)
    expect(w.find('[data-testid="pd-transcript-heuristic"]').exists()).toBe(true)
    expect(w.find('[data-testid="transcript-panel"]').exists()).toBe(true)
  })

  it('loads the run transcript on toggle click when a run is linked', async () => {
    getDepotPositionImpl = async () => ({ position: position(), orders: [order()], asOf: '2026-07-11T08:00:00Z', runId: 'run-open-1' })
    const w = mountView()
    await flushPromises()

    expect(getRunTranscriptSpy).not.toHaveBeenCalled()

    await w.find('[data-testid="transcript-toggle"]').trigger('click')
    await flushPromises()

    expect(getRunTranscriptSpy).toHaveBeenCalledWith('run-open-1')
    expect(w.find('[data-testid="transcript-raw"]').text()).toContain('run-open-1')
  })
})
