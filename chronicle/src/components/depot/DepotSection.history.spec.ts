import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import de from '../../i18n/locales/de'
import DepotSection from './DepotSection.vue'
import type { Depot } from '../../api/types'

const mockGetDepotHistory = vi.fn()
vi.mock('../../api', () => ({
  useApi: () => ({
    getDepotChart: vi.fn(async () => ({ points: [] })),
    getDepotHistory: mockGetDepotHistory,
  }),
}))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })
const router = createRouter({ history: createMemoryHistory(), routes: [
  { path: '/depots', name: 'depots', component: { template: '<div/>' } },
  { path: '/depots/:connection/:symbol', name: 'depot-position-detail', component: { template: '<div/>' } },
] })

const depot: Depot = {
  id: 'depot-1', provider: 'alpaca', environment: 'paper', status: 'ok', probedAt: null, error: null,
  account: null, aggregates: null, positions: [], orders: [], asOf: null,
}

function mountSection() {
  return mount(DepotSection, { props: { depot }, global: { plugins: [i18n, router] } })
}

describe('DepotSection history tab', () => {
  beforeEach(() => { setActivePinia(createPinia()); mockGetDepotHistory.mockReset() })

  it('loads and shows history when history tab is clicked', async () => {
    mockGetDepotHistory.mockResolvedValue({ entries: [
      { source: 'ORDER', symbol: 'AAPL', side: 'buy', qty: 10, entryPrice: 100, exitPrice: 110,
        profitLoss: 100, status: 'filled', brokerOrderId: 'o-1', brokerConfirmed: true,
        why: { strigoi: 'index-strigoi', killCriteria: ['x'], entryReasoning: 'drift',
               draculExitReason: 'TAKE_PROFIT', draculRealizedR: 2 } }], error: null })
    const w = mountSection()
    await w.find('[data-testid="depot-tab-history"]').trigger('click')
    await flushPromises()
    expect(mockGetDepotHistory).toHaveBeenCalledWith('depot-1')
    expect(w.find('[data-testid="depot-history-row"]').text()).toContain('AAPL')
    expect(w.text()).toContain('index-strigoi')
    expect(w.text()).toContain('nicht autoritativ')
    expect(w.text()).toContain('TAKE_PROFIT')
  })

  it('shows the broker error instead of the empty-history text when the response carries an error', async () => {
    mockGetDepotHistory.mockResolvedValue({ entries: [], error: 'boom' })
    const w = mountSection()
    await w.find('[data-testid="depot-tab-history"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('boom')
    expect(w.text()).not.toContain('Keine Historie')
  })
})
