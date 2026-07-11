import { describe, it, expect } from 'vitest'
import { isNavActive } from './useNavItems'

const chronicle = ['/', '/prey', '/verdict', '/strigoi', '/exit-signal']

describe('isNavActive', () => {
  it('matches chronicle on the root path only for "/"', () => {
    expect(isNavActive(chronicle, '/')).toBe(true)
    expect(isNavActive(['/watchlist'], '/')).toBe(false)
  })
  it('keeps chronicle active on detail routes', () => {
    expect(isNavActive(chronicle, '/prey/abc-123')).toBe(true)
    expect(isNavActive(chronicle, '/verdict/v-9')).toBe(true)
    expect(isNavActive(chronicle, '/strigoi/strigoi-echo')).toBe(true)
    expect(isNavActive(chronicle, '/exit-signal/es-1')).toBe(true)
  })
  it('does not match unrelated prefixes', () => {
    expect(isNavActive(chronicle, '/portfolio')).toBe(false)
    expect(isNavActive(['/report'], '/reportage')).toBe(false)
  })
  it('matches exact and nested paths for plain sections', () => {
    expect(isNavActive(['/settings'], '/settings')).toBe(true)
    expect(isNavActive(['/patterns'], '/patterns')).toBe(true)
  })
})
