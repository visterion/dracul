import { describe, it, expect } from 'vitest'
import { budgetLevel } from './budget'

describe('budgetLevel', () => {
  it('is ok below 80%', () => {
    expect(budgetLevel(0, 5)).toBe('ok')
    expect(budgetLevel(3.99, 5)).toBe('ok')
  })
  it('warns from 80% inclusive', () => {
    expect(budgetLevel(4, 5)).toBe('warn')
    expect(budgetLevel(4.99, 5)).toBe('warn')
  })
  it('is over from 100% inclusive', () => {
    expect(budgetLevel(5, 5)).toBe('over')
    expect(budgetLevel(7.5, 5)).toBe('over')
  })
  it('treats a missing cap as ok', () => {
    expect(budgetLevel(3, 0)).toBe('ok')
  })
})
