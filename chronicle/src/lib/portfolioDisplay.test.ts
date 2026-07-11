import { describe, it, expect } from 'vitest'
import { portfolioSummary, visibleThesisStatus } from './portfolioDisplay'
import type { WatchlistItem, ExitSignal } from '../api/types'

const pos = (entry: number | null, shares: number | null, current: number) =>
  ({ entryPrice: entry, shareCount: shares, currentPrice: current } as WatchlistItem)

describe('portfolioSummary', () => {
  it('sums value, cost and P&L across positions (fractional shares included)', () => {
    const s = portfolioSummary([pos(100, 2, 110), pos(50, 0.73, 40)])
    expect(s.count).toBe(2)
    expect(s.totalValue).toBeCloseTo(2 * 110 + 0.73 * 40, 6)   // 249.2
    expect(s.totalCost).toBeCloseTo(2 * 100 + 0.73 * 50, 6)    // 236.5
    expect(s.totalPnl).toBeCloseTo(12.7, 6)
    expect(s.totalPnlPct).toBeCloseTo((12.7 / 236.5) * 100, 6)
  })

  it('ignores rows without a complete position and reports pct null on zero cost', () => {
    const s = portfolioSummary([pos(null, null, 99)])
    expect(s.count).toBe(0)
    expect(s.totalValue).toBe(0)
    expect(s.totalPnlPct).toBeNull()
  })
})

describe('visibleThesisStatus', () => {
  const sig = (thesisStatus: ExitSignal['thesisStatus']) => ({ thesisStatus } as ExitSignal)
  it('suppresses the raw NONE enum', () => {
    expect(visibleThesisStatus(sig('NONE'))).toBeNull()
  })
  it('passes real statuses through', () => {
    expect(visibleThesisStatus(sig('INTACT'))).toBe('INTACT')
    expect(visibleThesisStatus(sig('WEAKENING'))).toBe('WEAKENING')
  })
  it('handles missing signal / status', () => {
    expect(visibleThesisStatus(null)).toBeNull()
    expect(visibleThesisStatus(sig(null))).toBeNull()
  })
})
