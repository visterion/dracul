import type { BenchmarkSignalStats, BenchmarkSummary } from '../api/types'

const EMPTY: BenchmarkSignalStats = {
  n: 0, weeks: 0, mean: null, median: null, hitRate: null, clusteredMean: null, ciLow: null, ciHigh: null,
}

/** Synthetic benchmark summary for VITE_MOCK mode — invented numbers, no production data. */
export const mockBenchmarkSummary: BenchmarkSummary = {
  benchmark: 'EUNL.DE',
  decisionDate: '2027-04-13',
  minEvents: 30,
  minWeeks: 5,
  agents: [
    {
      agent: 'strigoi-echo',
      coverageStart: '2026-06-15',
      signal20: { n: 64, weeks: 14, mean: -0.006, median: -0.004, hitRate: 0.45, clusteredMean: -0.005, ciLow: -0.021, ciHigh: 0.011 },
      signal60: { n: 51, weeks: 12, mean: -0.012, median: -0.01, hitRate: 0.43, clusteredMean: -0.011, ciLow: -0.04, ciHigh: 0.018 },
      paired20: { pickedN: 64, pickedMean: -0.006, pickedWeeks: 14, rejectedN: 170, rejectedMean: -0.003, rejectedWeeks: 16, difference: -0.003, ciLow: -0.025, ciHigh: 0.019 },
      paired60: { pickedN: 51, pickedMean: -0.012, pickedWeeks: 12, rejectedN: 140, rejectedMean: -0.008, rejectedWeeks: 15, difference: -0.004, ciLow: -0.04, ciHigh: 0.032 },
      status: 'GREY',
    },
    {
      agent: 'strigoi-insider',
      coverageStart: '2026-06-20',
      signal20: { n: 9, weeks: 6, mean: 0.004, median: 0.002, hitRate: 0.56, clusteredMean: 0.004, ciLow: -0.03, ciHigh: 0.038 },
      signal60: EMPTY,
      paired20: null,
      paired60: null,
      status: 'INSUFFICIENT',
    },
    {
      agent: 'strigoi-spin',
      coverageStart: null,
      signal20: { n: 33, weeks: 9, mean: 0.021, median: 0.018, hitRate: 0.61, clusteredMean: 0.02, ciLow: 0.004, ciHigh: 0.036 },
      signal60: { n: 31, weeks: 8, mean: 0.044, median: 0.04, hitRate: 0.65, clusteredMean: 0.042, ciLow: 0.012, ciHigh: 0.072 },
      paired20: null,
      paired60: null,
      status: 'GREEN',
    },
  ],
  depot: {
    connection: 'depot-1',
    from: '2026-07-01',
    to: '2026-10-09',
    twr: -0.031,
    benchmarkReturn: 0.015,
    excess: -0.046,
    byStrigoi: [
      { strigoi: 'strigoi-echo', positions: 6, openPositions: 1, capitalEur: 4200, returnEur: -0.052, benchmarkReturn: 0.008, excess: -0.06, skipped: 0 },
      { strigoi: 'strigoi-spin', positions: 2, openPositions: 2, capitalEur: 1800, returnEur: 0.034, benchmarkReturn: 0.011, excess: 0.023, skipped: 0 },
    ],
  },
}
