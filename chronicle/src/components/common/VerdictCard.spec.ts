import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import VerdictCard from './VerdictCard.vue'
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
