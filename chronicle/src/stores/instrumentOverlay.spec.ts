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

  it('open carries an optional name; close and a no-name reopen both null it', () => {
    const store = useInstrumentOverlayStore()
    expect(store.openName).toBeNull()
    store.open('AAPL', 'Apple Inc')
    expect(store.openName).toBe('Apple Inc')
    store.close()
    expect(store.openName).toBeNull()
    store.open('MSFT', 'Microsoft Corp')
    expect(store.openName).toBe('Microsoft Corp')
    // Reopening without a name (the ticker/click path) must not leak the
    // previous open's name onto the new symbol.
    store.open('AVGO')
    expect(store.openName).toBeNull()
  })
})
