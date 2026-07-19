import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import de from '../i18n/locales/de'
import CalibrationCard from './CalibrationCard.vue'
import type { ExecutorCalibration, ExecutorBehavior } from '../api/types'

const calibration: ExecutorCalibration = {
  executor: { insufficient: false, n: 42, brier: 0.123, buckets: [] },
  hunters: [],
}

const behavior: ExecutorBehavior = {
  veto_precision: [
    { reason_code: 'BROKER_ERROR', n: 3, skipped: 1, mean_hypothetical_r_20d: 0.1, mean_hypothetical_r_60d: 0.2, stopped_out_pct: 10 },
    { reason_code: 'NO_STOP', n: 2, skipped: 0, mean_hypothetical_r_20d: 0.1, mean_hypothetical_r_60d: 0.2, stopped_out_pct: 5 },
    { reason_code: 'SOME_OTHER_REASON', n: 1, skipped: 0, mean_hypothetical_r_20d: 0.1, mean_hypothetical_r_60d: 0.2, stopped_out_pct: 0 },
  ],
  caveats: [],
  hard_exit_latency: { n: 1, max_seconds: 3, p95_seconds: 2 },
  whipsaw: { reentry_within_10d: 0, roundtrip_under_5d: 0 },
  stop_basis: [],
  slippage: { n: 1, mean: 0.1, worst: 0.2 },
}

const mockGetExecutorCalibration = vi.fn(async () => calibration)
const mockGetExecutorBehavior = vi.fn(async () => behavior)

vi.mock('../api', () => ({
  useApi: () => ({
    getExecutorCalibration: mockGetExecutorCalibration,
    getExecutorBehavior: mockGetExecutorBehavior,
  }),
}))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function mountCalibrationCard() {
  return mount(CalibrationCard, { global: { plugins: [i18n] } })
}

describe('CalibrationCard explainer dots', () => {
  beforeEach(() => {
    mockGetExecutorCalibration.mockClear()
    mockGetExecutorBehavior.mockClear()
  })

  it('shows info-dot explainers for the calibration concepts', async () => {
    const w = mountCalibrationCard()
    await flushPromises()
    expect(w.findAll('.info-dot').length).toBeGreaterThanOrEqual(1)
  })
})
