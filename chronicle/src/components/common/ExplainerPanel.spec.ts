import { describe, it, expect, afterEach } from 'vitest'
import { mount, enableAutoUnmount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import ExplainerPanel from './ExplainerPanel.vue'
import de from '../../i18n/locales/de'
import type { Explainer } from '../../i18n/explainers'

const explainer: Explainer = {
  title: 'Geschützter Auftrag',
  sections: [
    { anchor: 'bracket', heading: 'Was ist das?', body: 'Ein Kauf mit Absicherung.' },
    { anchor: 'stop', heading: 'Stop', body: 'Verlustschutz.' },
  ],
}

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

// Unmount every wrapper after each test so the module-level scroll-lock
// ref-count (useScrollLock) never leaks between tests.
enableAutoUnmount(afterEach)

function stubMain(overflow = 'auto') {
  document.querySelector('main.app-main')?.remove()
  const m = document.createElement('main'); m.className = 'app-main'; m.style.overflow = overflow
  document.body.appendChild(m); return m
}

function make(anchor?: string) {
  return mount(ExplainerPanel, {
    props: { explainer, anchor },
    attachTo: document.body,
    global: { plugins: [i18n] },
  })
}

describe('ExplainerPanel', () => {
  it('scrolls to the anchored section when an anchor is given', () => {
    const w = make('stop')
    expect(w.findAll('[data-testid="explainer-section"]')).toHaveLength(2)
    expect(w.text()).toContain('Stop')
    w.unmount()
  })

  it('renders title and every section as a dialog', () => {
    const w = make()
    const dlg = w.get('[role="dialog"]')
    expect(dlg.attributes('aria-modal')).toBe('true')
    expect(w.text()).toContain('Geschützter Auftrag')
    expect(w.text()).toContain('Was ist das?')
    expect(w.text()).toContain('Stop')
    expect(w.findAll('[data-testid="explainer-section"]')).toHaveLength(2)
    w.unmount()
  })

  it('emits close on the close button and on Escape', async () => {
    const w = make()
    await w.get('[data-testid="explainer-close"]').trigger('click')
    expect(w.emitted('close')).toHaveLength(1)
    await w.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(w.emitted('close')).toHaveLength(2)
    w.unmount()
  })

  it('renders a section bullet list and key→value table when present', () => {
    const explainer = {
      title: 'T',
      sections: [
        { heading: 'Plain', body: 'just prose' },
        { heading: 'Rich', body: 'intro', bullets: ['one', 'two'],
          table: [{ label: 'Wann', value: '08:00 UTC' }, { label: 'Kappe', value: '5' }] },
      ],
    }
    const w = mount(ExplainerPanel, { props: { explainer }, global: { plugins: [i18n] } })
    expect(w.findAll('[data-testid="explainer-bullets"] li')).toHaveLength(2)
    const rows = w.findAll('[data-testid="explainer-table"] [data-testid="ex-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('Wann')
    expect(rows[0].text()).toContain('08:00 UTC')
    expect(w.findAll('[data-testid="explainer-bullets"]')).toHaveLength(1) // plain section has none
  })

  it('locks main.app-main while open and releases on unmount', () => {
    stubMain('auto')
    const w = mount(ExplainerPanel, { props: { explainer: { title: 'T', sections: [] } }, global: { plugins: [i18n] } })
    expect(document.querySelector<HTMLElement>('main.app-main')!.style.overflow).toBe('hidden')
    w.unmount()
    expect(document.querySelector<HTMLElement>('main.app-main')!.style.overflow).toBe('auto')
  })
})
