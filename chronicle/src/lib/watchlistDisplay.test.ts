import { describe, it, expect } from 'vitest'
import { showsVerdictBadge } from './watchlistDisplay'

describe('showsVerdictBadge', () => {
  it('hides the badge for tracking items without a linked verdict', () => {
    expect(showsVerdictBadge({ verdictId: null })).toBe(false)
  })
  it('shows the badge only when a verdict is linked', () => {
    expect(showsVerdictBadge({ verdictId: 'verdict-1' })).toBe(true)
  })
})
