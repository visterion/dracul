import type { VerdictDetail } from '../api/types'

export const mockVerdictDetails: VerdictDetail[] = [
  {
    id: 'verdict-1',
    symbol: 'AVGO',
    companyName: 'Broadcom Inc',
    contributingStrigoi: ['strigoi-spin', 'strigoi-insider', 'strigoi-echo'],
    consensusScore: 0.84,
    anomalyTypes: ['SPIN', 'INSIDER', 'PEAD'],
    currentPrice: 1147.70,
    currency: 'EUR',
    nativeCurrentPrice: 1247.50,
    nativeCurrency: 'USD',
    avgConfidence: 0.78,
    horizon: '90d',
    createdAt: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
    summary:
      "Broadcom's recent spin-off of its VMware infrastructure segment has created exactly the structural anomaly that institutional rebalancing rules tend to mishandle. Two index funds with mid-cap mandates were forced to liquidate post-spin positions within the first week, compressing the new entity below its intrinsic asset value. Simultaneously, three senior executives — the CFO, CTO, and two independent board members — purchased significant blocks of stock in the two weeks following separation. Such clustered insider buying has historically preceded sustained appreciation periods of 90 to 180 days. The Q3 earnings beat published last week adds a PEAD signal on top of these structural factors, creating a triple-confirmed setup rarely seen in a single instrument. Historical parallels from 2018–2023 suggest 60–90 day continued momentum, particularly given the sector's current strength.",
    signals: [
      'Spin-off forced selling pressure from two index funds with mid-cap mandates',
      'Insider cluster: CFO + CTO + 2 independent board members, $4.2M total in two weeks post-separation',
      'PEAD: Q3 EPS +14% YoY, guidance raised for Q4',
      'Price compressed ~18% below EBITDA-implied intrinsic value',
      'Sector tailwind: semiconductor infrastructure strength continuing into H2',
    ],
    risks: [
      'Macro headwinds could dampen sector rotation and extend recovery timeline',
      'Possible additional forced selling at quarter-end rebalancing from other mandate-constrained funds',
      'Insider purchases concentrated among 4 individuals — not yet broad-based employee buying',
      'Integration risk from VMware acquisition remains partially unresolved in Street models',
    ],
    contributingDetails: [
      {
        name: 'strigoi-spin',
        confidence: 0.81,
        thesis:
          'Spin-off completion combined with mandatory index-fund liquidation has created a classic forced-seller / patient-buyer asymmetry. The two funds required to exit had a combined position of approximately $340M. At current daily volume, their liquidation window closes within 8–12 trading days, after which the structural supply overhang dissipates.',
      },
      {
        name: 'strigoi-insider',
        confidence: 0.79,
        thesis:
          'CFO-led cluster buying on the same week as spin separation is a high-conviction signal. CFO purchases have 23% higher follow-through rates than board-only clusters in the training set. The $1.8M CFO purchase — at a price 15% below the pre-announcement level — suggests strong conviction about intrinsic value.',
      },
      {
        name: 'strigoi-echo',
        confidence: 0.74,
        thesis:
          'Q3 beat of 14% YoY on EPS with raised Q4 guidance triggers a PEAD signal. In semiconductor infrastructure names, post-earnings drift of this magnitude continues for 40–70 trading days with 68% historical frequency. Current options flow shows unusual call accumulation consistent with institutional position-building.',
      },
    ],
  },
]
