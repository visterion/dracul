import { describe, it, expect } from 'vitest'
import { MockApiClient } from './MockApiClient'

describe('MockApiClient.getChronicle', () => {
  it('returns fewer prey by default than with includeArchived=true', async () => {
    const client = new MockApiClient()
    const defaultResult = await client.getChronicle()
    const archivedResult = await client.getChronicle(true)

    expect(defaultResult.prey.length).toBeLessThan(archivedResult.prey.length)
  })

  it('excludes the archived fixture by default and includes it when includeArchived=true', async () => {
    const client = new MockApiClient()
    const defaultResult = await client.getChronicle()
    const archivedResult = await client.getChronicle(true)

    expect(defaultResult.prey.some(p => p.id === 'prey-archived-1')).toBe(false)
    expect(archivedResult.prey.some(p => p.id === 'prey-archived-1')).toBe(true)
  })
})

describe('MockApiClient.searchInstruments', () => {
  it('filters on the query so the empty state is reachable in mock/dev mode', async () => {
    const client = new MockApiClient()
    const hit = await client.searchInstruments('mock')
    const noHit = await client.searchInstruments('zzz-does-not-match')

    expect(hit.length).toBeGreaterThan(0)
    expect(noHit).toEqual([])
  })

  it('still returns [] for a too-short query', async () => {
    const client = new MockApiClient()
    expect(await client.searchInstruments('m')).toEqual([])
  })
})
