import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import DepotsView from './DepotsView.vue'
import de from '../i18n/locales/de'
import type { Depot, DepotsResponse, DepotChart } from '../api/types'
import { mockDepotChart } from '../mocks/depots'

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

vi.mock('../api', () => ({
  useApi: () => ({
    getDepots: vi.fn(async () => depotsResponse),
    getDepotChart: vi.fn(async (): Promise<DepotChart> => mockDepotChart),
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

    const dayChangeCells = w.findAll('[data-testid="day-change-cell"]').map(c => c.text())
    // ABB has dayChangePercent: null in the fixture above
    expect(dayChangeCells).toContain('—')
    expect(dayChangeCells.some(t => t === '0' || t.includes('0,00'))).toBe(false)
  })
})
