import { describe, it, expect } from 'vitest'
import { displayName } from './instrument'

describe('displayName', () => {
  it('returns empty string when the name is missing', () => {
    expect(displayName('PYPL')).toBe('')
    expect(displayName('PYPL', null)).toBe('')
    expect(displayName('PYPL', '')).toBe('')
    expect(displayName('PYPL', '   ')).toBe('')
  })

  it('returns empty string when the name equals the symbol (case-insensitive)', () => {
    expect(displayName('PYPL', 'PYPL')).toBe('')
    expect(displayName('MSM', 'msm')).toBe('')
    expect(displayName('3750.HK', '3750.hk')).toBe('')
  })

  it('returns the name when it adds information', () => {
    expect(displayName('AVGO', 'Broadcom Inc.')).toBe('Broadcom Inc.')
  })

  it('trims surrounding whitespace before comparing and returning', () => {
    expect(displayName('AVGO', '  Broadcom Inc. ')).toBe('Broadcom Inc.')
    expect(displayName('AVGO', ' avgo ')).toBe('')
  })
})
