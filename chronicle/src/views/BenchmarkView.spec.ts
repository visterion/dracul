import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import BenchmarkView from './BenchmarkView.vue'
import de from '../i18n/locales/de'
import type { BenchmarkExcludedCounts, BenchmarkExclusions, BenchmarkSignalStats, BenchmarkSummary } from '../api/types'

const EMPTY: BenchmarkSignalStats = {
  n: 0, weeks: 0, mean: null, median: null, hitRate: null, clusteredMean: null, ciLow: null, ciHigh: null,
}

const NONE: BenchmarkExcludedCounts = { noEntry: 0, noData: 0, unknownCurrency: 0, total: 0 }
const NOTHING_EXCLUDED: BenchmarkExclusions = { signal: NONE, picked: NONE, rejected: NONE, total: 0 }

const summary: BenchmarkSummary = {
  benchmark: 'EUNL.DE',
  decisionDate: '2027-04-13',
  minEvents: 30,
  minWeeks: 24,
  agents: [
    {
      agent: 'strigoi-syna',
      coverageStart: '2026-06-15',
      signal20: { n: 40, weeks: 8, mean: 0.012, median: 0.01, hitRate: 0.55, clusteredMean: 0.011, ciLow: 0.002, ciHigh: 0.02 },
      signal60: { n: 35, weeks: 25, mean: 0.031, median: 0.03, hitRate: 0.6, clusteredMean: 0.03, ciLow: 0.01, ciHigh: 0.05 },
      paired20: { pickedN: 12, pickedMean: 0.02, pickedWeeks: 6, rejectedN: 30, rejectedMean: 0.0, rejectedWeeks: 8, difference: 0.018, ciLow: -0.01, ciHigh: 0.046 },
      paired60: null,
      status: 'GREEN',
      excluded: {
        signal: { noEntry: 2, noData: 1, unknownCurrency: 0, total: 3 },
        picked: NONE,
        rejected: { noEntry: 0, noData: 0, unknownCurrency: 4, total: 4 },
        total: 7,
      },
    },
    {
      agent: 'strigoi-synb', coverageStart: null, signal20: EMPTY, signal60: EMPTY,
      paired20: null, paired60: null, status: 'INSUFFICIENT', excluded: NOTHING_EXCLUDED,
    },
  ],
  depot: {
    connection: 'depot-1', from: '2026-07-01', to: '2026-10-09', twr: -0.031, benchmarkReturn: 0.015, excess: -0.046,
    byStrigoi: [{ strigoi: 'strigoi-syna', positions: 3, openPositions: 1, capitalEur: 3000, returnEur: -0.04, benchmarkReturn: 0.01, excess: -0.05, skipped: 0 }],
  },
}

const mockGet = vi.fn(async (): Promise<BenchmarkSummary | null> => summary)

vi.mock('../api', () => ({
  useApi: () => ({ getBenchmarkSummary: mockGet }),
}))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function mountView() {
  return mount(BenchmarkView, { global: { plugins: [i18n] } })
}

describe('BenchmarkView', () => {
  beforeEach(() => {
    mockGet.mockReset()
    mockGet.mockResolvedValue(summary)
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-13T10:00:00Z'))
  })
  afterEach(() => vi.useRealTimers())

  it('counts down to the decision date', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.get('[data-testid="benchmark-countdown"]').text())
      .toBe('Noch 182 Tage bis zur Entscheidung am 13.04.2027')
  })

  it('shows the review rule including the owner sentence and the thresholds', async () => {
    const w = mountView()
    await flushPromises()
    const rules = w.get('[data-testid="benchmark-decision"]').text()
    expect(rules).toContain('behalten')
    expect(rules).toContain('mit genug Daten abschalten')
    expect(rules).toContain('unter 30 Ereignissen oder 24 Wochen')
    expect(rules).toContain('Abgeschaltet wird nie automatisch')
  })

  it('renders one row per strigoi with its status label', async () => {
    const w = mountView()
    await flushPromises()
    const rows = w.findAll('[data-testid="benchmark-row"]')
    expect(rows).toHaveLength(2)
    expect(w.get('[data-testid="benchmark-status-strigoi-syna"]').text()).toBe('Vorsprung')
    expect(w.get('[data-testid="benchmark-status-strigoi-synb"]').text()).toBe('zu wenig Daten')
  })

  it('shows mean, interval, N, weeks and hit rate for a measured signal and a dash otherwise', async () => {
    const w = mountView()
    await flushPromises()
    const a = w.get('[data-agent="strigoi-syna"] [data-col="signal60"]').text()
    // Ruling F6: the headline for a SIGNAL stat is clusteredMean (0.03), not the
    // event-level mean (0.031) — the CI shown beneath it is centred on clusteredMean.
    expect(a).toContain('3,0')
    expect(a).toContain('N 35')
    expect(a).toContain('25 Wo.')
    expect(a).toContain('60 % Treffer')
    expect(w.get('[data-agent="strigoi-synb"] [data-col="signal60"]').text()).toBe('—')
    expect(w.get('[data-agent="strigoi-syna"] [data-col="paired60"]').text()).toBe('—')
    expect(w.get('[data-agent="strigoi-syna"] [data-col="paired20"]').text()).toContain('gewählt 12')
    expect(w.get('[data-agent="strigoi-synb"] [data-col="coverage"]').text()).toBe('—')
  })

  it('renders the depot block and the footnotes', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.get('[data-testid="benchmark-depot-twr"]').text()).toContain('3,1')
    expect(w.findAll('[data-testid="benchmark-depot-row"]')).toHaveLength(1)
    const notes = w.get('[data-testid="benchmark-notes"]').text()
    expect(notes).toContain('Ohne Kosten, Steuern und Dividenden.')
    expect(notes).toContain('seit 2026-10-03 pausiert')
    // Ruling 3: the coverage footnote must say why signal-only strigoi (tech, momentum)
    // have no "Daten seit" — they store no pre-screen candidates.
    expect(notes).toContain('reine Signal-Strigoi (tech, momentum)')
    expect(notes).toContain('keine Vorfilter-Kandidaten')
    // Final review I2: the interval method is named.
    expect(notes).toContain('Newey–West')
    // Final review M5: the timing asymmetry of the paired comparison is stated.
    expect(notes).toContain('ab ihrem ersten Auftauchen')
    expect(notes).toContain('zeitlich nicht exakt deckungsgleich')
  })

  it('shows the excluded count per strigoi with a per-group tooltip, nothing when none', async () => {
    const w = mountView()
    await flushPromises()
    const x = w.get('[data-testid="benchmark-excluded-strigoi-syna"]')
    expect(x.text()).toBe('ausgeschlossen: 7')
    const title = x.attributes('title') ?? ''
    expect(title).toContain('Signal: 3 (ohne Einstieg 2 · keine Daten 1 · Währung 0)')
    expect(title).toContain('verworfen: 4 (ohne Einstieg 0 · keine Daten 0 · Währung 4)')
    expect(w.find('[data-testid="benchmark-excluded-strigoi-synb"]').exists()).toBe(false)
    expect(w.get('[data-testid="benchmark-notes"]').text()).toContain('„ausgeschlossen" = Ereignisse ohne Messwert')
  })

  it('shows an empty depot hint when the depot is not visible', async () => {
    mockGet.mockResolvedValue({ ...summary, depot: null })
    const w = mountView()
    await flushPromises()
    expect(w.find('[data-testid="benchmark-depot-empty"]').exists()).toBe(true)
  })

  it('shows the empty state for a null summary and the error state on failure', async () => {
    mockGet.mockResolvedValue(null)
    const empty = mountView()
    await flushPromises()
    expect(empty.find('[data-testid="benchmark-empty"]').exists()).toBe(true)

    mockGet.mockRejectedValue(new Error('HTTP 503'))
    const failed = mountView()
    await flushPromises()
    expect(failed.find('[data-testid="benchmark-error"]').exists()).toBe(true)
  })
})
