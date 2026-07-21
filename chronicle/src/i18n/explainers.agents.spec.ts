import { describe, it, expect } from 'vitest'
import de from './explainers.de'
import en from './explainers.en'

const KEYS = [
  'hunter.daywalker', 'hunter.daywalker-deep', 'hunter.executor', 'hunter.gropar',
  'hunter.renfield', 'hunter.voievod', 'hunter.voievod-outcome', 'decision.overview',
]

describe('agent + decision explainers', () => {
  it.each(KEYS)('%s exists in de and en with matching structure', (k) => {
    expect(de[k], `de missing ${k}`).toBeTruthy()
    expect(en[k], `en missing ${k}`).toBeTruthy()
    expect(en[k].sections.length).toBe(de[k].sections.length)
    expect(en[k].sections.map(s => s.anchor)).toEqual(de[k].sections.map(s => s.anchor))
    de[k].sections.forEach((s, i) => {
      expect((en[k].sections[i].table ?? []).length).toBe((s.table ?? []).length)
    })
  })

  it('decision.overview is substantial (>=8 sections)', () => {
    expect(de['decision.overview'].sections.length).toBeGreaterThanOrEqual(8)
  })
})
