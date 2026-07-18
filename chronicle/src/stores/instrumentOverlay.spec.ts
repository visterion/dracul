import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useInstrumentOverlayStore } from './instrumentOverlay'

describe('instrumentOverlay store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('open sets openSymbol, close nulls it', () => {
    const store = useInstrumentOverlayStore()
    expect(store.openSymbol).toBeNull()
    store.open('AAPL')
    expect(store.openSymbol).toBe('AAPL')
    store.open('MSFT')
    expect(store.openSymbol).toBe('MSFT')
    store.close()
    expect(store.openSymbol).toBeNull()
  })
})
