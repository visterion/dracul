import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import de from '../../i18n/locales/de'
import DepotSection from './DepotSection.vue'
import type { Depot } from '../../api/types'

vi.mock('../../api', () => ({
  useApi: () => ({
    getDepotChart: vi.fn(async () => ({ points: [] })),
    getDepotHistory: vi.fn(),
  }),
}))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })
const router = createRouter({ history: createMemoryHistory(), routes: [
  { path: '/depots', name: 'depots', component: { template: '<div/>' } },
  { path: '/depots/:connection/:symbol', name: 'depot-position-detail', component: { template: '<div/>' } },
] })

const baseDepot: Depot = {
  id: 'depot-1', provider: 'alpaca', environment: 'paper', status: 'ok', probedAt: null, error: null,
  account: null, aggregates: null, positions: [], orders: [], asOf: null,
}

function mountSection(overrides: Partial<Depot> = {}) {
  const depot: Depot = { ...baseDepot, ...overrides }
  return mount(DepotSection, { props: { depot }, global: { plugins: [i18n, router] } })
}

describe('DepotSection orders — grouped brackets', () => {
  beforeEach(() => { setActivePinia(createPinia()) })

  it('renders a bracket as grouped legs with roles and waiting status', () => {
    const w = mountSection({
      account: { equity: 9899.11, cash: 9899.11, buyingPower: 9899.11, currency: 'EUR', status: 'OK', asOf: '2026-07-19T20:04:23Z' },
      aggregates: null,
      positions: [],
      orders: [
        { brokerOrderId: 'e', symbol: 'STT', side: 'buy', qty: 6, type: 'limit', status: 'working', role: 'entry', parentId: null },
        { brokerOrderId: 't', symbol: 'STT', side: null, qty: 6, type: 'limit', status: 'notWorking', role: 'take_profit', parentId: 'e' },
        { brokerOrderId: 's', symbol: 'STT', side: null, qty: 6, type: 'stop', status: 'notWorking', role: 'stop_loss', parentId: 'e' },
      ],
    })
    const text = w.text()
    expect(text).toContain('Einstieg')
    expect(text).toContain('Ziel')
    expect(text).toContain('Stop')
    expect(text).toContain('wartet auf Einstieg')
  })
})
