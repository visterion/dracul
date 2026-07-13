import { describe, it, expect } from 'vitest'
import { formatMoney, formatMoneyPair, formatNumber, formatPercent, formatHorizon, microsToUsdInput, pctClass } from './format'

const n = (s: string) => s.replace(/[\u00a0\u202f]/g, ' ')

describe('formatMoney', () => {
  it('formats de-DE with 2 decimals and symbol', () => {
    expect(n(formatMoney(434.8, 'USD'))).toBe('434,80 $')
    expect(n(formatMoney(398.76, 'EUR'))).toBe('398,76 €')
  })
  it('defaults to USD', () => expect(n(formatMoney(1))).toBe('1,00 $'))
})

describe('formatMoneyPair', () => {
  it('renders original first, converted in parentheses', () => {
    expect(n(formatMoneyPair(434.8, 'USD', { value: 398.76, currency: 'EUR' })))
      .toBe('434,80 $ (398,76 €)')
  })
  it('omits parentheses without conversion or for same currency', () => {
    expect(n(formatMoneyPair(434.8, 'USD'))).toBe('434,80 $')
    expect(n(formatMoneyPair(434.8, 'EUR', { value: 434.8, currency: 'EUR' }))).toBe('434,80 €')
  })
})

describe('formatNumber', () => {
  it('groups thousands in de-DE', () => expect(formatNumber(1234567)).toBe('1.234.567'))
  it('honors fraction digits', () => expect(formatNumber(0.87, 2)).toBe('0,87'))
})

describe('formatPercent', () => {
  it('signs positive values, 1 decimal', () => expect(n(formatPercent(4.36))).toBe('+4,4 %'))
  it('keeps negative sign', () => expect(n(formatPercent(-2))).toBe('-2,0 %'))
  it('does not sign zero', () => expect(n(formatPercent(0))).toBe('0,0 %'))
})

describe('formatHorizon', () => {
  it('normalizes month shorthands', () => {
    expect(formatHorizon('3m')).toBe('3 Monate')
    expect(formatHorizon('1m')).toBe('1 Monat')
  })
  it('normalizes day shorthands and passthrough German', () => {
    expect(formatHorizon('90d')).toBe('90 Tage')
    expect(formatHorizon('90 Tage')).toBe('90 Tage')
    expect(formatHorizon('1d')).toBe('1 Tag')
  })
  it('returns unknown input verbatim', () => expect(formatHorizon('open-ended')).toBe('open-ended'))
})

describe('microsToUsdInput', () => {
  it('renders dot-decimal for inputs', () => expect(microsToUsdInput(1_500_000)).toBe('1.50'))
  it('renders infinity for null', () => expect(microsToUsdInput(null)).toBe('∞'))
})

describe('pctClass', () => {
  it('positive → pos', () => expect(pctClass(3.4)).toBe('pos'))
  it('negative → neg', () => expect(pctClass(-6)).toBe('neg'))
  it('rounds to 0.0 % → neutral', () => {
    expect(pctClass(-0.02)).toBe('')
    expect(pctClass(0.04)).toBe('')
    expect(pctClass(0)).toBe('')
  })
  it('null → neutral', () => expect(pctClass(null)).toBe(''))
})
