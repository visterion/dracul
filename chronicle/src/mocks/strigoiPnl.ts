import type {
  StrigoiPnlDetail, StrigoiPnlOverview, StrigoiPnlSummary, StrigoiPnlTrade,
} from '../api/types'

/** Synthetic trades only (invented symbols/prices) — never exported from a running instance. */
const TRADES: Record<string, StrigoiPnlTrade[]> = {
  'strigoi-spin': [
    { positionId: 9101, symbol: 'TSTA', status: 'OPEN', entryDate: '2026-09-14', exitDate: null,
      qty: 12, entryPrice: 41.2, exitPrice: null, currency: 'USD', realizedEur: 0,
      unrealizedEur: 38.5, r: null, exitReason: null, flags: [] },
    { positionId: 9102, symbol: 'TSTB', status: 'CLOSED', entryDate: '2026-08-03', exitDate: '2026-09-02',
      qty: 20, entryPrice: 18.5, exitPrice: 21.1, currency: 'USD', realizedEur: 47.6,
      unrealizedEur: null, r: 1.3, exitReason: 'HARD_STOP', flags: [] },
    { positionId: 9103, symbol: 'TSTC', status: 'CLOSED', entryDate: '2026-07-21', exitDate: '2026-08-11',
      qty: 8, entryPrice: 64, exitPrice: 61.2, currency: 'USD', realizedEur: -20.6,
      unrealizedEur: null, r: -1, exitReason: 'HARD_STOP', flags: [] },
  ],
  'strigoi-echo': [
    { positionId: 9201, symbol: 'TSTD', status: 'OPEN', entryDate: '2026-09-29', exitDate: null,
      qty: 15, entryPrice: 22.4, exitPrice: null, currency: 'USD', realizedEur: 0,
      unrealizedEur: null, r: null, exitReason: null, flags: ['NO_PRICE'] },
    { positionId: 9202, symbol: 'TSTE', status: 'CLOSED', entryDate: '2026-08-18', exitDate: '2026-09-15',
      qty: 30, entryPrice: 9.8, exitPrice: 10.9, currency: 'USD', realizedEur: 30.4,
      unrealizedEur: null, r: 0.8, exitReason: 'HARD_KILL_CRITERIA', flags: [] },
  ],
}

export const MOCK_PNL_CONNECTION = 'depot-1'
const MOCK_USD_EUR = 0.92

function round2(v: number): number {
  return Math.round(v * 100) / 100
}

/** Same rules as the backend summary: sums skip null amounts, 0 is neither win nor loss. */
function summarize(strigoi: string, trades: StrigoiPnlTrade[]): StrigoiPnlSummary {
  const closed = trades.filter(t => t.status === 'CLOSED')
  const open = trades.filter(t => t.status === 'OPEN')
  const wins = closed.filter(t => (t.realizedEur ?? 0) > 0).length
  const losses = closed.filter(t => (t.realizedEur ?? 0) < 0).length
  const realizedEur = round2(trades.reduce((s, t) => s + (t.realizedEur ?? 0), 0))
  const unrealizedEur = round2(open.reduce((s, t) => s + (t.unrealizedEur ?? 0), 0))
  return {
    strigoi,
    closedTrades: closed.length,
    wins,
    losses,
    hitRate: closed.length ? wins / closed.length : null,
    realizedEur,
    unrealizedEur,
    totalEur: round2(realizedEur + unrealizedEur),
    sumR: round2(closed.reduce((s, t) => s + (t.r ?? 0), 0)),
    openPositions: open.length,
    openCostEur: round2(open.reduce((s, t) => s + (t.qty ?? 0) * (t.entryPrice ?? 0) * MOCK_USD_EUR, 0)),
    flaggedTrades: trades.filter(t => t.flags.length > 0).length,
  }
}

export function mockStrigoiPnlOverview(connection: string): StrigoiPnlOverview {
  const strigoi = connection === MOCK_PNL_CONNECTION
    ? Object.entries(TRADES).map(([name, trades]) => summarize(name, trades))
      .sort((a, b) => a.strigoi.localeCompare(b.strigoi))
    : []
  return { connection, currency: 'EUR', fxBasis: 'current', strigoi }
}

export function mockStrigoiPnlDetail(name: string, connection: string): StrigoiPnlDetail {
  const trades = connection === MOCK_PNL_CONNECTION ? (TRADES[name] ?? []) : []
  return { connection, currency: 'EUR', fxBasis: 'current', summary: summarize(name, trades), trades }
}
