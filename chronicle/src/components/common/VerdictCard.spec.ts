import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import VerdictCard from './VerdictCard.vue'
import { useInstrumentOverlayStore } from '../../stores/instrumentOverlay'
import de from '../../i18n/locales/de'
import type { Verdict } from '../../api/types'

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function make(overrides: Partial<Verdict> = {}) {
  const verdict: Verdict = {
    id: 'v-1',
    symbol: 'PYPL',
    companyName: 'PYPL',
    contributingStrigoi: ['strigoi-spin'],
    consensusScore: 0.84,
    summary: 'Summary teaser.',
    createdAt: new Date().toISOString(),
    ...overrides,
  }
  return mount(VerdictCard, {
    props: { verdict },
    global: {
      plugins: [i18n],
      stubs: { ConsensusRing: true, BatGlyph: true },
    },
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('VerdictCard ticker overlay', () => {
  it('clicking the ticker opens the instrument overlay and does not emit "open" on the card', async () => {
    const w = make({ symbol: 'PYPL' })
    const store = useInstrumentOverlayStore()

    await w.find('.vc-ticker').trigger('click')

    expect(store.openSymbol).toBe('PYPL')
    expect(w.emitted('open')).toBeUndefined()
  })
})

describe('VerdictCard company name', () => {
  it('hides the company name when it equals the symbol (no "PYPL PYPL")', () => {
    const w = make({ symbol: 'PYPL', companyName: 'PYPL' })
    expect(w.find('.vc-name').exists()).toBe(false)
  })

  it('renders a real company name distinct from the symbol', () => {
    const w = make({ symbol: 'AVGO', companyName: 'Broadcom Inc' })
    expect(w.find('.vc-name').text()).toBe('Broadcom Inc')
  })
})
