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
