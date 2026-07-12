import type { DepotPositionView } from '../api/types'
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

/** Absolute local timestamp for the "Stand:" header, e.g. "12.07., 01:48:12".
 *  Deliberately NOT relative wording ("vor 3 Min") — the user wants to see
 *  exactly when the data was probed, not a fuzzy age bucket. */
export function formatAbsoluteTime(isoString: string): string {
  const d = new Date(isoString)
  const dd = String(d.getDate()).padStart(2, '0')
  const MM = String(d.getMonth() + 1).padStart(2, '0')
  const HH = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return `${dd}.${MM}., ${HH}:${mm}:${ss}`
}
