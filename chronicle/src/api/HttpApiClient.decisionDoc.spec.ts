import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { HttpApiClient } from './HttpApiClient'

describe('HttpApiClient.getDecisionDoc', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('resolves the markdown payload on 200', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ markdown: '# x' }),
    })
    const client = new HttpApiClient('')
    await expect(client.getDecisionDoc()).resolves.toEqual({ markdown: '# x' })
    expect(fetchMock).toHaveBeenCalledWith('/api/decision-doc')
  })

  it('resolves null on 404', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 404 })
    const client = new HttpApiClient('')
    await expect(client.getDecisionDoc()).resolves.toBeNull()
  })

  it('throws on non-404 non-ok response', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 500 })
    const client = new HttpApiClient('')
    await expect(client.getDecisionDoc()).rejects.toThrow('HTTP 500')
  })
})
