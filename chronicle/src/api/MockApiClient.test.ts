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

describe('MockApiClient strigoi P&L', () => {
  it('returns rows for depot-1 and none for another depot', async () => {
    const client = new MockApiClient()
    const own = await client.getStrigoiPnl('depot-1')
    const other = await client.getStrigoiPnl('saxo-live-1')
    expect(own?.strigoi.map(r => r.strigoi)).toEqual(['strigoi-echo', 'strigoi-spin'])
    expect(other?.strigoi).toEqual([])
  })

  it('returns an empty detail for a strigoi without trades', async () => {
    const detail = await new MockApiClient().getStrigoiPnlDetail('strigoi-lazarus')
    expect(detail?.trades).toEqual([])
    expect(detail?.summary.closedTrades).toBe(0)
    expect(detail?.summary.hitRate).toBeNull()
  })
})

describe('MockApiClient benchmark', () => {
  it('serves the synthetic summary with four strigoi and a depot block', async () => {
    const s = await new MockApiClient().getBenchmarkSummary()
    expect(s?.decisionDate).toBe('2027-04-13')
    expect(s?.agents.map(a => a.agent)).toEqual(
      ['strigoi-echo', 'strigoi-insider', 'strigoi-spin', 'strigoi-tech'],
    )
    expect(s?.depot?.byStrigoi).toHaveLength(2)
  })

  it('leaves coverageStart null only for the signal-only strigoi-tech', async () => {
    const s = await new MockApiClient().getBenchmarkSummary()
    const byAgent = new Map(s?.agents.map(a => [a.agent, a.coverageStart]))
    expect(byAgent.get('strigoi-spin')).not.toBeNull()
    expect(byAgent.get('strigoi-tech')).toBeNull()
  })
})
