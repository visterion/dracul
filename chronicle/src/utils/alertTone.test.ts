import { describe, it, expect } from 'vitest'
import { alertTone } from './alertTone'

describe('alertTone', () => {
  it('maps explicit severities', () => {
    expect(alertTone('CRITICAL', 'elevated')).toBe('critical')
    expect(alertTone('WARNING', 'elevated')).toBe('warning')
    expect(alertTone('INFO', 'info')).toBe('info')
  })
  it('is case-insensitive and accepts WARN', () => {
    expect(alertTone('critical', 'elevated')).toBe('critical')
    expect(alertTone('WARN', 'elevated')).toBe('warning')
  })
  it('falls back to the coarse level for legacy rows without severity', () => {
    expect(alertTone(null, 'elevated')).toBe('warning')
    expect(alertTone(undefined, 'info')).toBe('info')
    expect(alertTone(null, 'neutral')).toBe('neutral')
  })
})
