import type { Prey, AnomalyType } from '../api/types'

const ago = (hours: number) =>
  new Date(Date.now() - hours * 60 * 60 * 1000).toISOString()

const curatedPrey: Prey[] = [
  {
    id: 'prey-1',
    symbol: 'AVGO',
    companyName: 'Broadcom Inc',
    anomalyType: 'SPIN',
    confidence: 0.84,
    thesis:
      'Spin-off completion combined with forced institutional selling has compressed price below intrinsic value. Historical parallels with similar tech spin-offs suggest 60–90 day recovery as rebalancing pressure normalizes.',
    signals: [
      'Spin-off forced selling pressure from index funds with mid-cap mandates',
      'Price below EBITDA-implied intrinsic value by ~18%',
      'Sector peers trade at 30% premium on comparable metrics',
    ],
    risks: [
      'Macro headwinds may delay recovery timeline',
      'Possible additional rebalancing selling at quarter-end',
    ],
    killCriteria: [
      'Close below the post-spin low of 148.20',
      'No stabilization above spin-day close by 2026-09-30',
    ],
    horizon: '90d',
    discoveredBy: 'strigoi-spin',
    discoveredAt: ago(2),
  },
  {
    id: 'prey-2',
    symbol: 'NVDA',
    companyName: 'NVIDIA Corp',
    anomalyType: 'PEAD',
    confidence: 0.87,
    thesis:
      'Earnings beat by 14% YoY with raised guidance. Post-announcement drift in semiconductor sector with this magnitude historically correlates with 60-day continued upward drift in 7 of 10 comparable cases.',
    signals: [
      'Q3 EPS +14% YoY, guidance raised for Q4',
      'Unusual options activity: call volume 4× average in the 72 hours post-announcement',
      'Options flow suggests institutional accumulation',
    ],
    risks: [
      'Sector rotation risk if rates rise unexpectedly',
      'Macro headwinds on enterprise capex spending',
    ],
    killCriteria: [
      'Drift reverses with a close below the pre-earnings gap-up level',
      'Guidance walk-back or negative pre-announcement within the 60-day window',
    ],
    horizon: '60d',
    discoveredBy: 'strigoi-echo',
    discoveredAt: ago(4),
  },
  {
    id: 'prey-3',
    symbol: 'MELI',
    companyName: 'MercadoLibre Inc',
    anomalyType: 'SPIN',
    confidence: 0.72,
    thesis:
      'FinTech subsidiary separation creating short-term forced selling from EM-focused funds unable to hold pure FinTech names under their mandate constraints.',
    signals: [
      'EM fund mandate mismatch — forced sellers with 30-day liquidation window',
      'FinTech peers trade at 30% premium on GMV-implied value',
      'Management commentary consistently bullish on standalone entity',
    ],
    risks: [
      'EM currency risk, particularly BRL/USD exposure',
      'Regulatory headwinds from Banco Central do Brasil',
    ],
    killCriteria: [
      'FinTech unit trades below GMV-implied value 30 days post-separation',
      'Management reverses guidance on standalone profitability targets',
    ],
    horizon: '180d',
    discoveredBy: 'strigoi-spin',
    discoveredAt: ago(6),
  },
  {
    id: 'prey-4',
    symbol: 'AMD',
    companyName: 'Advanced Micro Devices',
    anomalyType: 'INSIDER',
    confidence: 0.65,
    thesis:
      'CFO and two board members purchased on the same day following a 20% drawdown. Cluster characteristics — CFO presence, same-day timing, below 52-week average — match historical precedents with 90-day positive outcomes.',
    signals: [
      'CFO purchase $1.2M at $167.40 on May 14',
      'Two independent board members purchased same day',
      'Combined insider purchase at multi-month price low',
    ],
    risks: [
      'Competitive pressure from Intel Gaudi in AI accelerator space',
      'AI spend concentration risk — top 5 customers represent 60% of data center revenue',
    ],
    killCriteria: [
      'Any insider files a Form 4 sale within 90 days of the cluster buy',
      'Close below the pre-cluster low of $167.40',
    ],
    horizon: '90d',
    discoveredBy: 'strigoi-insider',
    discoveredAt: ago(8),
  },
  {
    id: 'prey-5',
    symbol: 'MSFT',
    companyName: 'Microsoft Corp',
    anomalyType: 'LAZARUS',
    confidence: 0.58,
    thesis:
      'Quality company at 52-week low on sector rotation rather than fundamental deterioration. Piotroski F-Score of 8 indicates strong financial health. FCF yield at historically elevated levels relative to 10-year average.',
    signals: [
      '52-week low territory on rotation, not fundamental weakness',
      'Piotroski F-Score: 8 (strong financial health)',
      'FCF yield at 3.2% — top decile vs 10-year history',
    ],
    risks: [
      'Continued sector rotation may extend drawdown before recovery',
      'Valuation remains premium vs peers even at 52-week low',
    ],
    killCriteria: [
      'Piotroski F-Score drops below 6 on next quarterly filing',
      'Price sets a new 52-week low after an initial stabilization above the prior low',
    ],
    horizon: '180d',
    discoveredBy: 'strigoi-lazarus',
    discoveredAt: ago(10),
  },
  {
    id: 'prey-6',
    symbol: 'KD',
    companyName: 'Kyndryl Holdings',
    anomalyType: 'SPIN',
    confidence: 0.58,
    thesis: 'Recently separated IT-services spin-off trades below book value while service-contract renewals stabilize.',
    signals: ['Renewal rate stabilizing above 90%', 'Trades below tangible book value'],
    risks: ['Legacy contract margin pressure'],
    killCriteria: [],
    horizon: '90d',
    discoveredBy: 'strigoi-spin',
    discoveredAt: ago(30),
  },
]

// UX-audit D2: exercise the >30-card day-group chunking path end-to-end.
// The six curated prey span only ~2 days (<30 cards), so "Ältere Beute
// anzeigen" never appears in mock mode. Spread bulk filler across three
// calendar days (16/16/8) so the first visible groups exceed the 30-card
// threshold (buildPreyGroups + visibleGroupCount) and one older group stays
// hidden until the user expands it.
const daysAgo = (days: number, i: number) =>
  new Date(Date.now() - days * 86_400_000 - i * 1000).toISOString()

const anomalyCycle: AnomalyType[] = ['SPIN', 'PEAD', 'INSIDER', 'LAZARUS', 'INDEX', 'MERGER']

// Archived fixture: discoveredAt is far enough in the past that even the
// longest horizon (180d) has expired, so this prey is unambiguously
// archived. Used by MockApiClient.getChronicle to exercise the
// includeArchived toggle under VITE_MOCK=true.
export const archivedPrey: Prey = {
  id: 'prey-archived-1',
  symbol: 'ARCH',
  companyName: 'Archived Holdings Inc',
  anomalyType: 'MERGER',
  confidence: 0.61,
  thesis: 'Merger-arbitrage thesis whose horizon has long since expired; kept as a fixed fixture for the archive toggle.',
  signals: ['Filler signal'],
  risks: ['Filler risk'],
  killCriteria: ['Filler kill criterion'],
  horizon: '30d',
  discoveredBy: 'strigoi-merger',
  discoveredAt: daysAgo(200, 0),
}

const bulkPrey: Prey[] = ([[0, 16], [1, 16], [2, 8]] as const).flatMap(([days, n]) =>
  Array.from({ length: n }, (_, i): Prey => {
    const idx = days * 100 + i
    return {
      id: `prey-bulk-${idx}`,
      symbol: `BULK${idx}`,
      companyName: `Bulk Holdings ${idx}`,
      anomalyType: anomalyCycle[idx % anomalyCycle.length],
      confidence: 0.5 + (idx % 40) / 100,
      thesis: 'Synthetic filler prey used to exercise the day-group chunking path in mock mode.',
      signals: ['Filler signal'],
      risks: ['Filler risk'],
      killCriteria: ['Filler kill criterion'],
      horizon: '90d',
      discoveredBy: 'strigoi-spin',
      discoveredAt: daysAgo(days, i),
    }
  }),
)

export const mockPrey: Prey[] = [...curatedPrey, ...bulkPrey]
