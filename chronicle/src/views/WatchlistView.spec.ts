import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import WatchlistView from './WatchlistView.vue'
import { useInstrumentOverlayStore } from '../stores/instrumentOverlay'
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

vi.mock('../api', () => ({
  useApi: () => ({
    getWatchlistItems: mockGetWatchlistItems,
    getMe: mockGetMe,
    createWatchlistItem: vi.fn(),
    deleteWatchlistItem: vi.fn(),
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
