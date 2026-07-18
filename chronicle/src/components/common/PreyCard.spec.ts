import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import PreyCard from './PreyCard.vue'
import { useInstrumentOverlayStore } from '../../stores/instrumentOverlay'
import de from '../../i18n/locales/de'
import type { Prey } from '../../api/types'

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function make(overrides: Partial<Prey> = {}) {
  const prey: Prey = {
    id: 'p-1',
    symbol: 'PYPL',
    companyName: 'PayPal Holdings',
    anomalyType: 'SPIN',
    confidence: 0.6,
    thesis: 'Thesis text.',
    signals: ['signal 1'],
    risks: ['risk 1'],
    killCriteria: [],
    discoveredBy: 'strigoi-spin',
    discoveredAt: new Date().toISOString(),
    horizon: '90d',
    ...overrides,
  }
  return mount(PreyCard, {
    props: { prey },
    global: { plugins: [i18n] },
  })
}

describe('PreyCard ticker overlay', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('clicking the ticker opens the instrument overlay and does not emit "open" on the card', async () => {
    const w = make({ symbol: 'PYPL' })
    const store = useInstrumentOverlayStore()

    await w.find('.prey-ticker').trigger('click')

    expect(store.openSymbol).toBe('PYPL')
    expect(w.emitted('open')).toBeUndefined()
  })
})
