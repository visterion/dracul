import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { setActivePinia, createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import InstrumentOverlay from './InstrumentOverlay.vue'
import de from '../../i18n/locales/de'
import { useInstrumentOverlayStore } from '../../stores/instrumentOverlay'
import { ApiError } from '../../api/errors'
import type { Depot, DepotPositionView } from '../../api/types'

// ── api mock ─────────────────────────────────────────────────────
const getDepots = vi.fn()
const createWatchlistItem = vi.fn()
vi.mock('../../api', () => ({ useApi: () => ({ getDepots, createWatchlistItem }) }))

function pos(symbol: string, over: Partial<DepotPositionView> = {}): DepotPositionView {
  return {
    symbol, qty: 1, avgEntryPrice: 1, marketValue: 1, unrealizedPl: 0, unrealizedPlPct: 0,
    price: 1, dayChangePercent: 0, weightPct: 0, currency: 'USD', name: null, assetType: null,
    valueDate: null, nativePrice: null, nativeCurrency: null, ...over,
  }
}
function depot(id: string, environment: 'paper' | 'live', positions: DepotPositionView[]): Depot {
  return {
    id, provider: 'x', environment, status: 'ok', probedAt: null, error: null,
    account: null, aggregates: null, positions, orders: [], asOf: null,
  }
}

// ── stub the real InstrumentInfoPanel — it emits a header on mount so the
//    overlay header can be asserted without the real fetch chain. ───────────
const InstrumentInfoPanelStub = defineComponent({
  name: 'InstrumentInfoPanel',
  props: { symbol: { type: String, required: true }, currency: { type: String, required: false } },
  emits: ['header'],
  template: '<div data-testid="ip-stub" />',
  mounted() {
    this.$emit('header', { name: `Name ${this.symbol}`, lastPrice: 123.4, change: 1.2, changePct: 0.98 })
  },
})

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', name: 'home', component: { template: '<div/>' } },
    { path: '/depots/:connection/:symbol', name: 'depot-position-detail', component: { template: '<div/>' } },
  ],
})

function mountOverlay() {
  return mount(InstrumentOverlay, {
    global: {
      plugins: [i18n, router],
      // The real VDialog teleports to <body> and drives its own transition/
      // overlay machinery — none of that is under test here. Stub it down to
      // a plain conditional wrapper so assertions can run against the mounted
      // wrapper's DOM directly instead of chasing teleported content.
      stubs: {
        VDialog: { props: ['modelValue'], emits: ['update:model-value'], template: '<div v-if="modelValue"><slot /></div>' },
        InstrumentInfoPanel: InstrumentInfoPanelStub,
      },
    },
  })
}

describe('InstrumentOverlay', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    getDepots.mockReset()
    getDepots.mockResolvedValue({ depots: [] })
    createWatchlistItem.mockReset()
    await router.push('/')
  })

  it('renders nothing when no symbol is open', () => {
    const w = mountOverlay()
    expect(w.find('[data-testid="io-symbol"]').exists()).toBe(false)
  })

  it('renders the panel when openSymbol is set', async () => {
    const store = useInstrumentOverlayStore()
    const w = mountOverlay()
    store.open('AAPL')
    await flushPromises()

    expect(w.find('[data-testid="io-symbol"]').text()).toContain('AAPL')
    expect(w.find('[data-testid="ip-stub"]').exists()).toBe(true)
  })

  it('populates the header from the stubbed @header emit', async () => {
    const store = useInstrumentOverlayStore()
    const w = mountOverlay()
    store.open('AAPL')
    await flushPromises()

    expect(w.find('[data-testid="io-header-name"]').text()).toBe('Name AAPL')
    expect(w.find('[data-testid="io-header-price"]').text()).toBe('123,40')
  })

  it('shows the held banner when findHolding returns a hit', async () => {
    getDepots.mockResolvedValue({ depots: [depot('depot-1', 'live', [pos('AAPL')])] })
    const store = useInstrumentOverlayStore()
    const w = mountOverlay()
    store.open('AAPL')
    await flushPromises()

    expect(w.find('[data-testid="io-banner"]').exists()).toBe(true)
  })

  it('hides the held banner when findHolding returns null', async () => {
    getDepots.mockResolvedValue({ depots: [depot('depot-1', 'live', [pos('MSFT')])] })
    const store = useInstrumentOverlayStore()
    const w = mountOverlay()
    store.open('AAPL')
    await flushPromises()

    expect(w.find('[data-testid="io-banner"]').exists()).toBe(false)
  })

  it('hides the held banner when getDepots rejects', async () => {
    getDepots.mockRejectedValue(new Error('boom'))
    const store = useInstrumentOverlayStore()
    const w = mountOverlay()
    store.open('AAPL')
    await flushPromises()

    expect(w.find('[data-testid="io-banner"]').exists()).toBe(false)
  })

  it('never shows a stale holding: open(A) -> slow load -> close -> open(B) shows no A-banner', async () => {
    let resolveA!: (v: { depots: Depot[] }) => void
    const aPromise = new Promise<{ depots: Depot[] }>(resolve => { resolveA = resolve })
    getDepots.mockReturnValueOnce(aPromise)

    const store = useInstrumentOverlayStore()
    const w = mountOverlay()

    store.open('AAPL')
    await flushPromises()
    // A's load is still pending — switch away before it resolves.
    store.close()

    getDepots.mockResolvedValueOnce({ depots: [] })
    store.open('MSFT')
    await flushPromises()

    // Now let A's slow load resolve with an AAPL holding, after the user has
    // already moved on to MSFT. The banner must stay keyed off the CURRENT
    // openSymbol (computed), never an imperative capture from when A opened.
    resolveA({ depots: [depot('depot-1', 'live', [pos('AAPL')])] })
    await flushPromises()

    expect(store.openSymbol).toBe('MSFT')
    expect(w.find('[data-testid="io-banner"]').exists()).toBe(false)
  })

  it('banner click routes to depot-position-detail with {connection, symbol} and closes the overlay', async () => {
    getDepots.mockResolvedValue({ depots: [depot('depot-7', 'live', [pos('AAPL')])] })
    const store = useInstrumentOverlayStore()
    const w = mountOverlay()
    store.open('AAPL')
    await flushPromises()

    const banner = w.find('[data-testid="io-banner"]')
    expect(banner.exists()).toBe(true)
    await banner.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('depot-position-detail')
    expect(router.currentRoute.value.params).toEqual({ connection: 'depot-7', symbol: 'AAPL' })
    expect(store.openSymbol).toBeNull()
  })

  it('handles an unknown symbol: header renders, no crash, no banner', async () => {
    getDepots.mockResolvedValue({ depots: [] })
    const store = useInstrumentOverlayStore()
    const w = mountOverlay()
    expect(() => store.open('ZZZZZ')).not.toThrow()
    await flushPromises()

    expect(w.find('[data-testid="io-header-name"]').text()).toBe('Name ZZZZZ')
    expect(w.find('[data-testid="io-banner"]').exists()).toBe(false)
  })

  it('closes via the close button (aria-label)', async () => {
    const store = useInstrumentOverlayStore()
    const w = mountOverlay()
    store.open('AAPL')
    await flushPromises()

    await w.find(`[aria-label="${de.instrument.close}"]`).trigger('click')
    expect(store.openSymbol).toBeNull()
  })

  it('passes the search-supplied name when adding from the overlay, not the header\'s', async () => {
    // The stubbed InstrumentInfoPanel always emits `Name ${symbol}` as the header
    // name (see InstrumentInfoPanelStub above) — i.e. `Name SYNA.HE`, which is
    // present and DIFFERENT from the store's search-supplied name. A regression
    // to the header fallback (e.g. `header.value.name ?? store.openName`, the
    // wrong precedence) would send 'Name SYNA.HE' and fail this assertion.
    // NOKIA.HE has no profile name at all in production, so the panel header
    // cannot supply it there — this is the scenario that matters.
    createWatchlistItem.mockResolvedValue({
      id: 'w-1', ticker: 'SYNA.HE', companyName: 'Synthetic Alpha Oyj', currentPrice: 1,
      dayChangePercent: 0, status: 'calm', addedAt: '2026-08-08', tag: 'TRACKING', verdictId: null,
      alerts: [], priceHistory30d: [], entryPrice: null, shareCount: null, owner: 'me@example.com',
      currency: 'EUR', entryCurrency: 'EUR', nativeCurrentPrice: 1, nativeCurrency: 'EUR',
      nativeEntryPrice: null, source: 'manual',
    })
    const store = useInstrumentOverlayStore()
    const w = mountOverlay()
    store.open('SYNA.HE', 'Synthetic Alpha Oyj')
    await flushPromises()

    // Sanity: the header name really is present and really does differ from
    // the store's name, so this test is actually discriminating.
    expect(w.find('[data-testid="io-header-name"]').text()).toBe('Name SYNA.HE')

    await w.get('[data-testid="io-add"]').trigger('click')
    await flushPromises()

    expect(createWatchlistItem).toHaveBeenCalledWith(
      expect.objectContaining({ symbol: 'SYNA.HE', name: 'Synthetic Alpha Oyj' }))
  })

  it('shows a readable message when the add fails validation', async () => {
    // 400 used to fall into the raw-message branch ('bad request' verbatim);
    // assert the actual mapped string, not just non-empty.
    createWatchlistItem.mockRejectedValue(new ApiError('bad request', 400))
    const store = useInstrumentOverlayStore()
    const w = mountOverlay()
    store.open('AAPL')
    await flushPromises()

    await w.get('[data-testid="io-add"]').trigger('click')
    await flushPromises()

    expect(w.get('[data-testid="io-add-error"]').text()).toBe(de.watchlist.dialog.invalid)
  })

  it('maps a 5xx (Agora outage) to a translated message, not the raw HTTP string', async () => {
    createWatchlistItem.mockRejectedValue(new ApiError('createWatchlistItem failed: HTTP 502', 502))
    const store = useInstrumentOverlayStore()
    const w = mountOverlay()
    store.open('AAPL')
    await flushPromises()

    await w.get('[data-testid="io-add"]').trigger('click')
    await flushPromises()

    expect(w.get('[data-testid="io-add-error"]').text()).toBe(de.watchlist.dialog.unavailable)
  })
})
