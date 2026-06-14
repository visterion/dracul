import type { ExitSignal } from '../api/types'

export const mockExitSignals: ExitSignal[] = [
  {
    id: 'es-1', watchlistItemId: 'wl-1', symbol: 'AVGO', action: 'TRIM',
    firedRules: ['PROFIT_TARGET'], gainLossPct: 4.8, thesisStatus: 'INTACT',
    rationale: 'Position liegt deutlich im Plus; ein Teilverkauf sichert Gewinne, die These bleibt intakt.',
    confidence: 0.62, vistierieRunId: 'run-mock-1', runAt: '2026-06-14T22:00:00Z',
  },
  {
    id: 'es-2', watchlistItemId: 'wl-2', symbol: 'NVDA', action: 'SELL',
    firedRules: ['CHANDELIER_STOP', 'DEATH_CROSS'], gainLossPct: -1.9, thesisStatus: 'WEAKENING',
    rationale: 'Chandelier-Stop gerissen und MA50 unter MA200 — das technische Bild kippt, Ausstieg empfohlen.',
    confidence: 0.78, vistierieRunId: 'run-mock-1', runAt: '2026-06-14T22:00:00Z',
  },
]
