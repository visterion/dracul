import type { Depot, DepotPositionView } from '../api/types'
import type { DisplayMode } from '../composables/useDisplayMode'
import { formatMoney, formatPercent } from '../utils/format'

/** Renders an abs/% pair according to the shared display-mode toggle.
 *  Missing values (no quotes yet, no cost basis) render as an em dash —
 *  NEVER as 0, so "no data" is never mistaken for "no change". */
export function fmtPl(abs: number | null, pct: number | null, mode: DisplayMode, currency: string): string {
  if (mode === 'pct') {
    return pct == null ? '—' : formatPercent(pct)
  }
  return abs == null ? '—' : formatMoney(abs, currency)
}

/** A position's `dayChangePercent` is the only day-change field the API sends;
 *  the abs figure is derived the same way the mock aggregator does (marketValue
 *  scaled by the percent move) so the abs/% toggle has a real number to show. */
export function positionDayChangeAbs(p: DepotPositionView): number | null {
  return p.dayChangePercent == null ? null : Math.round(p.marketValue * (p.dayChangePercent / 100) * 100) / 100
}

export interface DepotTotals {
  totalValue: number
  totalCash: number
  totalDayChangeAbs: number | null
  totalDayChangePct: number | null
  currency: string
}

/** Summary-bar aggregates across all depots. Mixing currencies across depots
 *  is a known simplification (see documentation/chronicle.md) — the first
 *  depot with an account carries the display currency. */
export function depotTotals(depots: Depot[]): DepotTotals {
  const withAccount = depots.filter(d => d.account !== null)
  const totalValue = withAccount.reduce((s, d) => s + d.account!.equity, 0)
  const totalCash = withAccount.reduce((s, d) => s + d.account!.cash, 0)
  const currency = withAccount[0]?.account?.currency ?? 'EUR'

  const dayChanges = depots
    .map(d => d.aggregates?.dayChangeAbs)
    .filter((v): v is number => v != null)
  const totalDayChangeAbs = dayChanges.length ? dayChanges.reduce((a, b) => a + b, 0) : null

  const previousValue = totalDayChangeAbs != null ? totalValue - totalDayChangeAbs : null
  const totalDayChangePct = totalDayChangeAbs != null && previousValue
    ? Math.round((totalDayChangeAbs / previousValue) * 10000) / 100
    : null

  return {
    totalValue: Math.round(totalValue * 100) / 100,
    totalCash: Math.round(totalCash * 100) / 100,
    totalDayChangeAbs,
    totalDayChangePct,
    currency,
  }
}

const ALLOCATION_COLORS = [
  'var(--cathedral-gold)',
  'var(--blood-crimson-bright)',
  'var(--signal-positive-bright)',
  'var(--ash-gray-light)',
  'var(--moonlight-silver)',
  'var(--bone-ivory-dim)',
]

export interface AllocationSegment {
  symbol: string
  weightPct: number
  color: string
}

/** Builds the segments for the single stacked allocation bar, cycling through
 *  the design system's accent tokens so no two adjacent segments repeat a
 *  color for small position counts. */
export function allocationSegments(positions: DepotPositionView[]): AllocationSegment[] {
  return positions
    .filter(p => p.weightPct != null)
    .sort((a, b) => (b.weightPct ?? 0) - (a.weightPct ?? 0))
    .map((p, i) => ({ symbol: p.symbol, weightPct: p.weightPct as number, color: ALLOCATION_COLORS[i % ALLOCATION_COLORS.length] }))
}

/** 15 minutes — probe/quote data older than this reads as stale in the header. */
export const STALE_MS = 15 * 60_000

export function isStale(asOf: string | null, now: number = Date.now()): boolean {
  if (!asOf) return false
  return now - new Date(asOf).getTime() > STALE_MS
}
