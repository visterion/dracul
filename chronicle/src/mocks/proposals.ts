import type { ProposalRun } from '../api/types'

export const mockProposalRuns: ProposalRun[] = [
  {
    runId: 'run-mock-renfield-2',
    createdAt: '2026-08-08T22:00:00Z',
    marketNote: 'Ruhiger Handelstag, keine Makro-Überraschungen.',
    proposals: [
      {
        id: 'prop-1', symbol: 'AVGO', action: 'add', entryZone: '265-270', stop: '245',
        confidence: 0.72, rationale: 'Position bestätigt Aufwärtstrend, Nachkauf im Rücksetzer sinnvoll.',
        newsSentiment: [{ headline: 'Broadcom übertrifft Quartalsschätzungen', sentiment: 'positive' }],
      },
      {
        id: 'prop-2', symbol: 'PYPL', action: 'hold', entryZone: '', stop: '',
        confidence: 0.4, rationale: 'These weiterhin intakt, aber kein neuer Trigger — reine Beobachtung.',
        newsSentiment: null,
      },
    ],
  },
  {
    runId: 'run-mock-renfield-1',
    createdAt: '2026-08-07T22:00:00Z',
    marketNote: 'Leichte Schwäche im Halbleitersektor.',
    proposals: [
      {
        id: 'prop-3', symbol: 'NVDA', action: 'trim', entryZone: '', stop: '118',
        confidence: 0.58, rationale: 'Gewinnmitnahme nach starkem Lauf, Stop nachgezogen.',
        newsSentiment: [{ headline: 'Sektor-Rotation aus Halbleitern', sentiment: 'negative' }],
      },
    ],
  },
]
