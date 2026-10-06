import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import StrigoiPnlSection from './StrigoiPnlSection.vue'
import de from '../../i18n/locales/de'
import type { StrigoiPnlDetail, StrigoiPnlTrade } from '../../api/types'

function trade(overrides: Partial<StrigoiPnlTrade> = {}): StrigoiPnlTrade {
  return {
    positionId: 1, symbol: 'TSTA', status: 'OPEN', entryDate: '2026-09-14', exitDate: null,
    qty: 12, entryPrice: 41.2, exitPrice: null, currency: 'USD', realizedEur: 0,
    unrealizedEur: 38.5, r: null, exitReason: null, flags: [], ...overrides,
  }
}
function detail(trades: StrigoiPnlTrade[]): StrigoiPnlDetail {
  return {
    connection: 'depot-1', currency: 'EUR', fxBasis: 'current',
    summary: {
      strigoi: 'strigoi-tst', closedTrades: 4, wins: 3, losses: 1, hitRate: 0.75,
      realizedEur: 120.5, unrealizedEur: 38.5, totalEur: 159, sumR: 2.4,
      openPositions: 2, openCostEur: 800, flaggedTrades: 1,
    },
    trades,
  }
}

let response: StrigoiPnlDetail | null = null
const mockGetDetail = vi.fn(async (_name: string) => response)
vi.mock('../../api', () => ({ useApi: () => ({ getStrigoiPnlDetail: mockGetDetail }) }))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })
const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/strigoi/:name', name: 'strigoi-detail', component: { template: '<div/>' } },
    { path: '/depots/:connection/:symbol', name: 'depot-position-detail', component: { template: '<div/>' } },
  ],
})
function mountSection(name = 'strigoi-tst') {
  return mount(StrigoiPnlSection, { props: { name }, global: { plugins: [i18n, router] } })
}

beforeEach(async () => {
  mockGetDetail.mockClear()
  await router.push('/strigoi/strigoi-tst')
})

describe('StrigoiPnlSection', () => {
  it('renders the summary chips and one row per trade in the delivered order', async () => {
    response = detail([
      trade(),
      trade({ positionId: 2, symbol: 'TSTB', status: 'CLOSED', exitDate: '2026-09-02', exitPrice: 21.1,
        realizedEur: 47.6, unrealizedEur: null, r: 1.3, exitReason: 'HARD_STOP' }),
    ])
    const w = mountSection()
    await flushPromises()

    expect(mockGetDetail).toHaveBeenCalledWith('strigoi-tst')
    expect(w.text()).toContain('Ergebnis')
    const chips = w.find('[data-testid="strigoi-pnl-chips"]').text()
    expect(chips).toContain('4 Trades')
    expect(chips).toContain('Treffer 75 %')
    const rows = w.findAll('[data-testid="strigoi-pnl-trade"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('TSTA')
    expect(rows[0].text()).toContain('offen')
    expect(rows[0].find('[data-col="result"]').classes()).toContain('pos')
    expect(rows[1].text()).toContain('HARD_STOP')
    expect(rows[1].find('[data-col="r"]').text()).toBe('1,30')
    expect(w.find('[data-testid="strigoi-pnl-fx-note"]').exists()).toBe(true)
  })

  it('shows an unpriced open trade as — with a flag, never as 0', async () => {
    response = detail([trade({ unrealizedEur: null, flags: ['NO_PRICE'] })])
    const w = mountSection()
    await flushPromises()
    const cell = w.find('[data-testid="strigoi-pnl-trade"] [data-col="result"]')
    expect(cell.text()).toContain('—')
    expect(cell.find('.pnl-flag').attributes('title')).toContain('kein aktueller Kurs')
  })

  it('links the symbol of an open trade to its depot position, not a closed one', async () => {
    response = detail([
      trade(),
      trade({ positionId: 2, symbol: 'TSTB', status: 'CLOSED', exitDate: '2026-09-02', exitPrice: 21.1,
        realizedEur: 47.6, unrealizedEur: null, r: 1.3, exitReason: 'HARD_STOP' }),
    ])
    const w = mountSection()
    await flushPromises()
    const rows = w.findAll('[data-testid="strigoi-pnl-trade"]')
    expect(rows[0].find('a').attributes('href')).toBe('/depots/depot-1/TSTA')
    expect(rows[1].find('a').exists()).toBe(false)
  })

  it('shows the empty state without trades', async () => {
    response = detail([])
    const w = mountSection()
    await flushPromises()
    expect(w.find('[data-testid="strigoi-pnl-empty"]').text()).toBe('Noch keine Trades')
  })

  it('renders nothing when the endpoint is absent (null)', async () => {
    response = null
    const w = mountSection()
    await flushPromises()
    expect(w.find('[data-testid="strigoi-pnl-section"]').exists()).toBe(false)
  })
})
