import type { Pattern } from '../api/types'

export const mockPatterns: Pattern[] = [
  {
    id: 'pattern-1',
    appliesToStrigoi: 'strigoi-spin',
    statement:
      'Spin-offs from technology sector parents (SIC codes 7370–7379) significantly outperform spin-offs from industrial parents within the first 90 days post-separation. Strigoi-Spin should weight technology spin-offs +0.15 in confidence calculation.',
    status: 'PENDING',
    evidenceCount: 12,
    proposedAt: new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString(),
  },
]
