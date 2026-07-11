import type { WatchlistItem } from '../api/types'

/**
 * "Urteil verfolgt" only means something when the item is actually linked to
 * a verdict. tag === 'TRACKING' also holds for manually added tickers, which
 * made every row wear the badge.
 */
export function showsVerdictBadge(item: Pick<WatchlistItem, 'verdictId'>): boolean {
  return item.verdictId != null
}

export interface OwnerGroup {
  /** '' for the current user's own group (rendered without separator). */
  owner: string
  items: WatchlistItem[]
}

/** Own items first, then one labelled group per foreign owner (alphabetical). */
export function groupByOwner(items: WatchlistItem[], me: string): OwnerGroup[] {
  const mine: WatchlistItem[] = []
  const foreign = new Map<string, WatchlistItem[]>()
  for (const item of items) {
    if (me !== '' && item.owner === me) {
      mine.push(item)
    } else {
      const list = foreign.get(item.owner) ?? []
      list.push(item)
      foreign.set(item.owner, list)
    }
  }
  const groups: OwnerGroup[] = []
  if (mine.length > 0) groups.push({ owner: '', items: mine })
  for (const owner of [...foreign.keys()].sort()) {
    groups.push({ owner, items: foreign.get(owner)! })
  }
  return groups
}
