import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { HttpApiClient } from './HttpApiClient'
import { ApiError } from './errors'

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

describe('HttpApiClient.updatePatternGate', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('PATCHes action update_gate with the gate object', async () => {
    const client = new HttpApiClient('')
    const gate = { conditions: [{ field: 'mechanism', op: 'eq', value: 'PEAD' }] }
    await client.updatePatternGate('p-1', gate)
    expect(fetchMock).toHaveBeenCalledWith('/api/patterns/p-1', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'update_gate', gate }),
    })
  })

  it('PATCHes gate null to clear', async () => {
    const client = new HttpApiClient('')
    await client.updatePatternGate('p-1', null)
    expect(fetchMock).toHaveBeenCalledWith('/api/patterns/p-1', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'update_gate', gate: null }),
    })
  })

  it('throws on non-ok response', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 400 })
    const client = new HttpApiClient('')
    await expect(client.updatePatternGate('p-1', null)).rejects.toThrow('HTTP 400')
  })
})

describe('HttpApiClient.searchInstruments', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('builds /api/instruments/search?q=...&limit=... with the default limit', async () => {
    const client = new HttpApiClient('')
    await client.searchInstruments('nokia')
    expect(fetchMock).toHaveBeenCalledWith('/api/instruments/search?q=nokia&limit=10')
  })

  it('passes a custom limit through', async () => {
    const client = new HttpApiClient('')
    await client.searchInstruments('nokia', 5)
    expect(fetchMock).toHaveBeenCalledWith('/api/instruments/search?q=nokia&limit=5')
  })

  it('URL-encodes a query with spaces and umlauts', async () => {
    const client = new HttpApiClient('')
    await client.searchInstruments('Müller Öl AG')
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/instruments/search?q=${encodeURIComponent('Müller Öl AG')}&limit=10`,
    )
    // Concretely: no raw space or umlaut ever reaches the URL string.
    const calledUrl = fetchMock.mock.calls[0][0] as string
    expect(calledUrl).not.toContain(' ')
    expect(calledUrl).not.toContain('ü')
    expect(calledUrl).not.toContain('ö')
  })

  it('returns the parsed hit array on a 200', async () => {
    const hits = [{ symbol: 'NOK', name: 'Nokia Oyj', exchange: 'NYSE', type: 'EQUITY' }]
    fetchMock.mockResolvedValue({ ok: true, json: () => Promise.resolve(hits) })
    const client = new HttpApiClient('')
    await expect(client.searchInstruments('nokia')).resolves.toEqual(hits)
  })

  it('throws an ApiError carrying the status on a non-OK response', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 502 })
    const client = new HttpApiClient('')
    let caught: unknown
    try {
      await client.searchInstruments('nokia')
    } catch (e) {
      caught = e
    }
    expect(caught).toBeInstanceOf(ApiError)
    expect((caught as ApiError).status).toBe(502)
    expect((caught as ApiError).message).toBe('searchInstruments failed: HTTP 502')
  })
})
