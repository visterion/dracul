import { describe, it, expect, vi, afterEach } from 'vitest'
import { HttpApiClient } from './HttpApiClient'

describe('getDepotHistory', () => {
  afterEach(() => vi.restoreAllMocks())

  it('fetches and returns history', async () => {
    const payload = { entries: [{ source: 'ORDER', symbol: 'AAPL', side: 'buy', qty: 10,
      entryPrice: null, exitPrice: null, profitLoss: null, status: 'filled',
      brokerOrderId: 'o-1', brokerConfirmed: true, why: null }], error: null }
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(payload), { status: 200 })))

    const api = new HttpApiClient('http://test')
    const out = await api.getDepotHistory('depot-1')

    expect(out.entries).toHaveLength(1)
    expect(out.entries[0].symbol).toBe('AAPL')
    expect(fetch).toHaveBeenCalledWith('http://test/api/depots/depot-1/history')
  })
})
