import { describe, it, expect } from 'vitest'
import { computeEdgeFades } from './useEdgeFades'

describe('computeEdgeFades', () => {
  it('shows no fades when content fits', () => {
    expect(computeEdgeFades(0, 400, 400)).toEqual({ left: false, right: false })
  })
  it('shows only right fade at the far left', () => {
    expect(computeEdgeFades(0, 400, 800)).toEqual({ left: false, right: true })
  })
  it('shows both fades mid-scroll', () => {
    expect(computeEdgeFades(200, 400, 800)).toEqual({ left: true, right: true })
  })
  it('shows only left fade at the far right', () => {
    expect(computeEdgeFades(400, 400, 800)).toEqual({ left: true, right: false })
  })
  it('tolerates sub-pixel rounding at the edges', () => {
    expect(computeEdgeFades(399.4, 400, 800)).toEqual({ left: true, right: false })
  })
})
