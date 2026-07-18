import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import TickerButton from './TickerButton.vue'
import { useInstrumentOverlayStore } from '../../stores/instrumentOverlay'
import de from '../../i18n/locales/de'
import en from '../../i18n/locales/en'

const i18n = createI18n({ legacy: false, locale: 'en', messages: { de, en } })

// A parent that mimics PreyCard/WatchlistView: click + keydown on a role=button wrapper.
const Parent = {
  components: { TickerButton },
  template: `<div role="button" @click="onClick" @keydown.enter="onKey" @keydown.space.prevent="onKey">
               <TickerButton symbol="AAPL" /></div>`,
  props: ['onClick', 'onKey'],
}

function setup() {
  const onClick = vi.fn(); const onKey = vi.fn()
  setActivePinia(createPinia())
  const wrapper = mount(Parent, {
    props: { onClick, onKey },
    global: { plugins: [i18n] },
  })
  const store = useInstrumentOverlayStore()
  return { wrapper, store, onClick, onKey }
}

describe('TickerButton', () => {
  beforeEach(() => vi.clearAllMocks())

  it('click opens overlay and does not trigger parent click', async () => {
    const { wrapper, store, onClick } = setup()
    await wrapper.find('button').trigger('click')
    expect(store.openSymbol).toBe('AAPL')
    expect(onClick).not.toHaveBeenCalled()
  })

  it('Enter opens overlay and does not trigger parent keydown', async () => {
    const { wrapper, store, onKey } = setup()
    await wrapper.find('button').trigger('keydown', { key: 'Enter' })
    expect(store.openSymbol).toBe('AAPL')
    expect(onKey).not.toHaveBeenCalled()
  })

  it('Space opens overlay and does not trigger parent keydown', async () => {
    const { wrapper, store, onKey } = setup()
    await wrapper.find('button').trigger('keydown', { key: ' ' })
    expect(store.openSymbol).toBe('AAPL')
    expect(onKey).not.toHaveBeenCalled()
  })
})
