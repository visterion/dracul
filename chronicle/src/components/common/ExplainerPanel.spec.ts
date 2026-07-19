import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
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
})
