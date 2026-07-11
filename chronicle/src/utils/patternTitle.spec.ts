import { describe, it, expect } from 'vitest'
import { patternTitle } from './patternTitle'

describe('patternTitle', () => {
  it('returns short statements unchanged', () => {
    expect(patternTitle('Short statement.')).toBe('Short statement.')
  })

  it('truncates at a word boundary near 80 chars and appends an ellipsis', () => {
    const long =
      'Spin-offs from technology sector parents (SIC codes 7370-7379) significantly outperform spin-offs from industrial parents.'
    const title = patternTitle(long)
    expect(title.endsWith('…')).toBe(true)
    expect(title.length).toBeLessThanOrEqual(81)
    const body = title.slice(0, -1)
    expect(long.startsWith(body)).toBe(true)
    expect(long[body.length]).toBe(' ') // cut exactly at a word boundary
  })

  it('normalizes inner whitespace', () => {
    expect(patternTitle('a  b\n c')).toBe('a b c')
  })

  it('strips trailing punctuation before the ellipsis', () => {
    const s = 'word '.repeat(15) + 'tail,' + ' rest of the statement continues well beyond the limit'
    expect(patternTitle(s)).not.toMatch(/[,;:.]…$/)
  })

  it('hard-cuts a single overlong word', () => {
    const title = patternTitle('x'.repeat(200))
    expect(title.length).toBe(81)
    expect(title.endsWith('…')).toBe(true)
  })
})
