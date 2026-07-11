import { describe, it, expect } from 'vitest'
import { relativeTimeKey } from './useRelativeTime'

const DAY = 86_400_000

describe('relativeTimeKey', () => {
  it('uses the singular key for exactly one month', () => {
    expect(relativeTimeKey(35 * DAY)).toEqual({ key: 'time.oneMonthAgo' })
  })
  it('uses plural months beyond one', () => {
    expect(relativeTimeKey(65 * DAY)).toEqual({ key: 'time.monthsAgo', n: 2 })
  })
  it('keeps existing buckets intact', () => {
    expect(relativeTimeKey(30_000)).toEqual({ key: 'time.justNow' })
    expect(relativeTimeKey(5 * 60_000)).toEqual({ key: 'time.minutesAgo', n: 5 })
    expect(relativeTimeKey(3 * 3_600_000)).toEqual({ key: 'time.hoursAgo', n: 3 })
    expect(relativeTimeKey(1 * DAY)).toEqual({ key: 'time.yesterday' })
    expect(relativeTimeKey(3 * DAY)).toEqual({ key: 'time.daysAgo', n: 3 })
    expect(relativeTimeKey(14 * DAY)).toEqual({ key: 'time.weeksAgo', n: 2 })
  })
})
