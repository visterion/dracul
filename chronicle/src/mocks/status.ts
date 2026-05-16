import type { SystemStatus } from '../api/types'

export const mockSystemStatus: SystemStatus = {
  strigoi: [
    { name: 'strigoi-spin', state: 'hunting', lastRunAt: new Date(Date.now() - 22 * 60 * 1000).toISOString() },
    { name: 'strigoi-insider', state: 'resting', nextRunAt: '22:00' },
    { name: 'strigoi-echo', state: 'hunting', lastRunAt: new Date(Date.now() - 3 * 60 * 1000).toISOString() },
    { name: 'strigoi-lazarus', state: 'paused' },
    { name: 'strigoi-index', state: 'resting', nextRunAt: 'tomorrow' },
    { name: 'strigoi-merger', state: 'budget-hit' },
  ],
  lastVerdictAt: new Date(Date.now() - 12 * 60 * 1000).toISOString(),
  dailyCostUsd: 0.43,
  daywalkerActive: true,
}
