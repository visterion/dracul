import type { WatchlistItem, ExitSignal } from '../api/types'

export interface PortfolioSummary {
  count: number
  totalValue: number
  totalCost: number
  totalPnl: number
  /** null when there is no cost basis to compare against. */
  totalPnlPct: number | null
}

/** Header aggregates over complete positions (entryPrice + shareCount present). */
export function portfolioSummary(items: WatchlistItem[]): PortfolioSummary {
  let count = 0
  let totalValue = 0
  let totalCost = 0
  for (const i of items) {
    if (i.entryPrice == null || i.shareCount == null) continue
    count += 1
    totalValue += i.currentPrice * i.shareCount
    totalCost += i.entryPrice * i.shareCount
  }
  const totalPnl = totalValue - totalCost
  const totalPnlPct = totalCost > 0 ? (totalPnl / totalCost) * 100 : null
  return { count, totalValue, totalCost, totalPnl, totalPnlPct }
}

/** The raw NONE enum is noise, not a status — render nothing for it. */
export function visibleThesisStatus(signal: ExitSignal | null): string | null {
  const s = signal?.thesisStatus
  return !s || s === 'NONE' ? null : s
}
