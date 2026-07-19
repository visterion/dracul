import { describe, it, expect } from 'vitest'
import { getExplainer, explainerKeys } from './explainers'

describe('explainer registry', () => {
  it('resolves the same keys in de and en', () => {
    const de = explainerKeys('de')
    const en = explainerKeys('en')
    expect(new Set(en)).toEqual(new Set(de))
  })

  it('returns a titled, sectioned explainer for a known key', () => {
    const ex = getExplainer('de', 'orders.bracket')
    expect(ex.title.length).toBeGreaterThan(0)
    expect(ex.sections.length).toBeGreaterThan(0)
    expect(ex.sections[0].heading.length).toBeGreaterThan(0)
    expect(ex.sections[0].body.length).toBeGreaterThan(0)
  })

  it('falls back to de for an unknown locale', () => {
    expect(getExplainer('fr', 'orders.bracket')).toEqual(getExplainer('de', 'orders.bracket'))
  })
})
