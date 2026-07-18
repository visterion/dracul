import type {
  Depot, DepotsResponse, DepotChart, DepotPositionView, DepotAggregates,
  InstrumentInfo, DepotHistory,
} from '../api/types'

// ── Helpers ──────────────────────────────────────────────────────

/** Aggregates derived from the position list using the same formulas the
 *  backend uses (see documentation/api.md § Depots): investedValue and
 *  totalUnrealizedPl are plain sums; dayChangeAbs is the sum of each
 *  position's marketValue-weighted day change; dayChangePct is dayChangeAbs
 *  relative to the depot's pre-move value. */
function aggregatesFor(positions: DepotPositionView[]): DepotAggregates {
  const investedValue = positions.reduce((s, p) => s + p.qty * p.avgEntryPrice, 0)
  const totalUnrealizedPl = positions.reduce((s, p) => s + p.unrealizedPl, 0)
  const totalUnrealizedPlPct = investedValue !== 0
    ? Math.round((totalUnrealizedPl / investedValue) * 10000) / 100
    : null
  const marketValue = positions.reduce((s, p) => s + p.marketValue, 0)
  const dayChangeAbs = positions.reduce(
    (s, p) => s + p.marketValue * ((p.dayChangePercent ?? 0) / 100),
    0,
  )
  const previousValue = marketValue - dayChangeAbs
  const dayChangePct = previousValue !== 0
    ? Math.round((dayChangeAbs / previousValue) * 10000) / 100
    : null
  return {
    investedValue: Math.round(investedValue * 100) / 100,
    totalUnrealizedPl: Math.round(totalUnrealizedPl * 100) / 100,
    totalUnrealizedPlPct,
    dayChangeAbs: Math.round(dayChangeAbs * 100) / 100,
    dayChangePct,
  }
}

function withWeights(positions: Omit<DepotPositionView, 'weightPct'>[]): DepotPositionView[] {
  const total = positions.reduce((s, p) => s + p.marketValue, 0)
  return positions.map(p => ({
    ...p,
    weightPct: total !== 0 ? Math.round((p.marketValue / total) * 10000) / 100 : null,
  }))
}

// ── depot-1 (paper, alpaca) ──────────────────────────────────────

const depot1Positions = withWeights([
  {
    symbol: 'NVDA', qty: 10, avgEntryPrice: 120.00, marketValue: 1350.00,
    unrealizedPl: 150.00, unrealizedPlPct: 12.50, price: 135.00,
    dayChangePercent: 1.20, currency: 'USD',
    name: 'NVIDIA Corporation', assetType: 'Stock', valueDate: '2026-01-15',
    nativePrice: null, nativeCurrency: null,
  },
  {
    symbol: 'ABB', qty: 20, avgEntryPrice: 35.00, marketValue: 770.00,
    unrealizedPl: 70.00, unrealizedPlPct: 10.00, price: 38.50,
    dayChangePercent: -0.40, currency: 'USD',
    name: 'ABB Ltd', assetType: 'Stock', valueDate: '2026-02-03',
    nativePrice: null, nativeCurrency: null,
  },
  {
    symbol: 'TSM', qty: 15, avgEntryPrice: 150.00, marketValue: 2175.00,
    unrealizedPl: -75.00, unrealizedPlPct: -3.33, price: 145.00,
    dayChangePercent: 0.80, currency: 'USD',
    name: 'Taiwan Semiconductor Manufacturing', assetType: 'Stock', valueDate: '2025-11-20',
    nativePrice: null, nativeCurrency: null,
  },
])

const depot1MarketValue = depot1Positions.reduce((s, p) => s + p.marketValue, 0)
const depot1Cash = 500.00

export const mockDepotPaper: Depot = {
  id: 'depot-1',
  provider: 'alpaca',
  environment: 'paper',
  status: 'connected',
  probedAt: '2026-07-11T08:00:00Z',
  error: null,
  account: {
    cash: depot1Cash,
    equity: Math.round((depot1MarketValue + depot1Cash) * 100) / 100,
    buyingPower: 1000.00,
    currency: 'USD',
    status: 'ACTIVE',
    asOf: '2026-07-11T08:00:00Z',
  },
  aggregates: aggregatesFor(depot1Positions),
  positions: depot1Positions,
  orders: [
    { brokerOrderId: 'o-1001', symbol: 'NVDA', side: 'buy', qty: 10, type: 'market', status: 'filled', role: 'entry' },
    { brokerOrderId: 'o-1002', symbol: 'ABB', side: 'buy', qty: 20, type: 'market', status: 'filled', role: 'entry' },
    { brokerOrderId: 'o-1003', symbol: 'TSM', side: 'buy', qty: 15, type: 'market', status: 'filled', role: 'entry' },
  ],
  asOf: '2026-07-11T08:00:00Z',
}

// ── saxo-live-1 (live) ───────────────────────────────────────────

const liveDepotPositions = withWeights([
  {
    symbol: 'ASML', qty: 5, avgEntryPrice: 650.00, marketValue: 3400.00,
    unrealizedPl: 150.00, unrealizedPlPct: 4.62, price: 680.00,
    dayChangePercent: 0.50, currency: 'EUR',
    name: 'ASML Holding N.V.', assetType: 'Stock', valueDate: '2026-03-10',
    nativePrice: 720.50, nativeCurrency: 'CHF',
  },
])

const liveMarketValue = liveDepotPositions.reduce((s, p) => s + p.marketValue, 0)
const liveCash = 200.00

export const mockDepotLive: Depot = {
  id: 'saxo-live-1',
  provider: 'saxo',
  environment: 'live',
  status: 'connected',
  probedAt: '2026-07-11T08:00:00Z',
  error: null,
  account: {
    cash: liveCash,
    equity: Math.round((liveMarketValue + liveCash) * 100) / 100,
    buyingPower: liveCash,
    currency: 'EUR',
    status: 'ACTIVE',
    asOf: '2026-07-11T08:00:00Z',
  },
  aggregates: aggregatesFor(liveDepotPositions),
  positions: liveDepotPositions,
  orders: [
    { brokerOrderId: 'o-2001', symbol: 'ASML', side: 'buy', qty: 5, type: 'market', status: 'filled', role: 'entry' },
  ],
  asOf: '2026-07-11T08:00:00Z',
}

export const mockDepots: Depot[] = [mockDepotPaper, mockDepotLive]

export const mockDepotsResponse: DepotsResponse = {
  depots: mockDepots,
  error: null,
}

// ── Charts ───────────────────────────────────────────────────────

const CHART_POINT_COUNT = 30

/** 30-point close-price-style series with a mild upward drift + noise,
 *  deterministic so tests/snapshots stay stable. */
function buildSeries(base: number, drift: number): number[] {
  return Array.from({ length: CHART_POINT_COUNT }, (_, i) => {
    const trend = base + drift * i
    const noise = base * 0.015 * Math.sin(i * 0.6)
    return Math.round((trend + noise) * 100) / 100
  })
}

function datesFor(count: number): string[] {
  const today = new Date('2026-07-11T00:00:00Z')
  return Array.from({ length: count }, (_, i) => {
    const d = new Date(today)
    d.setUTCDate(d.getUTCDate() - (count - 1 - i))
    return d.toISOString().slice(0, 10)
  })
}

const depotCurveValues = buildSeries(4150, 5)
const depotCurveDates = datesFor(CHART_POINT_COUNT)
const depotCurveBase = depotCurveValues[0]

export const mockDepotChart: DepotChart = {
  points: depotCurveDates.map((t, i) => ({ t, value: depotCurveValues[i] })),
  relative: depotCurveDates.map((t, i) => ({
    t,
    pct: Math.round(((depotCurveValues[i] / depotCurveBase - 1) * 100) * 100) / 100,
  })),
  partial: false,
}

const instrumentCloseValues = buildSeries(135, 0.6)
const instrumentDates = datesFor(CHART_POINT_COUNT)

export const mockInstrumentChart: DepotChart = {
  points: instrumentDates.map((t, i) => ({ t, value: instrumentCloseValues[i] })),
}

// ── Instrument info bundle ─────────────────────────────────────

export const mockInstrumentInfo: InstrumentInfo = {
  symbol: 'NVDA',
  profile: {
    symbol: 'NVDA',
    name: 'NVIDIA Corporation',
    industry: 'Semiconductors',
    exchange: 'NASDAQ',
    marketCap: 3_200_000_000_000,
  },
  news: {
    symbol: 'NVDA',
    news: [
      { headline: 'NVIDIA unveils next-gen datacenter GPU', source: 'Reuters', publishedAt: '2026-07-05T12:00:00Z' },
      { headline: 'Analysts raise NVDA price target on AI demand', source: 'Bloomberg', publishedAt: '2026-07-08T09:30:00Z' },
    ],
  },
  earnings: {
    earnings: [
      { symbol: 'NVDA', period: 'Q2 2026', reportDate: '2026-08-20', epsEstimate: 0.85, epsActual: null },
    ],
  },
  analystEstimates: {
    symbol: 'NVDA',
    recommendations: [
      { period: '2026-07', buy: 38, hold: 5, sell: 1, strongBuy: 20, strongSell: 0 },
    ],
  },
  earningsEstimates: {
    symbol: 'NVDA',
    estimates: [
      { period: 'Q2 2026', epsAvg: 0.85, epsLow: 0.78, epsHigh: 0.92, revenueAvg: 45_000_000_000 },
    ],
  },
  fundamentalScore: { symbol: 'NVDA', score: 8 },
  fundamentals: { symbol: 'NVDA', peRatio: 42.3, pbRatio: 30.1, dividendYield: 0.03 },
  insiderActivity: {
    transactions: [
      { ticker: 'NVDA', insider: 'Jensen Huang', transactionDate: '2026-06-15', type: 'sell', shares: 50000, price: 132.10 },
    ],
  },
}

// ── History ──────────────────────────────────────────────────────

export const mockDepotHistory: DepotHistory = {
  entries: [
    {
      source: 'ORDER', symbol: 'AAPL', side: 'buy', qty: 10, entryPrice: 100, exitPrice: 110,
      profitLoss: 100, status: 'filled', brokerOrderId: 'o-1', brokerConfirmed: true,
      why: { strigoi: 'index-strigoi', killCriteria: ['stop below 95'],
             entryReasoning: 'index inclusion drift', draculExitReason: 'TAKE_PROFIT', draculRealizedR: 2 },
    },
  ],
  error: null,
}
