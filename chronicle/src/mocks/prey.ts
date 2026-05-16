import type { Prey } from '../api/types'

const ago = (hours: number) =>
  new Date(Date.now() - hours * 60 * 60 * 1000).toISOString()

export const mockPrey: Prey[] = [
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
      'Insider buying of $2.1M in the 72 hours post-announcement',
      'Options flow suggests institutional accumulation',
    ],
    risks: [
      'Sector rotation risk if rates rise unexpectedly',
      'Macro headwinds on enterprise capex spending',
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
    horizon: '180d',
    discoveredBy: 'strigoi-lazarus',
    discoveredAt: ago(10),
  },
]
