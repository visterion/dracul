import type { BenchmarkExcludedCounts, BenchmarkExclusions, BenchmarkSignalStats, BenchmarkSummary } from '../api/types'

const EMPTY: BenchmarkSignalStats = {
  n: 0, weeks: 0, mean: null, median: null, hitRate: null, clusteredMean: null, ciLow: null, ciHigh: null,
}

const NONE: BenchmarkExcludedCounts = { noEntry: 0, noData: 0, unknownCurrency: 0, total: 0 }
const NOTHING_EXCLUDED: BenchmarkExclusions = { signal: NONE, picked: NONE, rejected: NONE, total: 0 }

/** Synthetic benchmark summary for VITE_MOCK mode — invented numbers, no production data. */
export const mockBenchmarkSummary: BenchmarkSummary = {
  benchmark: 'EUNL.DE',
  decisionDate: '2027-04-13',
  minEvents: 30,
  minWeeks: 24,
  agents: [
    {
      agent: 'strigoi-echo',
      coverageStart: '2026-06-15',
      signal20: { n: 64, weeks: 14, mean: -0.006, median: -0.004, hitRate: 0.45, clusteredMean: -0.005, ciLow: -0.021, ciHigh: 0.011 },
      signal60: { n: 51, weeks: 15, mean: -0.012, median: -0.01, hitRate: 0.43, clusteredMean: -0.011, ciLow: null, ciHigh: null },
      paired20: { pickedN: 64, pickedMean: -0.006, pickedWeeks: 14, rejectedN: 170, rejectedMean: -0.003, rejectedWeeks: 16, difference: -0.003, ciLow: -0.025, ciHigh: 0.019 },
      paired60: { pickedN: 51, pickedMean: -0.012, pickedWeeks: 12, rejectedN: 140, rejectedMean: -0.008, rejectedWeeks: 15, difference: -0.004, ciLow: null, ciHigh: null },
      status: 'INSUFFICIENT',
      excluded: {
        signal: { noEntry: 2, noData: 0, unknownCurrency: 1, total: 3 },
        picked: { noEntry: 1, noData: 0, unknownCurrency: 0, total: 1 },
        rejected: { noEntry: 3, noData: 4, unknownCurrency: 2, total: 9 },
        total: 13,
      },
    },
    {
      agent: 'strigoi-insider',
      coverageStart: '2026-06-20',
      signal20: { n: 9, weeks: 6, mean: 0.004, median: 0.002, hitRate: 0.56, clusteredMean: 0.004, ciLow: null, ciHigh: null },
      signal60: EMPTY,
      paired20: null,
      paired60: null,
      status: 'INSUFFICIENT',
      excluded: NOTHING_EXCLUDED,
    },
    {
      agent: 'strigoi-spin',
      coverageStart: '2026-06-25',
      signal20: { n: 45, weeks: 27, mean: 0.021, median: 0.018, hitRate: 0.61, clusteredMean: 0.02, ciLow: 0.004, ciHigh: 0.036 },
      signal60: { n: 41, weeks: 26, mean: 0.044, median: 0.04, hitRate: 0.65, clusteredMean: 0.042, ciLow: 0.012, ciHigh: 0.072 },
      paired20: null,
      paired60: null,
      status: 'GREEN',
      excluded: {
        signal: { noEntry: 1, noData: 0, unknownCurrency: 0, total: 1 },
        picked: NONE,
        rejected: NONE,
        total: 1,
      },
    },
    {
      agent: 'strigoi-tech',
      coverageStart: null,
      signal20: { n: 40, weeks: 27, mean: 0.003, median: 0.001, hitRate: 0.5, clusteredMean: 0.002, ciLow: -0.013, ciHigh: 0.017 },
      signal60: { n: 34, weeks: 25, mean: 0.006, median: 0.004, hitRate: 0.52, clusteredMean: 0.005, ciLow: -0.018, ciHigh: 0.028 },
      paired20: null,
      paired60: null,
      status: 'GREY',
      excluded: NOTHING_EXCLUDED,
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
