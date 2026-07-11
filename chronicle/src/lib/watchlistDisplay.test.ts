import { describe, it, expect } from 'vitest'
import { showsVerdictBadge, groupByOwner } from './watchlistDisplay'
import type { WatchlistItem } from '../api/types'

describe('showsVerdictBadge', () => {
  it('hides the badge for tracking items without a linked verdict', () => {
    expect(showsVerdictBadge({ verdictId: null })).toBe(false)
  })
  it('shows the badge only when a verdict is linked', () => {
    expect(showsVerdictBadge({ verdictId: 'verdict-1' })).toBe(true)
  })
})

const wi = (id: string, owner: string) => ({ id, owner } as WatchlistItem)

describe('groupByOwner', () => {
  it('puts my items first (unlabelled group), foreign owners after, alphabetically', () => {
    const items = [wi('1', 'zed@x.com'), wi('2', 'me@x.com'), wi('3', 'anna@x.com'), wi('4', 'me@x.com')]
    const groups = groupByOwner(items, 'me@x.com')
    expect(groups.map(g => g.owner)).toEqual(['', 'anna@x.com', 'zed@x.com'])
    expect(groups[0].items.map(i => i.id)).toEqual(['2', '4'])
  })

  it('preserves the incoming order inside each group', () => {
    const items = [wi('b', 'anna@x.com'), wi('a', 'anna@x.com')]
    expect(groupByOwner(items, 'me@x.com')[0].items.map(i => i.id)).toEqual(['b', 'a'])
  })

  it('omits the own group when I have no items', () => {
    const groups = groupByOwner([wi('1', 'anna@x.com')], 'me@x.com')
    expect(groups.map(g => g.owner)).toEqual(['anna@x.com'])
  })

  it('treats everything as foreign while /api/me has not resolved yet (me === "")', () => {
    const groups = groupByOwner([wi('1', 'anna@x.com')], '')
    expect(groups.map(g => g.owner)).toEqual(['anna@x.com'])
  })
})
