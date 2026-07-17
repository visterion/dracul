import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { HttpApiClient } from './HttpApiClient'

describe('HttpApiClient.getChronicle', () => {
  const chronicleData = { prey: [], verdicts: [], alerts: [], pendingPatterns: [] }
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(chronicleData),
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('fetches /api/chronicle with no query string by default', async () => {
    const client = new HttpApiClient('')
    await client.getChronicle()
    expect(fetchMock).toHaveBeenCalledWith('/api/chronicle')
  })

  it('fetches /api/chronicle with no query string when includeArchived is false', async () => {
    const client = new HttpApiClient('')
    await client.getChronicle(false)
    expect(fetchMock).toHaveBeenCalledWith('/api/chronicle')
  })

  it('appends ?includeArchived=true when includeArchived is true', async () => {
    const client = new HttpApiClient('')
    await client.getChronicle(true)
    expect(fetchMock).toHaveBeenCalledWith('/api/chronicle?includeArchived=true')
  })
})
