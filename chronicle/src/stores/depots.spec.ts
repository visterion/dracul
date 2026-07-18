import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

const getDepots = vi.fn()
vi.mock('../api', () => ({ useApi: () => ({ getDepots }) }))

import { useDepotsStore } from './depots'
import type { Depot, DepotPositionView } from '../api/types'

function pos(symbol: string, over: Partial<DepotPositionView> = {}): DepotPositionView {
  return {
    symbol, qty: 1, avgEntryPrice: 1, marketValue: 1, unrealizedPl: 0, unrealizedPlPct: 0,
    price: 1, dayChangePercent: 0, weightPct: 0, currency: 'USD', name: null, assetType: null,
    valueDate: null, nativePrice: null, nativeCurrency: null, ...over,
  }
}
function depot(id: string, environment: 'paper' | 'live', positions: DepotPositionView[]): Depot {
  return { id, provider: 'x', environment, status: 'ok', probedAt: null, error: null,
    account: null, aggregates: null, positions, orders: [], asOf: null }
}

describe('depots store', () => {
  beforeEach(() => { setActivePinia(createPinia()); getDepots.mockReset() })

  it('load calls getDepots(false); findHolding matches exactly', async () => {
    getDepots.mockResolvedValue({ depots: [depot('depot-1', 'live', [pos('AAPL')])] })
    const store = useDepotsStore()
    await store.load()
    expect(getDepots).toHaveBeenCalledWith(false)
    expect(store.findHolding('AAPL')).toEqual({ connection: 'depot-1', position: expect.objectContaining({ symbol: 'AAPL' }) })
    expect(store.findHolding('MSFT')).toBeNull()
  })

  it('findHolding prefers live over paper', async () => {
    getDepots.mockResolvedValue({ depots: [
      depot('paper-1', 'paper', [pos('NVDA', { name: 'paper' })]),
      depot('live-1', 'live', [pos('NVDA', { name: 'live' })]),
    ] })
    const store = useDepotsStore()
    await store.load()
    expect(store.findHolding('NVDA')?.connection).toBe('live-1')
  })

  it('failed load leaves store empty and retries on next load', async () => {
    getDepots.mockRejectedValueOnce(new Error('boom'))
    const store = useDepotsStore()
    await store.load()
    expect(store.findHolding('AAPL')).toBeNull()
    getDepots.mockResolvedValueOnce({ depots: [depot('depot-1', 'live', [pos('AAPL')])] })
    await store.load()
    expect(store.findHolding('AAPL')?.connection).toBe('depot-1')
    expect(getDepots).toHaveBeenCalledTimes(2)
  })
})
