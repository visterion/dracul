import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ProposalsView from './ProposalsView.vue'
import de from '../i18n/locales/de'
import type { ProposalRun } from '../api/types'

/** Minimal fake standing in for the browser's EventSource, so the view's SSE
 *  subscription (pattern from stores/liveAlerts.ts) can be exercised without a
 *  real network connection. Tests reach into `FakeEventSource.instances` to
 *  dispatch a `proposal.new` event at the listener the view registered. */
class FakeEventSource {
  static instances: FakeEventSource[] = []
  private listeners: Record<string, ((e: MessageEvent) => void)[]> = {}

  constructor(public url: string) {
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, cb: (e: MessageEvent) => void) {
    (this.listeners[type] ??= []).push(cb)
  }

  close() {}

  dispatch(type: string, data: unknown) {
    for (const cb of this.listeners[type] ?? []) {
      cb({ data: JSON.stringify(data) } as MessageEvent)
    }
  }
}

const runA: ProposalRun = {
  runId: 'run-2',
  createdAt: '2026-08-08T22:00:00Z',
  marketNote: 'Ruhiger Handelstag.',
  proposals: [
    {
      id: 'p-1', symbol: 'AVGO', action: 'add', entryZone: '265-270', stop: '245',
      confidence: 0.72, rationale: 'Nachkauf im Rücksetzer.',
      newsSentiment: [{ headline: 'Broadcom übertrifft Erwartungen', sentiment: 'positive' }],
    },
    {
      id: 'p-2', symbol: 'PYPL', action: 'hold', entryZone: '', stop: '',
      confidence: 0.4, rationale: 'These weiterhin intakt, kein neuer Trigger.',
      newsSentiment: null,
    },
  ],
}

const runB: ProposalRun = {
  runId: 'run-1',
  createdAt: '2026-08-07T22:00:00Z',
  marketNote: 'Leichte Schwäche im Halbleitersektor.',
  proposals: [
    {
      id: 'p-3', symbol: 'NVDA', action: 'trim', entryZone: '', stop: '118',
      confidence: 0.58, rationale: 'Gewinnmitnahme nach starkem Lauf.',
      newsSentiment: [{ headline: 'Sektor-Rotation aus Halbleitern', sentiment: 'negative' }],
    },
  ],
}

const mockGetProposals = vi.fn(async () => [runA, runB])

vi.mock('../api', () => ({
  useApi: () => ({
    getProposals: mockGetProposals,
  }),
}))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function mountView() {
  return mount(ProposalsView, {
    global: { plugins: [i18n] },
  })
}

describe('ProposalsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockGetProposals.mockClear()
    mockGetProposals.mockResolvedValue([runA, runB])
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('groups proposals by run, newest run first', async () => {
    const w = mountView()
    await flushPromises()

    const runSections = w.findAll('[data-testid^="proposal-run-"]')
    expect(runSections).toHaveLength(2)
    expect(runSections[0].attributes('data-testid')).toBe('proposal-run-run-2')
    expect(runSections[1].attributes('data-testid')).toBe('proposal-run-run-1')
    expect(runSections[0].text()).toContain('Ruhiger Handelstag.')
  })

  it('visually separates action items from warn-holds', async () => {
    const w = mountView()
    await flushPromises()

    const firstRun = w.get('[data-testid="proposal-run-run-2"]')
    const actionItems = firstRun.get('[data-testid="proposal-action-items"]')
    const warnHolds = firstRun.get('[data-testid="proposal-warn-holds"]')

    expect(actionItems.text()).toContain('AVGO')
    expect(actionItems.text()).not.toContain('PYPL')
    expect(warnHolds.text()).toContain('PYPL')
    expect(warnHolds.text()).not.toContain('AVGO')

    // run-1 has no hold action at all — the warn-hold section must not render.
    const secondRun = w.get('[data-testid="proposal-run-run-1"]')
    expect(secondRun.find('[data-testid="proposal-warn-holds"]').exists()).toBe(false)
  })

  it('renders news sentiment at the headline entry', async () => {
    const w = mountView()
    await flushPromises()

    const news = w.findAll('[data-testid="proposal-news"]')
    expect(news.length).toBeGreaterThan(0)
    expect(news[0].text()).toContain('Broadcom übertrifft Erwartungen')
  })

  it('appends a run when a proposal.new SSE event arrives', async () => {
    const w = mountView()
    await flushPromises()

    expect(w.findAll('[data-testid^="proposal-run-"]')).toHaveLength(2)

    const runC: ProposalRun = {
      runId: 'run-3',
      createdAt: '2026-08-09T22:00:00Z',
      marketNote: 'Neuer Lauf via SSE.',
      proposals: [
        { id: 'p-4', symbol: 'TSLA', action: 'buy', entryZone: '200-205', stop: '190',
          confidence: 0.65, rationale: 'Neuer Vorschlag.', newsSentiment: null },
      ],
    }
    mockGetProposals.mockResolvedValueOnce([runC, runA, runB])

    expect(FakeEventSource.instances).toHaveLength(1)
    FakeEventSource.instances[0].dispatch('proposal.new', { count: 1, run_id: 'run-3', ts: '2026-08-09T22:00:01Z' })
    await flushPromises()

    const runSections = w.findAll('[data-testid^="proposal-run-"]')
    expect(runSections).toHaveLength(3)
    expect(runSections[0].attributes('data-testid')).toBe('proposal-run-run-3')
  })

  it('shows an empty state that names the reason instead of implying an error', async () => {
    mockGetProposals.mockResolvedValue([])
    const w = mountView()
    await flushPromises()

    const empty = w.get('[data-testid="proposals-empty"]')
    expect(empty.text()).toContain('Renfield')
    expect(w.find('[data-testid^="proposal-run-"]').exists()).toBe(false)
  })
})
