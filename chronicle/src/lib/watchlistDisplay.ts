import type { WatchlistItem } from '../api/types'

/**
 * "Urteil verfolgt" only means something when the item is actually linked to
 * a verdict. tag === 'TRACKING' also holds for manually added tickers, which
 * made every row wear the badge.
 */
export function showsVerdictBadge(item: Pick<WatchlistItem, 'verdictId'>): boolean {
  return item.verdictId != null
}
