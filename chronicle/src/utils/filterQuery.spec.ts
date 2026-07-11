import { describe, it, expect } from 'vitest'
import { filterToQuery, queryToFilter } from './filterQuery'

describe('filterToQuery', () => {
  it('omits params for the default filter', () => expect(filterToQuery('all')).toEqual({}))
  it('maps high confidence to filter=high', () => expect(filterToQuery('high')).toEqual({ filter: 'high' }))
  it('maps anomaly classes to class=<AnomalyClass>', () => expect(filterToQuery('SPIN')).toEqual({ class: 'SPIN' }))
})

describe('queryToFilter', () => {
  it('round-trips all three shapes', () => {
    expect(queryToFilter({})).toBe('all')
    expect(queryToFilter({ filter: 'high' })).toBe('high')
    expect(queryToFilter({ class: 'PEAD' })).toBe('PEAD')
  })
  it('ignores unknown filter values', () => expect(queryToFilter({ filter: 'bogus' })).toBe('all'))
})
