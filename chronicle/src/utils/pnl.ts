import { formatMoney } from './format'
import type { StrigoiPnlTrade } from '../api/types'

/** Sign colour for an EUR amount or R; '' for 0 and for an unknown (null) value. */
export function signClass(v: number | null | undefined): '' | 'pos' | 'neg' {
  if (v == null || v === 0) return ''
  return v > 0 ? 'pos' : 'neg'
}

/** TagPill tone for a signed figure. */
export function signTone(v: number | null | undefined): 'green' | 'crimson' | 'ash' {
  const c = signClass(v)
  return c === 'pos' ? 'green' : c === 'neg' ? 'crimson' : 'ash'
}

/** '—' for null: a missing amount is never shown as 0,00 €. */
export function formatEur(v: number | null | undefined): string {
  return v == null ? '—' : formatMoney(v, 'EUR')
}

/** What one trade earned so far: CLOSED = realized; OPEN = realized trims + unrealized,
 *  null when either part is unknown. */
export function tradeResultEur(t: StrigoiPnlTrade): number | null {
  if (t.status === 'CLOSED') return t.realizedEur
  if (t.realizedEur == null || t.unrealizedEur == null) return null
  return t.realizedEur + t.unrealizedEur
}
