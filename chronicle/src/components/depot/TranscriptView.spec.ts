import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import de from '../../i18n/locales/de'
import TranscriptView from './TranscriptView.vue'

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function mountView(transcript: unknown) {
  return mount(TranscriptView, { props: { transcript }, global: { plugins: [i18n] } })
}

describe('TranscriptView', () => {
  it('renders a structured transcript: tool name/output, LLM answer text, and final_output', () => {
    const transcript = {
      run_id: 'run-1',
      agent: 'index-strigoi',
      status: 'COMPLETED',
      model: 'claude-sonnet',
      turn_count: 1,
      started_at: '2026-07-19T10:00:00Z',
      finished_at: '2026-07-19T10:00:05Z',
      turns: [
        {
          index: 0,
          llm_input_messages: [{ role: 'user', content: 'go fetch data' }],
          text: 'Here is my analysis of the situation.',
          stop_reason: 'end_turn',
          tool_calls: [
            { tool_use_id: 't1', name: 'fetch_prices', input: { symbol: 'AAPL' }, output: 'price: 123', is_error: false },
          ],
          tokens: { input: 10, output: 20 },
        },
      ],
      final_output: { verdict: 'BUY' },
    }
    const w = mountView(transcript)

    expect(w.text()).toContain('fetch_prices')
    expect(w.text()).toContain('price: 123')
    expect(w.text()).toContain('Here is my analysis of the situation.')
    expect(w.text()).toContain(de.depots.transcript.result)
    expect(w.text()).toContain('BUY')
    // prompt is collapsed by default
    expect(w.text()).not.toContain('go fetch data')
    expect(w.text()).toContain(de.depots.transcript.showPrompt)
  })

  it('falls back to raw JSON pre for an unknown transcript shape', () => {
    const w = mountView({ foo: 1 })
    const pre = w.find('pre')
    expect(pre.exists()).toBe(true)
    expect(pre.text()).toContain('"foo"')
    expect(pre.text()).toContain('1')
  })

  it('marks a failing tool call as an error', () => {
    const transcript = {
      turns: [
        {
          index: 0,
          tool_calls: [
            { name: 'broken_tool', input: {}, output: 'boom', is_error: true, error_detail: 'timeout' },
          ],
        },
      ],
    }
    const w = mountView(transcript)
    expect(w.find('[data-testid="tool-call-error"]').exists()).toBe(true)
    expect(w.text()).toContain('broken_tool')
  })

  it('handles null/undefined transcript without crashing', () => {
    const w = mountView(null)
    expect(w.find('pre').exists()).toBe(true)
  })
})
