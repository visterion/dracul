import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import InfoDot from './InfoDot.vue'
import de from '../../i18n/locales/de'

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function make(props: { topic: string; anchor?: string }) {
  return mount(InfoDot, { props, global: { plugins: [i18n] }, attachTo: document.body })
}

describe('InfoDot', () => {
  it('is a labelled button and opens the panel on click', async () => {
    const w = make({ topic: 'orders.bracket' })
    const btn = w.get('button')
    expect(btn.attributes('aria-label')).toBeTruthy()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    await btn.trigger('click')
    expect(document.querySelector('[role="dialog"]')).not.toBeNull()
    w.unmount()
  })

  it('closes the panel when the panel emits close', async () => {
    const w = make({ topic: 'orders.bracket' })
    await w.get('button').trigger('click')
    expect(document.querySelector('[role="dialog"]')).not.toBeNull()
    await (document.querySelector('[data-testid="explainer-close"]') as HTMLElement).click()
    await w.vm.$nextTick()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    w.unmount()
  })

  it('renders nothing for an unknown topic', () => {
    const w = make({ topic: 'does.not.exist' })
    expect(w.find('button').exists()).toBe(false)
    w.unmount()
  })

  it('icon variant renders a ph-info button with a custom label and still opens the panel', async () => {
    const w = mount(InfoDot, {
      props: { topic: 'depot.metrics', variant: 'icon', label: 'Wie Dracul entscheidet' },
      global: { plugins: [i18n] },
    })
    const btn = w.get('button')
    expect(btn.attributes('aria-label')).toBe('Wie Dracul entscheidet')
    expect(w.find('i.ph-info').exists()).toBe(true)
    await btn.trigger('click')
    expect(document.querySelector('[data-testid="explainer-overlay"]')).not.toBeNull()
    w.unmount()
  })

  it('default variant is unchanged (dot, default aria-label)', () => {
    const w = mount(InfoDot, { props: { topic: 'depot.metrics' }, global: { plugins: [i18n] } })
    expect(w.get('button').attributes('aria-label')).toBe(i18n.global.t('explainer.open'))
    w.unmount()
  })
})
