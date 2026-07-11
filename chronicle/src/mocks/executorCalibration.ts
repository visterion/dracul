import type { ExecutorCalibration, ExecutorBehavior } from '../api/types'

export const mockExecutorCalibration: ExecutorCalibration = {
  executor: {
    brier: 0.18, n: 42, insufficient: false,
    buckets: [
      { range: '0.6-0.7', n: 10, predicted: 0.65, observed: 0.5 },
      { range: '0.7-0.8', n: 18, predicted: 0.74, observed: 0.72 },
    ],
  },
  hunters: [
    { agent: 'strigoi-echo', brier: 0.21, n: 34, insufficient: false, buckets: [] },
    { agent: 'strigoi-lazarus', brier: 0.31, n: 8, insufficient: true, buckets: [] },
  ],
}

export const mockExecutorBehavior: ExecutorBehavior = {
  veto_precision: [
    {
      reason_code: 'PACE_LIMIT', n: 12, skipped: 3,
      mean_hypothetical_r_20d: 0.4, mean_hypothetical_r_60d: 1.1, stopped_out_pct: 25.0,
    },
    {
      reason_code: 'LOW_CONFIDENCE', n: 6, skipped: 0,
      mean_hypothetical_r_20d: -0.2, mean_hypothetical_r_60d: -0.1, stopped_out_pct: 50.0,
    },
  ],
  caveats: [
    'counterfactuals assume reference-price fills (optimistic)',
    'PACE_LIMIT/BUDGET rejects are opportunity-cost questions',
    'reason_code is the first failed check; stats are conditional on earlier checks passing',
  ],
  hard_exit_latency: { n: 5, max_seconds: 3, p95_seconds: 2 },
  whipsaw: { reentry_within_10d: 0, roundtrip_under_5d: 1 },
  stop_basis: [
    { basis: 'ATR', n: 8, mean_realized_r: 0.9, mean_mae_r: -0.5 },
    { basis: 'SWING_LOW', n: 4, mean_realized_r: 1.3, mean_mae_r: -0.3 },
  ],
  slippage: { n: 12, mean: -0.02, worst: -0.15 },
}
