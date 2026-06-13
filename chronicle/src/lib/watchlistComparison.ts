import type { WatchlistItem } from '../api/types'

/** A ticker held by both users. Price fields are shared (identical per-ticker). */
export interface SharedRow {
  ticker: string
  companyName: string
  currentPrice: number
  dayChangePercent: number
  mine: WatchlistItem
  theirs: WatchlistItem
}

export interface ComparisonResult {
  both: SharedRow[]
  onlyMine: WatchlistItem[]
  onlyTheirs: WatchlistItem[]
}

/**
 * Split a flat, multi-owner watchlist into three buckets relative to two users.
 * Ticker matching is case-insensitive; each bucket is sorted alphabetically.
 * Pure — no side effects.
 */
export function buildComparison(
  items: WatchlistItem[],
  me: string,
  otherOwner: string,
): ComparisonResult {
  const key = (t: string) => t.toUpperCase()
  const mineItems = items.filter(i => i.owner === me)
  const theirItems = items.filter(i => i.owner === otherOwner)
  const theirByTicker = new Map(theirItems.map(i => [key(i.ticker), i]))
  const mineByTicker = new Map(mineItems.map(i => [key(i.ticker), i]))

  const both: SharedRow[] = []
  const onlyMine: WatchlistItem[] = []
  for (const m of mineItems) {
    const t = theirByTicker.get(key(m.ticker))
    if (t) {
      both.push({
        ticker: m.ticker,
        companyName: m.companyName,
        currentPrice: m.currentPrice,
        dayChangePercent: m.dayChangePercent,
        mine: m,
        theirs: t,
      })
    } else {
      onlyMine.push(m)
    }
  }
  const onlyTheirs = theirItems.filter(t => !mineByTicker.has(key(t.ticker)))

  const byTicker = (a: { ticker: string }, b: { ticker: string }) =>
    a.ticker.localeCompare(b.ticker)
  both.sort(byTicker)
  onlyMine.sort(byTicker)
  onlyTheirs.sort(byTicker)
  return { both, onlyMine, onlyTheirs }
}
