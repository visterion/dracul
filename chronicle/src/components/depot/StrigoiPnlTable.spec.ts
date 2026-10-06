import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import StrigoiPnlTable from './StrigoiPnlTable.vue'
import de from '../../i18n/locales/de'
import type { StrigoiPnlOverview, StrigoiPnlSummary } from '../../api/types'

function row(overrides: Partial<StrigoiPnlSummary> = {}): StrigoiPnlSummary {
  return {
    strigoi: 'strigoi-syna', closedTrades: 4, wins: 3, losses: 1, hitRate: 0.75,
    realizedEur: 120.5, unrealizedEur: -30, totalEur: 90.5, sumR: 2.4,
    openPositions: 1, openCostEur: 500, flaggedTrades: 0, ...overrides,
  }
}
function overview(rows: StrigoiPnlSummary[]): StrigoiPnlOverview {
  return { connection: 'depot-1', currency: 'EUR', fxBasis: 'current', strigoi: rows }
}

let response: StrigoiPnlOverview | null = null
const mockGetStrigoiPnl = vi.fn(async (_connection: string) => response)
vi.mock('../../api', () => ({ useApi: () => ({ getStrigoiPnl: mockGetStrigoiPnl }) }))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })
const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/depots', name: 'depots', component: { template: '<div/>' } },
    { path: '/strigoi/:name', name: 'strigoi-detail', component: { template: '<div/>' } },
  ],
})
function mountTable(connection = 'depot-1') {
  return mount(StrigoiPnlTable, { props: { connection }, global: { plugins: [i18n, router] } })
}

beforeEach(async () => {
  mockGetStrigoiPnl.mockClear()
  await router.push('/depots')
})

describe('StrigoiPnlTable', () => {
  it('renders one row per strigoi with signed EUR cells and the FX note', async () => {
    response = overview([
      row(),
      row({ strigoi: 'unknown', closedTrades: 0, wins: 0, losses: 0, hitRate: null, realizedEur: -5,
        unrealizedEur: 0, totalEur: -5, sumR: 0, openPositions: 0, openCostEur: 0 }),
    ])
    const w = mountTable()
    await flushPromises()

    expect(mockGetStrigoiPnl).toHaveBeenCalledWith('depot-1')
    expect(w.text()).toContain('Ergebnis pro Strigoi')
    const rows = w.findAll('[data-testid="strigoi-pnl-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('strigoi-syna')
    expect(rows[0].text()).toContain('75 %')
    expect(rows[0].find('[data-col="realized"]').classes()).toContain('pos')
    expect(rows[0].find('[data-col="unrealized"]').classes()).toContain('neg')
    expect(rows[1].text()).toContain('unbekannt')
    expect(rows[1].find('[data-col="hitRate"]').text()).toBe('—')
    expect(w.find('[data-testid="strigoi-pnl-fx-note"]').text())
      .toBe('Umrechnung in € zum aktuellen Wechselkurs')
  })

  it('shows the empty state when no strigoi has traded', async () => {
    response = overview([])
    const w = mountTable()
    await flushPromises()
    expect(w.find('[data-testid="strigoi-pnl-empty"]').text()).toBe('Noch keine Trades')
  })

  it('opens /strigoi/:name on row click, but not for the unknown group', async () => {
    response = overview([row(), row({ strigoi: 'unknown' })])
    const w = mountTable()
    await flushPromises()
    const rows = w.findAll('[data-testid="strigoi-pnl-row"]')

    await rows[1].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('depots')

    await rows[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/strigoi/strigoi-syna')
  })

  it('does not link a row whose source_agent is not a real strigoi (e.g. an operator injection)', async () => {
    response = overview([row({ strigoi: 'injected' })])
    const w = mountTable()
    await flushPromises()
    const rows = w.findAll('[data-testid="strigoi-pnl-row"]')
    expect(rows[0].classes()).not.toContain('pnl-row-link')

    await rows[0].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('depots')
  })

  it('marks a strigoi whose sum misses flagged trades', async () => {
    response = overview([row({ flaggedTrades: 2 })])
    const w = mountTable()
    await flushPromises()
    expect(w.find('[data-testid="strigoi-pnl-row"] .pnl-flag').exists()).toBe(true)
  })

  it('renders nothing when the endpoint is absent (null)', async () => {
    response = null
    const w = mountTable()
    await flushPromises()
    expect(w.find('[data-testid="strigoi-pnl"]').exists()).toBe(false)
  })
})
