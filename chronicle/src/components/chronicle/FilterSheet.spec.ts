import { describe, it, expect, afterEach } from 'vitest'
import { mount, enableAutoUnmount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import FilterSheet from './FilterSheet.vue'
import InfoDot from '../common/InfoDot.vue'
import de from '../../i18n/locales/de'

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

// Unmount every wrapper after each test so the module-level scroll-lock
// ref-count (useScrollLock) never leaks between tests.
enableAutoUnmount(afterEach)

function stubMain(overflow = 'auto') {
  document.querySelector('main.app-main')?.remove()
  const m = document.createElement('main'); m.className = 'app-main'; m.style.overflow = overflow
  document.body.appendChild(m); return m
}

function make(props = {}) {
  return mount(FilterSheet, {
    attachTo: document.body,
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
  it('locks main.app-main scroll while open and restores the prior value on close', async () => {
    const main = document.createElement('main')
    main.className = 'app-main'
    main.style.overflow = 'auto'
    document.body.appendChild(main)

    const w = make({ open: true })
    await w.vm.$nextTick()
    expect(main.style.overflow).toBe('hidden')

    await w.setProps({ open: false })
    await w.vm.$nextTick()
    expect(main.style.overflow).toBe('auto')

    w.unmount()
    main.remove()
  })

  it('moves focus into the panel when opened', async () => {
    const w = make({ open: true })
    await w.vm.$nextTick()
    const panel = w.find('[role="dialog"]').element
    expect(panel.contains(document.activeElement)).toBe(true)
    w.unmount()
  })

  it('wraps focus from the last focusable element to the first on Tab', async () => {
    const w = make({ open: true })
    await w.vm.$nextTick()
    const focusables = w.find('[role="dialog"]').element
      .querySelectorAll<HTMLElement>('button')
    const first = focusables[0]
    const last = focusables[focusables.length - 1]
    last.focus()
    await w.find('[role="dialog"]').trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(first)
    w.unmount()
  })

  it('returns focus to the triggering element on close', async () => {
    const trigger = document.createElement('button')
    document.body.appendChild(trigger)
    trigger.focus()
    const w = make({ open: true })
    await w.vm.$nextTick()
    await w.setProps({ open: false })
    await w.vm.$nextTick()
    expect(document.activeElement).toBe(trigger)
    w.unmount()
    trigger.remove()
  })

  it('shows the Meute-overview explainer InfoDot on the brood-profiles head', () => {
    const w = make()
    // Mobile users must reach the same hunter.overview explainer the desktop
    // sidebar exposes next to "Die Meute".
    const topics = w.findAllComponents(InfoDot).map(c => c.props('topic'))
    expect(topics).toContain('hunter.overview')
  })

  it('does not repeat the panel title "Filter" as a section head', () => {
    const w = make()
    const headTexts = w.findAll('.fg-head').map(el => el.text())
    // Header already shows "Filter"; the first fg-head section must be a real
    // grouping ("Anomalie-Klasse"), not a second "Filter" label.
    expect(headTexts).not.toContain('Filter')
  })

  it('locks main.app-main while open and restores it when open flips to false', async () => {
    const m = stubMain('auto')
    const w = make({ open: true })
    await w.vm.$nextTick()
    expect(m.style.overflow).toBe('hidden')
    await w.setProps({ open: false })
    await w.vm.$nextTick()
    expect(m.style.overflow).toBe('auto')
    w.unmount()
  })
})
