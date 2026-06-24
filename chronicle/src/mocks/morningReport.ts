import type { MorningReport } from '../api/types'

export const mockMorningReport: MorningReport = {
  generatedAt: '2026-06-23T07:00:00Z',
  sellCount: 1,
  trimCount: 1,
  holdCount: 1,
  positions: [
    {
      symbol: 'BBB', companyName: 'Beta Bio', shareCount: 10, entryPrice: 50,
      currentClose: 48, activeStop: 45, nextTarget2r: 70, distanceToStopPct: 6.25,
      targetReached: false,
      action: 'SELL', thesisStatus: 'INVALIDATED', confidence: 0.9,
      rationale: 'These gebrochen — Stop unterschritten.',
      ticket: { side: 'SELL', symbol: 'BBB', shares: 10, limitReference: 48, stop: 45, target: 70 },
    },
    {
      symbol: 'CCC', companyName: 'Gamma Corp', shareCount: 90, entryPrice: 20,
      currentClose: 21, activeStop: 20, nextTarget2r: 30, distanceToStopPct: 4.76,
      targetReached: false,
      action: 'TRIM', thesisStatus: 'WEAKENING', confidence: 0.6,
      rationale: 'Teilgewinn mitnehmen.',
      ticket: { side: 'TRIM', symbol: 'CCC', shares: 30, limitReference: 21, stop: 20, target: 30 },
    },
    {
      symbol: 'AAA', companyName: 'Alpha AG', shareCount: 30, entryPrice: 100,
      currentClose: 95, activeStop: 80, nextTarget2r: 160, distanceToStopPct: 15.79,
      targetReached: true,
      action: 'HOLD', thesisStatus: 'INTACT', confidence: 0.8,
      rationale: 'These intakt, Stop fern.',
      ticket: { side: 'HOLD', symbol: 'AAA', shares: 0, limitReference: 95, stop: 80, target: 160 },
    },
  ],
}
