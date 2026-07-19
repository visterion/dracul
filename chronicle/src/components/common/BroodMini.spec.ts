import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import BroodMini from './BroodMini.vue'
import de from '../../i18n/locales/de'
import type { StrigoiStatus } from '../../api/types'

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function strigoi(name: string): StrigoiStatus {
  return { name, state: 'hunting' } as unknown as StrigoiStatus
}

describe('BroodMini explainer dots', () => {
  it('renders an InfoDot per known hunter and keeps a separate open-button', () => {
    const w = mount(BroodMini, {
      props: { strigoi: [strigoi('strigoi-echo')], counts: {} },
      global: { plugins: [i18n] },
    })
    expect(w.findAll('.info-dot').length).toBeGreaterThanOrEqual(1)
    // InfoDot must NOT be nested inside the row's open-button
    expect(w.get('.brood-row-open').element.querySelector('.info-dot')).toBeNull()
  })

  it('still emits open when the row open-button is clicked', async () => {
    const w = mount(BroodMini, {
      props: { strigoi: [strigoi('strigoi-echo')], counts: {} },
      global: { plugins: [i18n] },
    })
    await w.get('.brood-row-open').trigger('click')
    expect(w.emitted('open')).toBeTruthy()
  })
})
