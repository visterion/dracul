import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { compactAge, useStatusStore } from './status'

describe('compactAge', () => {
  it('formats seconds', () => expect(compactAge(30)).toBe('30s'))
  it('formats minutes', () => expect(compactAge(5 * 60)).toBe('5m'))
  it('formats hours', () => expect(compactAge(3 * 3600)).toBe('3h'))
  it('formats days below a week', () => expect(compactAge(2 * 86400)).toBe('2d'))
  it('rolls over to weeks at 7 days', () => expect(compactAge(7 * 86400)).toBe('1w'))
  it('formats multiple weeks', () => expect(compactAge(14 * 86400)).toBe('2w'))
  it('rolls over to months at ~30 days', () => expect(compactAge(30 * 86400)).toBe('1mo'))
  it('does not show 56d for an eight-week gap', () => expect(compactAge(56 * 86400)).toBe('1mo'))
})

describe('lastVerdictRelative', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('returns "—" when there is no status', () => {
    const store = useStatusStore()
    expect(store.lastVerdictRelative).toBe('—')
  })
})
