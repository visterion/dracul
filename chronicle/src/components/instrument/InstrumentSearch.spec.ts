import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import InstrumentSearch from './InstrumentSearch.vue'
import de from '../../i18n/locales/de'
import en from '../../i18n/locales/en'

// ── api mock ─────────────────────────────────────────────────────
// House convention (see InstrumentOverlay.spec.ts): mock the relative
// `../../api` module and its `useApi()` accessor, not an `@/api` alias.
const searchInstruments = vi.fn()
vi.mock('../../api', () => ({ useApi: () => ({ searchInstruments }) }))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de, en } })

function hit(symbol: string, exchange = 'NYSE') {
  return { symbol, name: `${symbol} Corp`, exchange, type: 'EQUITY' }
}

function mountSearch() {
  return mount(InstrumentSearch, { global: { plugins: [i18n] } })
}

describe('InstrumentSearch', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    searchInstruments.mockReset()
    searchInstruments.mockResolvedValue([hit('SYNA'), hit('SYNA.HE', 'HEL')])
  })
  afterEach(() => vi.useRealTimers())

  async function typeInto(wrapper: any, value: string) {
    const input = wrapper.get('[data-testid="is-input"]')
    await input.setValue(value)
  }

  it('does not query for a single character', async () => {
    const wrapper = mountSearch()
    await typeInto(wrapper, 'n')
    vi.advanceTimersByTime(500)
    await flushPromises()

    expect(searchInstruments).not.toHaveBeenCalled()
  })

  it('queries for exactly two characters', async () => {
    const wrapper = mountSearch()
    await typeInto(wrapper, 'no')
    vi.advanceTimersByTime(500)
    await flushPromises()

    expect(searchInstruments).toHaveBeenCalledWith('no', expect.any(Number))
  })

  it('collapses a typing burst into a single request', async () => {
    const wrapper = mountSearch()
    await typeInto(wrapper, 'no')
    await typeInto(wrapper, 'nok')
    await typeInto(wrapper, 'noki')
    await typeInto(wrapper, 'nokia')
    vi.advanceTimersByTime(500)
    await flushPromises()

    expect(searchInstruments).toHaveBeenCalledTimes(1)
    expect(searchInstruments).toHaveBeenCalledWith('nokia', expect.any(Number))
  })

  it('shows symbol, name and exchange per row', async () => {
    const wrapper = mountSearch()
    await typeInto(wrapper, 'nokia')
    vi.advanceTimersByTime(500)
    await flushPromises()

    const rows = wrapper.findAll('[data-testid="is-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[1].text()).toContain('SYNA.HE')
    expect(rows[1].text()).toContain('HEL')
  })

  it('emits select with symbol and name', async () => {
    const wrapper = mountSearch()
    await typeInto(wrapper, 'nokia')
    vi.advanceTimersByTime(500)
    await flushPromises()

    await wrapper.findAll('[data-testid="is-row"]')[1].trigger('click')

    expect(wrapper.emitted('select')?.[0]).toEqual(['SYNA.HE', 'SYNA.HE Corp'])
  })

  it('keeps a dotted symbol intact', async () => {
    searchInstruments.mockResolvedValue([hit('AT0000A324Q2.VI', 'Vienna')])
    const wrapper = mountSearch()
    await typeInto(wrapper, 'at0000')
    vi.advanceTimersByTime(500)
    await flushPromises()

    await wrapper.get('[data-testid="is-row"]').trigger('click')

    expect(wrapper.emitted('select')?.[0]?.[0]).toBe('AT0000A324Q2.VI')
  })

  it('moves the highlight with the arrow keys and selects with enter', async () => {
    const wrapper = mountSearch()
    await typeInto(wrapper, 'nokia')
    vi.advanceTimersByTime(500)
    await flushPromises()

    const input = wrapper.get('[data-testid="is-input"]')
    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'Enter' })

    expect(wrapper.emitted('select')?.[0]?.[0]).toBe('SYNA.HE')
  })

  it('tracks aria-activedescendant with the arrow-key highlight', async () => {
    const wrapper = mountSearch()
    await typeInto(wrapper, 'nokia')
    vi.advanceTimersByTime(500)
    await flushPromises()

    const input = wrapper.get('[data-testid="is-input"]')
    expect(input.attributes('aria-activedescendant')).toBeFalsy()

    const rows = wrapper.findAll('[data-testid="is-row"]')
    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(input.attributes('aria-activedescendant')).toBe(rows[0].attributes('id'))

    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(input.attributes('aria-activedescendant')).toBe(rows[1].attributes('id'))

    await input.trigger('keydown', { key: 'Escape' })
    expect(input.attributes('aria-activedescendant')).toBeFalsy()
  })

  it('clears the results on escape', async () => {
    const wrapper = mountSearch()
    await typeInto(wrapper, 'nokia')
    vi.advanceTimersByTime(500)
    await flushPromises()

    await wrapper.get('[data-testid="is-input"]').trigger('keydown', { key: 'Escape' })

    expect(wrapper.findAll('[data-testid="is-row"]')).toHaveLength(0)
  })

  it('shows a named empty state, not an error, when there are no hits', async () => {
    searchInstruments.mockResolvedValue([])
    const wrapper = mountSearch()
    await typeInto(wrapper, 'nothingatall')
    vi.advanceTimersByTime(500)
    await flushPromises()

    expect(wrapper.find('[data-testid="is-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="is-error"]').exists()).toBe(false)
  })

  it('shows an error state when the backend fails', async () => {
    searchInstruments.mockRejectedValue(new Error('HTTP 502'))
    const wrapper = mountSearch()
    await typeInto(wrapper, 'nokia')
    vi.advanceTimersByTime(500)
    await flushPromises()

    expect(wrapper.find('[data-testid="is-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="is-empty"]').exists()).toBe(false)
  })

  it('discards a response that arrives after a newer one', async () => {
    let resolveFirst: (v: any) => void = () => {}
    searchInstruments
      .mockImplementationOnce(() => new Promise(r => { resolveFirst = r }))
      .mockResolvedValueOnce([hit('SECOND')])

    const wrapper = mountSearch()
    await typeInto(wrapper, 'aa')
    vi.advanceTimersByTime(500)
    await typeInto(wrapper, 'bb')
    vi.advanceTimersByTime(500)
    await flushPromises()

    resolveFirst([hit('FIRST')])
    await flushPromises()

    expect(wrapper.get('[data-testid="is-row"]').text()).toContain('SECOND')
  })
})
