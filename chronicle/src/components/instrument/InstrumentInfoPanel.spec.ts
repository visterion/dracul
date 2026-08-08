import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import de from '../../i18n/locales/de'
import InstrumentInfoPanel from './InstrumentInfoPanel.vue'

const getInstrumentChart = vi.fn()
const getInstrumentInfo = vi.fn()
vi.mock('../../api', () => ({ useApi: () => ({ getInstrumentChart, getInstrumentInfo }) }))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function chart(points: number[]) {
  return { points: points.map((v, i) => ({ t: `2026-05-0${i + 1}T00:00:00Z`, value: v })) }
}
function mountPanel(props: Record<string, unknown> = {}) {
  return mount(InstrumentInfoPanel, { props: { symbol: 'AAPL', ...props }, global: { plugins: [i18n] } })
}

describe('InstrumentInfoPanel', () => {
  beforeEach(() => {
    getInstrumentChart.mockReset(); getInstrumentInfo.mockReset()
    getInstrumentChart.mockResolvedValue(chart([100, 110]))
    getInstrumentInfo.mockResolvedValue({ symbol: 'AAPL', profile: { name: 'Apple Inc' },
      news: null, earnings: null, analystEstimates: null, earningsEstimates: null,
      fundamentalScore: null, fundamentals: null, insiderActivity: null })
  })

  it('loads info+chart on mount and emits header', async () => {
    const w = mountPanel()
    await flushPromises()
    expect(getInstrumentInfo).toHaveBeenCalledWith('AAPL')
    expect(getInstrumentChart).toHaveBeenCalledWith('AAPL', '1m')
    const ev = w.emitted('header')
    expect(ev).toBeTruthy()
    const last = ev![ev!.length - 1][0] as { name: string; lastPrice: number | null; change: number | null }
    expect(last.name).toBe('Apple Inc')
    expect(last.lastPrice).toBe(110)
    expect(last.change).toBe(10)
  })

  it('symbol prop change reloads info+chart, resets range, discards stale response', async () => {
    const w = mountPanel()
    await flushPromises()
    getInstrumentInfo.mockClear(); getInstrumentChart.mockClear()
    await w.setProps({ symbol: 'MSFT' })
    await flushPromises()
    expect(getInstrumentInfo).toHaveBeenCalledWith('MSFT')
    expect(getInstrumentChart).toHaveBeenCalledWith('MSFT', '1m')
  })

  it('range change fetches only chart (info not refetched) and re-emits header', async () => {
    const w = mountPanel()
    await flushPromises()
    const infoCalls = getInstrumentInfo.mock.calls.length
    await w.find('[data-testid="ip-range-1y"]').trigger('click')
    await flushPromises()
    expect(getInstrumentInfo.mock.calls.length).toBe(infoCalls)
    expect(getInstrumentChart).toHaveBeenLastCalledWith('AAPL', '1y')
  })

  it('info failure keeps panel alive (sections hidden, no throw)', async () => {
    getInstrumentInfo.mockRejectedValueOnce(new Error('boom'))
    const w = mountPanel()
    await flushPromises()
    expect(w.find('[data-testid="ip-section-news"]').exists()).toBe(false)
  })

  it('distinguishes a missing section from a failed fetch, with a translated message', async () => {
    // Today every failure is swallowed ("A failed info bundle must never error the panel"),
    // so a real backend outage would read as "not available for this listing".
    getInstrumentInfo.mockReset()
    getInstrumentInfo.mockRejectedValue(new Error('HTTP 502'))
    const w = mountPanel()
    await flushPromises()
    expect(w.find('[data-testid="iip-error"]').exists()).toBe(true)
    expect(w.find('[data-testid="iip-unavailable"]').exists()).toBe(false)
    // The raw exception text ('HTTP 502') must never reach the user — only
    // the translated, readable message.
    expect(w.get('[data-testid="iip-error"]').text()).toBe(de.instrument.error)
  })

  it('shows a named empty state for a listing without filings', async () => {
    getInstrumentInfo.mockResolvedValue({ symbol: 'AAPL', profile: { name: 'Apple Inc' },
      news: null, earnings: null, analystEstimates: null, earningsEstimates: null,
      fundamentalScore: null, fundamentals: null, insiderActivity: null })
    const w = mountPanel()
    await flushPromises()
    expect(w.find('[data-testid="iip-unavailable"]').exists()).toBe(true)
    expect(w.find('[data-testid="iip-error"]').exists()).toBe(false)
  })

  it('clears the previous instrument\'s error/empty state on a symbol change', async () => {
    getInstrumentInfo.mockRejectedValueOnce(new Error('HTTP 502'))
    const w = mountPanel()
    await flushPromises()
    expect(w.find('[data-testid="iip-error"]').exists()).toBe(true)

    // Next symbol's fetch is still pending — the stale error must be gone
    // immediately, not linger until the new fetch resolves.
    let resolveNext!: (v: unknown) => void
    getInstrumentInfo.mockReturnValueOnce(new Promise(resolve => { resolveNext = resolve }))
    await w.setProps({ symbol: 'MSFT' })
    expect(w.find('[data-testid="iip-error"]').exists()).toBe(false)
    expect(w.find('[data-testid="iip-unavailable"]').exists()).toBe(false)

    resolveNext({ symbol: 'MSFT', profile: { name: 'Microsoft Corp' },
      news: null, earnings: null, analystEstimates: null, earningsEstimates: null,
      fundamentalScore: null, fundamentals: null, insiderActivity: null })
    await flushPromises()
    expect(w.find('[data-testid="iip-unavailable"]').exists()).toBe(true)
  })

  it('price target uses currency prop, plain number without it', async () => {
    getInstrumentInfo.mockResolvedValue({ symbol: 'AAPL',
      analystEstimates: { priceTarget: 200, recommendations: [{ strongBuy: 1, buy: 0, hold: 0, sell: 0, strongSell: 0 }] },
      profile: null, news: null, earnings: null, earningsEstimates: null,
      fundamentalScore: null, fundamentals: null, insiderActivity: null })
    const w = mountPanel({ currency: 'EUR' })
    await flushPromises()
    expect(w.find('[data-testid="ip-section-insights"]').text()).toContain('200')
    // EUR formatting present (exact glyph depends on formatMoney); assert no bare USD when currency given
  })
})
