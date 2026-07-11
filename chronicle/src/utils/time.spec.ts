import { describe, it, expect } from 'vitest'
import { countSince, localMidnight } from './time'

describe('localMidnight', () => {
  it('returns today at 00:00 local time', () => {
    const m = localMidnight(new Date(2026, 6, 11, 15, 30))
    expect([m.getFullYear(), m.getMonth(), m.getDate(), m.getHours(), m.getMinutes()])
      .toEqual([2026, 6, 11, 0, 0])
  })
})

describe('countSince', () => {
  it('counts only instants at or after the boundary', () => {
    const midnight = new Date(2026, 6, 11, 0, 0)
    const dates = [
      new Date(2026, 6, 11, 8, 0).toISOString(),  // today
      new Date(2026, 6, 10, 23, 59).toISOString(), // yesterday
      'not-a-date',
    ]
    expect(countSince(dates, midnight)).toBe(1)
  })
})
