import { describe, it, expect } from 'vitest'
import { getExplainer } from './explainers'

const CONCEPTS = ['depot.metrics', 'orders.roles', 'calibration']
const VETO_ANCHORS = ['brokerError', 'noStop', 'lowConfidence', 'paceLimit', 'cooldown', 'belowAnchor']

describe('depot concept explainers', () => {
  it('exists de+en for every depot concept', () => {
    for (const c of CONCEPTS) for (const loc of ['de', 'en']) {
      const ex = getExplainer(loc, c)
      expect(ex.sections.length, `${loc} ${c}`).toBeGreaterThan(0)
    }
  })
  it('calibration explainer covers the common veto reasons + says it is not exhaustive', () => {
    const ex = getExplainer('de', 'calibration')
    const anchors = ex.sections.map(s => s.anchor)
    for (const a of VETO_ANCHORS) expect(anchors, a).toContain(a)
    expect(ex.sections.some(s => /nicht die vollständige/i.test(s.body))).toBe(true)
  })
})
