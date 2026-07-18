import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import OrderTicketCard from './OrderTicketCard.vue'
import de from '../../i18n/locales/de'
import type { OrderTicket } from '../../api/types'

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

beforeEach(() => {
  setActivePinia(createPinia())
})

function make(ticket: OrderTicket) {
  return mount(OrderTicketCard, { props: { ticket }, global: { plugins: [i18n] } })
}

const base: OrderTicket = {
  side: 'SELL',
  symbol: 'TEST',
  shares: 0,
  limitReference: null,
  stop: 45,
  target: 70,
}

function sharesCell(w: ReturnType<typeof make>): string {
  // The shares value is the first <dd> in the ticket grid.
  return w.findAll('.ticket__grid dd')[0].text()
}

describe('OrderTicketCard shares formatting', () => {
  it('renders fractional shares with de-DE comma and up to 4 decimals', () => {
    const w = make({ ...base, shares: 0.2433 })
    expect(sharesCell(w)).toBe('0,2433')
  })

  it('renders whole shares without decimals', () => {
    const w = make({ ...base, shares: 10 })
    expect(sharesCell(w)).toBe('10')
  })
})
