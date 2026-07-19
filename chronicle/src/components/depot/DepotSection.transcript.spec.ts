import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import de from '../../i18n/locales/de'
import DepotSection from './DepotSection.vue'
import type { Depot } from '../../api/types'

const mockGetDepotHistory = vi.fn()
const mockGetRunTranscript = vi.fn()
vi.mock('../../api', () => ({
  useApi: () => ({
    getDepotChart: vi.fn(async () => ({ points: [] })),
    getDepotHistory: mockGetDepotHistory,
    getRunTranscript: mockGetRunTranscript,
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

describe('DepotSection raw transcript panel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockGetDepotHistory.mockReset()
    mockGetRunTranscript.mockReset()
  })

  it('shows the toggle for a row with a linkable runId, lazily fetches, and renders the raw content', async () => {
    mockGetDepotHistory.mockResolvedValue({ entries: [
      { source: 'ORDER', symbol: 'AAPL', side: 'buy', qty: 10, entryPrice: 100, exitPrice: 110,
        profitLoss: 100, status: 'filled', brokerOrderId: 'o-1', brokerConfirmed: true,
        why: { strigoi: 'index-strigoi', killCriteria: ['x'], entryReasoning: 'drift',
               draculExitReason: 'TAKE_PROFIT', draculRealizedR: 2, runId: 'run-xyz' } }], error: null })
    mockGetRunTranscript.mockResolvedValue({ transcript: { foo: 'bar' }, expired: false })
    const w = mountSection()
    await w.find('[data-testid="depot-tab-history"]').trigger('click')
    await flushPromises()

    const toggle = w.find('[data-testid="transcript-toggle"]')
    expect(toggle.exists()).toBe(true)
    expect(mockGetRunTranscript).not.toHaveBeenCalled()

    await toggle.trigger('click')
    await flushPromises()

    expect(mockGetRunTranscript).toHaveBeenCalledWith('run-xyz')
    expect(w.text()).toContain('"foo"')
    expect(w.text()).toContain('"bar"')
  })

  it('does not show a toggle for a row without a runId', async () => {
    mockGetDepotHistory.mockResolvedValue({ entries: [
      { source: 'ORDER', symbol: 'AAPL', side: 'buy', qty: 10, entryPrice: 100, exitPrice: 110,
        profitLoss: 100, status: 'filled', brokerOrderId: 'o-1', brokerConfirmed: true,
        why: { strigoi: 'index-strigoi', killCriteria: ['x'], entryReasoning: 'drift',
               draculExitReason: 'TAKE_PROFIT', draculRealizedR: 2, runId: null } }], error: null })
    const w = mountSection()
    await w.find('[data-testid="depot-tab-history"]').trigger('click')
    await flushPromises()

    expect(w.find('[data-testid="transcript-toggle"]').exists()).toBe(false)
  })

  it('shows an expired hint instead of raw content when the run is expired', async () => {
    mockGetDepotHistory.mockResolvedValue({ entries: [
      { source: 'ORDER', symbol: 'AAPL', side: 'buy', qty: 10, entryPrice: 100, exitPrice: 110,
        profitLoss: 100, status: 'filled', brokerOrderId: 'o-1', brokerConfirmed: true,
        why: { strigoi: 'index-strigoi', killCriteria: ['x'], entryReasoning: 'drift',
               draculExitReason: 'TAKE_PROFIT', draculRealizedR: 2, runId: 'run-old' } }], error: null })
    mockGetRunTranscript.mockResolvedValue({ transcript: null, expired: true })
    const w = mountSection()
    await w.find('[data-testid="depot-tab-history"]').trigger('click')
    await flushPromises()

    await w.find('[data-testid="transcript-toggle"]').trigger('click')
    await flushPromises()

    expect(mockGetRunTranscript).toHaveBeenCalledWith('run-old')
    expect(w.text()).toContain(de.depots.transcript.expired)
  })
})
