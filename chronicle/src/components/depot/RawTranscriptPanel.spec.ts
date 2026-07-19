import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import de from '../../i18n/locales/de'
import RawTranscriptPanel from './RawTranscriptPanel.vue'

const mockGetRunTranscript = vi.fn()
const mockGetInspectorTranscript = vi.fn()
vi.mock('../../api', () => ({
  useApi: () => ({
    getRunTranscript: mockGetRunTranscript,
    getInspectorTranscript: mockGetInspectorTranscript,
  }),
}))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function mountPanel(props: { runId: string; source?: 'depot' | 'inspector' }) {
  return mount(RawTranscriptPanel, { props, global: { plugins: [i18n] } })
}

describe('RawTranscriptPanel source prop', () => {
  beforeEach(() => {
    mockGetRunTranscript.mockReset()
    mockGetInspectorTranscript.mockReset()
  })

  it('defaults to the depot transcript endpoint when source is omitted', async () => {
    mockGetRunTranscript.mockResolvedValue({ transcript: { foo: 'bar' }, expired: false })
    const w = mountPanel({ runId: 'run-1' })
    await w.find('[data-testid="transcript-toggle"]').trigger('click')
    await flushPromises()

    expect(mockGetRunTranscript).toHaveBeenCalledWith('run-1')
    expect(mockGetInspectorTranscript).not.toHaveBeenCalled()
  })

  it('uses the inspector endpoint when source="inspector"', async () => {
    mockGetInspectorTranscript.mockResolvedValue({ transcript: { foo: 'bar' }, expired: false })
    const w = mountPanel({ runId: 'run-2', source: 'inspector' })
    await w.find('[data-testid="transcript-toggle"]').trigger('click')
    await flushPromises()

    expect(mockGetInspectorTranscript).toHaveBeenCalledWith('run-2')
    expect(mockGetRunTranscript).not.toHaveBeenCalled()
  })

  it('shows the expired hint for an expired inspector transcript', async () => {
    mockGetInspectorTranscript.mockResolvedValue({ transcript: null, expired: true })
    const w = mountPanel({ runId: 'run-3', source: 'inspector' })
    await w.find('[data-testid="transcript-toggle"]').trigger('click')
    await flushPromises()

    expect(w.find('[data-testid="transcript-expired"]').exists()).toBe(true)
    expect(w.text()).toContain(de.depots.transcript.expired)
  })
})
