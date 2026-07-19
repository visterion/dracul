import { describe, it, expect } from 'vitest'
import { getExplainer } from './explainers'

const HUNTERS = ['spin', 'insider', 'echo', 'lazarus', 'index', 'merger']

describe('hunter explainers', () => {
  it('has a de + en explainer for every hunter with idea/inputs/strike sections', () => {
    for (const h of HUNTERS) {
      for (const loc of ['de', 'en']) {
        const ex = getExplainer(loc, `hunter.${h}`)
        expect(ex.title.length, `${loc} ${h} title`).toBeGreaterThan(0)
        expect(ex.sections.map(s => s.anchor).sort()).toEqual(['idea', 'inputs', 'strike'])
        for (const s of ex.sections) expect(s.body.length, `${loc} ${h} ${s.anchor}`).toBeGreaterThan(20)
      }
    }
  })

  it('has a de + en overview explainer', () => {
    for (const loc of ['de', 'en']) {
      const ex = getExplainer(loc, 'hunter.overview')
      expect(ex.sections.length).toBeGreaterThan(1)
    }
  })
})
