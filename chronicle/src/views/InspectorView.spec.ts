import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import de from '../i18n/locales/de'
import type { InspectorRun, InspectorRunsResponse, RunTranscript } from '../api/types'
import InspectorView from './InspectorView.vue'

function run(overrides: Partial<InspectorRun> = {}): InspectorRun {
  return {
    runId: 'run-1',
    agent: 'strigoi-echo',
    status: 'completed',
    hasError: false,
    startedAt: '2026-07-18T08:00:00Z',
    snippet: 'looked at NVDA earnings drift',
    ...overrides,
  }
}

let getInspectorRunsImpl: (agent: string | null, limit?: number, offset?: number) => Promise<InspectorRunsResponse> =
  async () => ({ runs: [run(), run({ runId: 'run-2', agent: 'voievod', hasError: true })] })
let getInspectorTranscriptImpl: (runId: string) => Promise<RunTranscript> =
  async runId => ({ transcript: { runId, note: 'mock transcript' }, expired: false })

const getInspectorRunsSpy = vi.fn((agent: string | null, limit?: number, offset?: number) => getInspectorRunsImpl(agent, limit, offset))
const getInspectorTranscriptSpy = vi.fn((runId: string) => getInspectorTranscriptImpl(runId))

vi.mock('../api', () => ({
  useApi: () => ({
    getInspectorRuns: getInspectorRunsSpy,
    getInspectorTranscript: getInspectorTranscriptSpy,
  }),
}))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function mountView() {
  return mount(InspectorView, { global: { plugins: [i18n] } })
}

beforeEach(() => {
  getInspectorRunsImpl = async () => ({ runs: [run(), run({ runId: 'run-2', agent: 'voievod', hasError: true })] })
  getInspectorTranscriptImpl = async runId => ({ transcript: { runId, note: 'mock transcript' }, expired: false })
  getInspectorRunsSpy.mockClear()
  getInspectorTranscriptSpy.mockClear()
})

describe('InspectorView', () => {
  it('loads runs for "all agents" on mount and renders both rows', async () => {
    const w = mountView()
    await flushPromises()

    expect(getInspectorRunsSpy).toHaveBeenCalledWith(null, 50, 0)
    const rows = w.findAll('[data-testid="inspector-run"]')
    expect(rows.length).toBe(2)
    expect(rows[0].text()).toContain('strigoi-echo')
    expect(rows[0].text()).toContain('looked at NVDA earnings drift')
    expect(rows[1].text()).toContain('voievod')
  })

  it('marks runs with hasError', async () => {
    const w = mountView()
    await flushPromises()

    const rows = w.findAll('[data-testid="inspector-run"]')
    expect(rows[0].find('[data-testid="inspector-run-error"]').exists()).toBe(false)
    expect(rows[1].find('[data-testid="inspector-run-error"]').exists()).toBe(true)
  })

  it('re-fetches with the selected agent when the filter changes', async () => {
    const w = mountView()
    await flushPromises()

    const select = w.find('[data-testid="inspector-agent-filter"]')
    await select.setValue('voievod')
    await flushPromises()

    expect(getInspectorRunsSpy).toHaveBeenLastCalledWith('voievod', 50, 0)
  })

  it('expands the raw transcript panel when a run row is clicked', async () => {
    const w = mountView()
    await flushPromises()

    const rows = w.findAll('[data-testid="inspector-run"]')
    expect(w.findComponent({ name: 'RawTranscriptPanel' }).exists()).toBe(false)

    await rows[0].trigger('click')
    await flushPromises()

    const panel = w.find('[data-testid="transcript-panel"]')
    expect(panel.exists()).toBe(true)
  })

  it('loads more runs with an increased offset and appends them', async () => {
    getInspectorRunsImpl = async (_agent, _limit, offset) => {
      if (!offset) return { runs: [run(), run({ runId: 'run-2', agent: 'voievod', hasError: true })] }
      return { runs: [run({ runId: 'run-3', agent: 'gropar' })] }
    }
    const w = mountView()
    await flushPromises()

    const more = w.find('[data-testid="inspector-load-more"]')
    expect(more.exists()).toBe(true)
    await more.trigger('click')
    await flushPromises()

    expect(getInspectorRunsSpy).toHaveBeenLastCalledWith(null, 50, 2)
    const rows = w.findAll('[data-testid="inspector-run"]')
    expect(rows.length).toBe(3)
  })
})
