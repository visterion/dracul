import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import WatchlistView from './WatchlistView.vue'
import InstrumentSearch from '../components/instrument/InstrumentSearch.vue'
import InstrumentOverlay from '../components/instrument/InstrumentOverlay.vue'
import { useInstrumentOverlayStore } from '../stores/instrumentOverlay'
import { ApiError } from '../api/errors'
import de from '../i18n/locales/de'
import type { WatchlistItem } from '../api/types'

vi.mock('vuetify', () => ({
  useDisplay: () => ({ smAndDown: false }),
}))

function item(overrides: Partial<WatchlistItem> = {}): WatchlistItem {
  return {
    id: 'w-1',
    ticker: 'PYPL',
    companyName: 'PayPal Holdings',
    currentPrice: 62.5,
    dayChangePercent: 1.2,
    status: 'calm',
    addedAt: '2026-05-14',
    tag: 'TRACKING',
    verdictId: null,
    alerts: [],
    priceHistory30d: [],
    entryPrice: null,
    shareCount: null,
    owner: 'me@example.com',
    currency: 'USD',
    entryCurrency: 'USD',
    nativeCurrentPrice: 62.5,
    nativeCurrency: 'USD',
    nativeEntryPrice: null,
    source: 'manual',
    ...overrides,
  }
}

const itemA = item({ id: 'w-1', ticker: 'PYPL' })
const itemB = item({ id: 'w-2', ticker: 'AVGO', companyName: 'Broadcom Inc' })
const mockGetWatchlistItems = vi.fn(async () => [itemA, itemB])
const mockGetMe = vi.fn(async () => ({ email: 'me@example.com' }))
const mockCreateWatchlistItem = vi.fn()
const mockSearchInstruments = vi.fn(async () => [])

vi.mock('../api', () => ({
  useApi: () => ({
    getWatchlistItems: mockGetWatchlistItems,
    getMe: mockGetMe,
    createWatchlistItem: mockCreateWatchlistItem,
    deleteWatchlistItem: vi.fn(),
    searchInstruments: mockSearchInstruments,
  }),
}))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

function mountView() {
  return mount(WatchlistView, {
    global: { plugins: [i18n] },
  })
}

describe('WatchlistView ticker overlay', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockGetWatchlistItems.mockClear()
    mockGetWatchlistItems.mockResolvedValue([itemA, itemB])
    mockCreateWatchlistItem.mockReset()
    mockSearchInstruments.mockReset()
    mockSearchInstruments.mockResolvedValue([])
  })

  it('clicking the ticker opens the instrument overlay and does not change the selected row', async () => {
    const w = mountView()
    await flushPromises()

    const store = useInstrumentOverlayStore()

    const rows = w.findAll('[data-testid="watchlist-item"]')
    expect(rows).toHaveLength(2)
    // Desktop auto-select picks the first item on mount.
    expect(rows[0].classes()).toContain('active')
    expect(rows[1].classes()).not.toContain('active')

    // Click the ticker inside the SECOND (non-selected) row.
    await rows[1].find('.wr-ticker').trigger('click')

    expect(store.openSymbol).toBe('AVGO')
    // Selection must be unchanged: the ticker click must not have bubbled
    // into the row's own @click="selectedId = item.id" handler.
    const rowsAfter = w.findAll('[data-testid="watchlist-item"]')
    expect(rowsAfter[0].classes()).toContain('active')
    expect(rowsAfter[1].classes()).not.toContain('active')
  })
})

describe('WatchlistView add dialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockGetWatchlistItems.mockClear()
    mockGetWatchlistItems.mockResolvedValue([itemA, itemB])
    mockCreateWatchlistItem.mockReset()
    mockSearchInstruments.mockReset()
    mockSearchInstruments.mockResolvedValue([])
  })

  async function openDialogAndType(w: ReturnType<typeof mountView>, symbol: string) {
    await w.get('[data-testid="wl-open-add"]').trigger('click')
    const input = w.get('[data-testid="wl-add-symbol"]')
    await input.setValue(symbol)
  }

  it('renders a readable message on a validation error', async () => {
    // 400 used to fall into the raw-message branch ('bad request' verbatim);
    // only 404/422 were mapped. Assert the actual mapped string, not just
    // non-empty — the raw message is also non-empty and would pass a weaker check.
    mockCreateWatchlistItem.mockRejectedValue(new ApiError('bad request', 400))
    const w = mountView()
    await flushPromises()

    await openDialogAndType(w, 'NOKIA')
    await w.get('[data-testid="wl-add-submit"]').trigger('click')
    await flushPromises()

    expect(w.get('[role="alert"]').text()).toBe(de.watchlist.dialog.invalid)
  })

  it('lets a 15-char symbol reach the API', async () => {
    // TICKER_RE used to cap at 12 chars and silently return.
    mockCreateWatchlistItem.mockResolvedValue(item({ id: 'w-9', ticker: 'AT0000A324Q2.VI' }))
    const w = mountView()
    await flushPromises()

    await openDialogAndType(w, 'AT0000A324Q2.VI')
    await w.get('[data-testid="wl-add-submit"]').trigger('click')
    await flushPromises()

    expect(mockCreateWatchlistItem).toHaveBeenCalledWith(
      expect.objectContaining({ symbol: 'AT0000A324Q2.VI' }))
  })

  it('still maps 422 to the not-found message', async () => {
    mockCreateWatchlistItem.mockRejectedValue(new ApiError('nope', 422))
    const w = mountView()
    await flushPromises()

    await openDialogAndType(w, 'NOKIA')
    await w.get('[data-testid="wl-add-submit"]').trigger('click')
    await flushPromises()

    expect(w.get('[role="alert"]').text()).toContain('NOKIA')
  })

  it('maps a 5xx (Agora outage) to a translated message, not the raw HTTP string', async () => {
    mockCreateWatchlistItem.mockRejectedValue(new ApiError('createWatchlistItem failed: HTTP 502', 502))
    const w = mountView()
    await flushPromises()

    await openDialogAndType(w, 'NOKIA')
    await w.get('[data-testid="wl-add-submit"]').trigger('click')
    await flushPromises()

    expect(w.get('[role="alert"]').text()).toBe(de.watchlist.dialog.unavailable)
  })

  it('a search hit closes the dialog and opens the instrument overlay with symbol + name', async () => {
    // One entry point: search -> look at it -> add from there. Selecting a
    // hit must NOT fill the dialog's direct-entry field; it hands off to the
    // overlay store, whose own add-button (InstrumentOverlay.vue) is where
    // the create request actually happens.
    const w = mountView()
    await flushPromises()
    const store = useInstrumentOverlayStore()

    await w.get('[data-testid="wl-open-add"]').trigger('click')
    // Simulate the InstrumentSearch selection via its emit — the same event
    // the dialog's @select="onSearchSelect" handler consumes.
    w.getComponent(InstrumentSearch).vm.$emit('select', 'SYNA.HE', 'Synthetic Alpha Oyj')
    await flushPromises()

    expect(store.openSymbol).toBe('SYNA.HE')
    expect(store.openName).toBe('Synthetic Alpha Oyj')
    expect(mockCreateWatchlistItem).not.toHaveBeenCalled()
  })

  it('accepts a 24-char symbol and rejects a 25-char one (TICKER_RE boundary, byte-identical to the backend)', async () => {
    const w = mountView()
    await flushPromises()

    await openDialogAndType(w, 'A'.repeat(24))
    expect((w.get('[data-testid="wl-add-submit"]').element as HTMLButtonElement).disabled).toBe(false)

    await openDialogAndType(w, 'A'.repeat(25))
    expect((w.get('[data-testid="wl-add-submit"]').element as HTMLButtonElement).disabled).toBe(true)
  })
})

// The overlay is mounted once at the App shell (see App.vue), reachable from
// anywhere — this wrapper reproduces that shape so an add triggered through
// the overlay can be observed landing back in WatchlistView's own list,
// exactly like the real app instead of two isolated component mounts.
const InstrumentInfoPanelStub = defineComponent({
  name: 'InstrumentInfoPanel',
  props: { symbol: { type: String, required: true }, currency: { type: String, required: false } },
  emits: ['header'],
  template: '<div data-testid="ip-stub" />',
})

const AppShell = defineComponent({
  components: { WatchlistView, InstrumentOverlay },
  template: '<div><WatchlistView /><InstrumentOverlay /></div>',
})

function mountApp() {
  return mount(AppShell, {
    global: { plugins: [i18n], stubs: { InstrumentInfoPanel: InstrumentInfoPanelStub } },
  })
}

describe('WatchlistView + InstrumentOverlay integration', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockGetWatchlistItems.mockClear()
    mockGetWatchlistItems.mockResolvedValue([itemA, itemB])
    mockCreateWatchlistItem.mockReset()
    mockSearchInstruments.mockReset()
    mockSearchInstruments.mockResolvedValue([])
  })

  it('an add from the overlay appears in the watchlist list, and the just-added ticker stops being offered by the search CTA', async () => {
    mockCreateWatchlistItem.mockResolvedValue(
      item({ id: 'w-77', ticker: 'NOKIA.HE', companyName: 'Nokia Oyj' }),
    )
    const w = mountApp()
    await flushPromises()

    await w.get('[data-testid="wl-open-add"]').trigger('click')
    // Search hit -> overlay opens (see the WatchlistView-only test above for
    // the wiring itself) -> click the overlay's own add button.
    w.getComponent(InstrumentSearch).vm.$emit('select', 'NOKIA.HE', 'Nokia Oyj')
    await flushPromises()
    await w.get('[data-testid="io-add"]').trigger('click')
    await flushPromises()

    const rows = w.findAll('[data-testid="watchlist-item"]')
    expect(rows.some(r => r.text().includes('NOKIA.HE'))).toBe(true)

    // Second symptom: the empty-filter "add this ticker" CTA must not still
    // offer the ticker that was just added. Switch to the "alerts" tab (the
    // new item has none, so the filtered list collapses to empty) and search
    // its exact ticker — `addableSymbol` must see it in `items` regardless of
    // the active filter tab, or the CTA would re-offer a ticker already added.
    await w.findAll('.watch-tab')[1].trigger('click')
    const searchInput = w.get('.watch-search input')
    await searchInput.setValue('NOKIA.HE')
    await flushPromises()
    expect(w.find('[data-testid="wl-search-add"]').exists()).toBe(false)
  })
})
