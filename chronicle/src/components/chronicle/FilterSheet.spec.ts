import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import FilterSheet from './FilterSheet.vue'
import de from '../../i18n/locales/de'

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function make(props = {}) {
  return mount(FilterSheet, {
    props: {
      open: true, filter: 'all', anomalyTypes: ['SPIN', 'PEAD'],
      counts: { all: 5, high: 2, SPIN: 3, PEAD: 2 },
      strigoi: [], broodCounts: {},
      ...props,
    },
    global: { plugins: [i18n] },
  })
}

describe('FilterSheet', () => {
  it('renders a dialog when open', () => {
    const w = make()
    expect(w.find('[role="dialog"]').exists()).toBe(true)
  })
  it('renders nothing when closed', () => {
    const w = make({ open: false })
    expect(w.find('[role="dialog"]').exists()).toBe(false)
  })
  it('emits close on backdrop click', async () => {
    const w = make()
    await w.find('[data-testid="filter-sheet-backdrop"]').trigger('click')
    expect(w.emitted('close')).toHaveLength(1)
  })
  it('emits close on Escape', async () => {
    const w = make()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await w.vm.$nextTick()
    expect(w.emitted('close')).toHaveLength(1)
  })
  it('emits select with the chip value', async () => {
    const w = make()
    await w.find('[data-testid="sheet-chip-high"]').trigger('click')
    expect(w.emitted('select')![0]).toEqual(['high'])
  })
  it('does not emit close on Escape while closed', async () => {
    const w = make({ open: false })
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await w.vm.$nextTick()
    expect(w.emitted('close')).toBeUndefined()
  })
  it('locks body scroll while open and restores on close', async () => {
    const w = make({ open: true })
    await w.vm.$nextTick()
    expect(document.body.style.overflow).toBe('hidden')
    await w.setProps({ open: false })
    expect(document.body.style.overflow).toBe('')
  })
})
