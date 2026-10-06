import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { HttpApiClient } from './HttpApiClient'

describe('HttpApiClient strigoi P&L', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({ strigoi: [] }) })
    vi.stubGlobal('fetch', fetchMock)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('fetches the overview for the given connection', async () => {
    await new HttpApiClient('').getStrigoiPnl('depot-1')
    expect(fetchMock).toHaveBeenCalledWith('/api/executor/pnl/strigoi?connection=depot-1')
  })

  it('fetches the detail without a connection by default', async () => {
    await new HttpApiClient('').getStrigoiPnlDetail('strigoi-spin')
    expect(fetchMock).toHaveBeenCalledWith('/api/executor/pnl/strigoi/strigoi-spin')
  })

  it('resolves null on 404 (executor disabled or connection not visible)', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 404 })
    expect(await new HttpApiClient('').getStrigoiPnl('depot-1')).toBeNull()
    expect(await new HttpApiClient('').getStrigoiPnlDetail('strigoi-spin')).toBeNull()
  })

  it('throws on other errors', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 503 })
    await expect(new HttpApiClient('').getStrigoiPnl('depot-1')).rejects.toThrow('HTTP 503')
  })
})
