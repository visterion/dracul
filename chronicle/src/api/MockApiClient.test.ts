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
