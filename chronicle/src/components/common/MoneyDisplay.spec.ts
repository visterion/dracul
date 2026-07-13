import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import MoneyDisplay from './MoneyDisplay.vue'
import de from '../../i18n/locales/de'

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function make(props: InstanceType<typeof MoneyDisplay>['$props']) {
  return mount(MoneyDisplay, { props, global: { plugins: [i18n] } })
}

describe('MoneyDisplay originalPrimary', () => {
  it('renders original currency first, converted in parens, without the "urspr." token', () => {
    const w = make({
      amount: 1147.70, currency: 'EUR',
      nativeAmount: 1247.50, nativeCurrency: 'USD',
      originalPrimary: true,
    })
    expect(w.text()).toBe('1.247,50 $ (1.147,70 €)')
    expect(w.text()).not.toContain('urspr.')
  })

  it('falls back to display currency when native equals display currency', () => {
    const w = make({
      amount: 100, currency: 'EUR',
      nativeAmount: 100, nativeCurrency: 'EUR',
      originalPrimary: true,
    })
    expect(w.text().trim()).toBe('100,00 €')
  })

  it('keeps display-primary + native line in bare parens when originalPrimary is not set', () => {
    const w = make({
      amount: 1147.70, currency: 'EUR',
      nativeAmount: 1247.50, nativeCurrency: 'USD',
    })
    expect(w.text()).toContain('1.147,70 €')
    expect(w.text()).not.toContain('urspr.')
    expect(w.text()).toContain('1.247,50')
  })
})
