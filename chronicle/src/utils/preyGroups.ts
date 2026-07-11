export interface DayGroup<T> { key: string; label: string; items: T[] }

/** Day groups (newest day first), items within a day by confidence desc. */
export function buildPreyGroups<T extends { discoveredAt: string; confidence: number }>(
  prey: T[],
  keyFor: (iso: string) => string,
  labelFor: (iso: string) => string,
): DayGroup<T>[] {
  const sorted = [...prey].sort(
    (a, b) => new Date(b.discoveredAt).getTime() - new Date(a.discoveredAt).getTime(),
  )
  const groups: DayGroup<T>[] = []
  for (const p of sorted) {
    const key = keyFor(p.discoveredAt)
    let g = groups.find(x => x.key === key)
    if (!g) {
      g = { key, label: labelFor(p.discoveredAt), items: [] }
      groups.push(g)
    }
    g.items.push(p)
  }
  for (const g of groups) g.items.sort((a, b) => b.confidence - a.confidence)
  return groups
}

/** How many day groups to render: whole groups up to ~baseCards, plus extraGroups. */
export function visibleGroupCount(groupSizes: number[], extraGroups: number, baseCards = 30): number {
  let count = 0
  let cards = 0
  while (count < groupSizes.length && cards < baseCards) {
    cards += groupSizes[count]
    count++
  }
  return Math.min(groupSizes.length, count + extraGroups)
}
