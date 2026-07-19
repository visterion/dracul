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

  it('filters the displayed runs client-side without re-fetching when the filter changes', async () => {
    const w = mountView()
    await flushPromises()
    const callCountBeforeFilter = getInspectorRunsSpy.mock.calls.length

    const select = w.find('[data-testid="inspector-agent-filter"]')
    await select.setValue('voievod')
    await flushPromises()

    // No new upstream fetch — the unfiltered runs already loaded are re-filtered for display.
    expect(getInspectorRunsSpy.mock.calls.length).toBe(callCountBeforeFilter)
    const rows = w.findAll('[data-testid="inspector-run"]')
    expect(rows.length).toBe(1)
    expect(rows[0].text()).toContain('voievod')
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

  // Regression test for I-2 (whole-branch review): with an active agent
  // filter, "load more" must paginate over the UNFILTERED upstream list
  // (fetchOffset = count of all runs fetched so far), never over the
  // filtered/displayed count. Fetching with `agent=null` at the wrong
  // offset previously produced duplicate runIds and skipped runs once a
  // filter was selected.
  it('paginates over the unfiltered upstream list when a filter is active, avoiding duplicate runIds', async () => {
    // First upstream page mixes agents: 2 x strigoi-echo, 1 x voievod.
    getInspectorRunsImpl = async (_agent, _limit, offset) => {
      if (!offset) {
        return {
          runs: [
            run({ runId: 'run-1', agent: 'strigoi-echo' }),
            run({ runId: 'run-2', agent: 'voievod' }),
            run({ runId: 'run-3', agent: 'strigoi-echo' }),
          ],
        }
      }
      // Second upstream page — only reached if fetchOffset is the correct
      // unfiltered count (3), not the filtered count (2, for strigoi-echo).
      if (offset === 3) {
        return { runs: [run({ runId: 'run-4', agent: 'strigoi-echo' })] }
      }
      throw new Error(`unexpected offset ${offset}`)
    }
    const w = mountView()
    await flushPromises()

    const select = w.find('[data-testid="inspector-agent-filter"]')
    await select.setValue('strigoi-echo')
    await flushPromises()

    // Filtered display shows only the 2 strigoi-echo runs from page 1.
    expect(w.findAll('[data-testid="inspector-run"]').length).toBe(2)

    const more = w.find('[data-testid="inspector-load-more"]')
    await more.trigger('click')
    await flushPromises()

    // The upstream fetch for "load more" is always agent=null, and at the
    // UNFILTERED offset (3), never the filtered count (2).
    expect(getInspectorRunsSpy).toHaveBeenLastCalledWith(null, 50, 3)

    // 3 strigoi-echo runs now loaded in total (run-1, run-3, run-4); no
    // duplicate `:key` (verified indirectly: a duplicate runId would
    // collapse under Vue's `:key`-based reconciliation and this count
    // would not reach 3, or the wrong upstream page would have been
    // requested — asserted above via the offset=3 call).
    const rows = w.findAll('[data-testid="inspector-run"]')
    expect(rows.length).toBe(3)
  })
})
