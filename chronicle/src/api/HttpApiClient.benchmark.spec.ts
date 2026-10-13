import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { HttpApiClient } from './HttpApiClient'

describe('HttpApiClient benchmark', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200,
      headers: { get: () => 'application/json' },
      json: () => Promise.resolve({ agents: [] }),
    })
    vi.stubGlobal('fetch', fetchMock)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('fetches the summary', async () => {
    await new HttpApiClient('').getBenchmarkSummary()
    expect(fetchMock).toHaveBeenCalledWith('/api/benchmark/summary')
  })

  it('resolves null on 404 and on a non-JSON 200 (SPA fallback)', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 404 })
    expect(await new HttpApiClient('').getBenchmarkSummary()).toBeNull()
    fetchMock.mockResolvedValue({
      ok: true, status: 200,
      headers: { get: () => 'text/html; charset=utf-8' },
      json: () => Promise.reject(new Error('Unexpected token <')),
    })
    expect(await new HttpApiClient('').getBenchmarkSummary()).toBeNull()
  })

  it('throws on other errors', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 503 })
    await expect(new HttpApiClient('').getBenchmarkSummary()).rejects.toThrow('HTTP 503')
  })
})
