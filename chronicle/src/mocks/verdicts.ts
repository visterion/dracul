import type { Verdict } from '../api/types'

export const mockVerdicts: Verdict[] = [
  {
    id: 'verdict-1',
    symbol: 'AVGO',
    companyName: 'Broadcom Inc',
    contributingStrigoi: ['strigoi-spin', 'strigoi-insider', 'strigoi-echo'],
    consensusScore: 0.84,
    summary:
      "Broadcom's recent spin-off of its VMware infrastructure segment has created exactly the structural anomaly that institutional rebalancing rules tend to mishandle. Two index funds with mid-cap mandates were forced to liquidate post-spin positions within the first week, compressing the new entity below intrinsic asset value. Simultaneously, three senior executives purchased significant blocks of stock in the two weeks following separation — a clustered insider signal that has historically preceded sustained appreciation. A Q3 earnings beat layered on top creates a triple-confirmed setup rarely seen in a single instrument.",
    createdAt: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
  },
]
