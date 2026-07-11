import { describe, it, expect } from 'vitest'
import { buildPreyGroups, visibleGroupCount } from './preyGroups'

const p = (id: string, discoveredAt: string, confidence: number) => ({ id, discoveredAt, confidence })
const key = (iso: string) => iso.slice(0, 10)
const label = (iso: string) => iso.slice(0, 10)

describe('buildPreyGroups', () => {
  it('groups by day, newest day first', () => {
    const groups = buildPreyGroups(
      [p('a', '2026-07-09T08:00:00Z', 0.5), p('b', '2026-07-10T08:00:00Z', 0.5)],
      key, label,
    )
    expect(groups.map(g => g.key)).toEqual(['2026-07-10', '2026-07-09'])
  })
  it('sorts within a day by confidence descending', () => {
    const groups = buildPreyGroups(
      [
        p('low', '2026-07-10T09:00:00Z', 0.4),
        p('high', '2026-07-10T07:00:00Z', 0.9),
        p('mid', '2026-07-10T08:00:00Z', 0.7),
      ],
      key, label,
    )
    expect(groups[0].items.map(i => i.id)).toEqual(['high', 'mid', 'low'])
  })
})

describe('visibleGroupCount', () => {
  it('includes whole day groups until ~30 cards are visible', () => {
    expect(visibleGroupCount([20, 15, 10], 0)).toBe(2) // 20 < 30 -> add next group
    expect(visibleGroupCount([35, 10], 0)).toBe(1)
  })
  it('adds one more group per extra chunk', () => {
    expect(visibleGroupCount([20, 15, 10], 1)).toBe(3)
  })
  it('never exceeds the number of groups', () => {
    expect(visibleGroupCount([5], 4)).toBe(1)
    expect(visibleGroupCount([], 0)).toBe(0)
  })
})
