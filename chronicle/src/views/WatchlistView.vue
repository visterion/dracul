<template>
  <div class="content-inner full-bleed">
    <div class="watch-grid" :class="{ 'show-detail': smAndDown && selectedId !== null }">
      <!-- LIST PANE -->
      <div
        class="watch-list-pane"
        data-testid="watchlist-list"
      >
        <div class="watch-search">
          <i class="ph ph-magnifying-glass" aria-hidden="true" />
          <input
            v-model="searchQuery"
            type="text"
            :placeholder="t('watchlist.search.placeholder')"
            :aria-label="t('watchlist.search.placeholder')"
          />
        </div>

        <div class="watch-tabs">
          <button
            v-for="f in filters"
            :key="f.key"
            class="watch-tab"
            :class="{ active: activeFilter === f.key }"
            @click="activeFilter = f.key"
          >
            {{ f.label }}
          </button>
        </div>

        <button class="btn btn-crimson-ghost watch-add" data-testid="wl-open-add" @click="addOpen = true">
          {{ t('watchlist.addButton') }}
        </button>

        <v-dialog v-model="addOpen" max-width="420">
          <div class="watchlist__dialog">
            <div class="watchlist__dialog-title">{{ t('watchlist.dialog.title') }}</div>
            <input
              v-model="addSymbol"
              class="watchlist__dialog-input"
              type="text"
              :placeholder="t('watchlist.dialog.placeholder')"
              maxlength="10"
              data-testid="wl-add-symbol"
              @input="addSymbol = addSymbol.toUpperCase()"
            />
            <div class="watchlist__dialog-tag">
              <label><input type="radio" v-model="addTag" value="TRACKING" /> {{ t('watchlist.dialog.tagTracking') }}</label>
              <label><input type="radio" v-model="addTag" value="HELD" /> {{ t('watchlist.dialog.tagHeld') }}</label>
            </div>
            <p v-if="addError" class="watchlist__dialog-error" role="alert">{{ addError }}</p>
            <div class="watchlist__dialog-actions">
              <button class="watchlist__dialog-cancel" @click="addOpen = false">{{ t('watchlist.dialog.cancel') }}</button>
              <button
                class="watchlist__dialog-submit"
                :disabled="!/^[A-Z][A-Z0-9.\-]{0,9}$/.test(addSymbol) || addSubmitting"
                data-testid="wl-add-submit"
                @click="onAddSymbol"
              >{{ t('watchlist.dialog.add') }}</button>
            </div>
          </div>
        </v-dialog>

        <template v-if="loading">
          <v-skeleton-loader
            v-for="i in 5"
            :key="i"
            type="list-item-two-line"
            class="watchlist__skeleton"
          />
        </template>

        <div v-else-if="filteredItems.length === 0" class="empty small">
          <div class="em-text">{{ t('watchlist.empty') }}</div>
        </div>

        <div v-else class="watch-rows">
          <div
            v-for="item in filteredItems"
            :key="item.id"
            class="watch-row"
            role="button"
            tabindex="0"
            :class="{ active: selectedId === item.id }"
            data-testid="watchlist-item"
            @click="selectedId = item.id"
            @keydown.enter="selectedId = item.id"
            @keydown.space.prevent="selectedId = item.id"
          >
            <div class="wr-id">
              <span class="wr-ticker mono">{{ item.ticker }}</span>
              <span class="wr-name">{{ item.companyName }}</span>
              <span class="wr-owner mono" :data-testid="`wl-owner-${item.id}`">{{ item.owner }}</span>
              <span class="wr-flags">
                <span v-if="item.tag === 'TRACKING'" class="wr-track">{{ t('watchlist.flags.tracked') }}</span>
                <span v-if="item.entryPrice !== null" class="wr-held">{{ t('watchlist.flags.position') }}</span>
              </span>
            </div>
            <div class="wr-price">
              <span class="wr-px mono">${{ fmtPrice(item.currentPrice) }}</span>
              <span class="wr-chg mono" :class="item.dayChangePercent >= 0 ? 'pos' : 'neg'">
                {{ item.dayChangePercent >= 0 ? '+' : '' }}{{ item.dayChangePercent.toFixed(1) }}%
              </span>
            </div>
            <div class="wr-meta">
              <button
                v-if="isMine(item)"
                class="wr-tag-toggle"
                :class="`wr-tag-toggle--${item.tag.toLowerCase()}`"
                :data-testid="`wl-tag-${item.id}`"
                :disabled="rowBusyId === item.id"
                @click.stop="onToggleTag(item)"
              >{{ item.tag === 'HELD' ? t('watchlist.tagLabel.held') : t('watchlist.tagLabel.tracking') }}</button>
              <span class="wr-dot" :class="`dot-${dotClass(item.status)}`" />
              <button
                v-if="isMine(item)"
                class="wr-delete"
                :data-testid="`wl-delete-${item.id}`"
                :disabled="rowBusyId === item.id"
                :aria-label="t('watchlist.deleteAria')"
                @click.stop="onDelete(item)"
              >✕</button>
            </div>
          </div>
        </div>
      </div>

      <!-- DETAIL PANE -->
      <div
        class="watch-detail-pane"
        data-testid="watchlist-detail"
      >
        <BackLink data-testid="watchlist-back" @click="selectedId = null">{{ t('watchlist.back') }}</BackLink>

        <template v-if="loading">
          <v-skeleton-loader type="heading" />
          <v-skeleton-loader type="paragraph" class="mt-4" />
        </template>

        <template v-else-if="selectedItem">
          <div class="wd-head">
            <div>
              <h1 class="wd-ticker mono">{{ selectedItem.ticker }}</h1>
              <div class="wd-name">{{ selectedItem.companyName }}</div>
              <div v-if="selectedItem.tag === 'TRACKING'" class="wd-since">
                {{ t('watchlist.detail.subtitleTracking', { date: formatDate(selectedItem.addedAt) }) }}
              </div>
            </div>
            <div class="wd-quote">
              <span class="wd-px mono">${{ fmtPrice(selectedItem.currentPrice) }}</span>
              <span class="wd-chg mono" :class="selectedItem.dayChangePercent >= 0 ? 'pos' : 'neg'">
                {{ selectedItem.dayChangePercent >= 0 ? '+' : '' }}{{ selectedItem.dayChangePercent.toFixed(1) }}% {{ t('watchlist.detail.today') }}
              </span>
            </div>
          </div>

          <!-- POSITION PANEL — backend-backed, editable entry price -->
          <SectionHeader :label="t('watchlist.sections.position')" />
          <div v-if="selectedItem.entryPrice !== null" class="wd-position-card">
            <div class="wd-pos-fields">
              <label class="wd-field">
                <span class="wd-field-k">{{ t('watchlist.position.entry') }}</span>
                <span class="wd-input-wrap">
                  <span class="wd-curr">$</span>
                  <input
                    v-model.number="entryPriceInput"
                    class="wd-input mono"
                    type="number"
                    step="0.01"
                    min="0"
                    data-testid="wl-entry-price"
                    @blur="onPositionBlur"
                  />
                </span>
                <span class="wd-field-hint mono">{{ t('watchlist.position.currentHint', { price: fmtPrice(selectedItem.currentPrice) }) }}</span>
              </label>
              <label class="wd-field">
                <span class="wd-field-k">{{ t('watchlist.position.size') }}</span>
                <span class="wd-input-wrap">
                  <input
                    v-model.number="shareCountInput"
                    class="wd-input mono"
                    type="number"
                    step="1"
                    min="0"
                    data-testid="wl-share-count"
                    @blur="onPositionBlur"
                  />
                </span>
                <span class="wd-field-hint mono">{{ t('watchlist.position.valueHint', { value: fmtInt((shareCountInput || 0) * selectedItem.currentPrice) }) }}</span>
              </label>
              <div class="wd-field">
                <span class="wd-field-k">{{ t('watchlist.position.pnl') }}</span>
                <span class="wd-pnl mono" :class="pnlAbs >= 0 ? 'pos' : 'neg'" data-testid="wl-pnl-abs">
                  {{ pnlAbs >= 0 ? '+' : '−' }}${{ fmtInt(Math.abs(pnlAbs)) }}
                </span>
                <span class="wd-field-hint mono" :class="pnlPct >= 0 ? 'pos' : 'neg'" data-testid="wl-pnl-pct">
                  {{ pnlPct >= 0 ? '+' : '' }}{{ pnlPct.toFixed(1) }}%
                </span>
              </div>
            </div>
            <div class="wd-pos-foot">
              <span class="wd-pos-note mono">
                <i class="ph ph-cloud-check" aria-hidden="true" />
                {{ t('watchlist.position.note') }}
              </span>
              <button class="wd-pos-remove" data-testid="wl-remove-position" @click="onRemovePosition">
                {{ t('watchlist.position.remove') }}
              </button>
            </div>
          </div>
          <div v-else class="wd-addpos">
            <div class="wd-addpos-text">
              <span>{{ t('watchlist.position.none') }}</span>
              <span class="wd-addpos-sub mono">{{ t('watchlist.position.noneHint', { price: fmtPrice(selectedItem.currentPrice) }) }}</span>
            </div>
            <button class="btn btn-primary" data-testid="wl-add-position" @click="onAddPosition">
              <i class="ph ph-plus" aria-hidden="true" /> {{ t('watchlist.position.capture') }}
            </button>
          </div>

          <!-- ALERT FEED -->
          <SectionHeader :label="t('watchlist.sections.alerts')" />
          <div v-if="selectedItem.alerts.length === 0" class="empty small">
            <div class="em-text">{{ t('watchlist.noAlerts') }}</div>
          </div>
          <div v-else class="alert-feed">
            <AlertRow
              v-for="alert in selectedItem.alerts"
              :key="alert.id"
              :alert="alert"
            />
          </div>

          <!-- LINKED VERDICT -->
          <template v-if="selectedItem.verdictId">
            <SectionHeader :label="t('watchlist.sections.linkedVerdicts')" />
            <div class="wd-verdict-card">
              <div class="wd-verdict-ticker mono">{{ selectedItem.ticker }} · Verdict #{{ selectedItem.verdictId }}</div>
              <router-link
                :to="{ name: 'verdict-detail', params: { id: selectedItem.verdictId } }"
                class="wd-verdict-link"
              >{{ t('watchlist.verdictLink') }}</router-link>
            </div>
          </template>
        </template>

        <div v-else class="empty small">
          <div class="em-text">{{ t('watchlist.noSelection') }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useDisplay } from 'vuetify'
import BackLink from '../components/common/BackLink.vue'
import SectionHeader from '../components/common/SectionHeader.vue'
import AlertRow from '../components/common/AlertRow.vue'
import { useApi } from '../api'
import { useMe } from '../composables/useMe'
import type { WatchlistItem, WatchlistStatus, WatchlistTag } from '../api/types'

const { t, locale } = useI18n()
const { smAndDown } = useDisplay()

const api = useApi()
const me = useMe()
const items = ref<WatchlistItem[]>([])
const loading = ref(true)
const selectedId = ref<string | null>(null)
const searchQuery = ref('')
const activeFilter = ref<'all' | 'held' | 'tracking' | 'alerts'>('all')

onMounted(async () => {
  try {
    items.value = await api.getWatchlistItems()
    // Desktop: auto-select the first item so the right pane is populated.
    // Mobile (drill-in): keep the list as the entry point — no auto-select.
    if (items.value.length > 0 && !smAndDown.value) selectedId.value = items.value[0].id
  } finally {
    loading.value = false
  }
})
function isMine(item: WatchlistItem): boolean { return item.owner === me.value }

const selectedItem = computed(() =>
  items.value.find(i => i.id === selectedId.value) ?? null
)

const counts = computed(() => ({
  all: items.value.length,
  held: items.value.filter(i => i.entryPrice !== null).length,
  tracking: items.value.filter(i => i.tag === 'TRACKING').length,
  alerts: items.value.filter(i => i.alerts.length > 0).length,
}))

const filters = computed(() => [
  { key: 'all' as const, label: t('watchlist.filter.all', { n: counts.value.all }) },
  { key: 'held' as const, label: t('watchlist.filter.held', { n: counts.value.held }) },
  { key: 'tracking' as const, label: t('watchlist.filter.tracking', { n: counts.value.tracking }) },
  { key: 'alerts' as const, label: t('watchlist.filter.alerts', { n: counts.value.alerts }) },
])

const filteredItems = computed(() =>
  items.value
    .filter(item => {
      if (activeFilter.value === 'held') return item.entryPrice !== null
      if (activeFilter.value === 'tracking') return item.tag === 'TRACKING'
      if (activeFilter.value === 'alerts') return item.alerts.length > 0
      return true
    })
    .filter(item => {
      if (!searchQuery.value) return true
      const q = searchQuery.value.toLowerCase()
      return item.ticker.toLowerCase().includes(q) || item.companyName.toLowerCase().includes(q)
    })
)

function dotClass(status: WatchlistStatus): 'positive' | 'warning' | 'danger' {
  if (status === 'calm') return 'positive'
  if (status === 'elevated') return 'warning'
  return 'danger'
}

function fmtPrice(n: number): string {
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtInt(n: number): string {
  return n.toLocaleString('en-US', { maximumFractionDigits: 0 })
}

// ── Position editing (backend-backed via patchWatchlistPosition) ──
const entryPriceInput = ref<number>(0)
const shareCountInput = ref<number>(0)

// Sync local inputs from the selected item whenever selection changes.
watch(selectedItem, (item) => {
  entryPriceInput.value = item?.entryPrice ?? 0
  shareCountInput.value = item?.shareCount ?? 0
}, { immediate: true })

const pnlAbs = computed(() => {
  const item = selectedItem.value
  if (!item) return 0
  return (item.currentPrice - entryPriceInput.value) * (shareCountInput.value || 0)
})
const pnlPct = computed(() => {
  const item = selectedItem.value
  if (!item || !entryPriceInput.value) return 0
  return ((item.currentPrice - entryPriceInput.value) / entryPriceInput.value) * 100
})

async function onAddPosition() {
  const item = selectedItem.value
  if (!item) return
  // Snapshot the current price as the (editable) entry; default size 100 shares.
  // Prefill the (now-revealed) inputs immediately — the computed selectedItem keeps
  // the same object reference, so the watcher won't re-fire on this mutation.
  entryPriceInput.value = item.currentPrice
  shareCountInput.value = 100
  await persistPosition(item, item.currentPrice, 100)
}

async function onPositionBlur() {
  const item = selectedItem.value
  // No existing position → nothing to persist on blur.
  if (!item || item.entryPrice === null) return
  // v-model.number yields NaN for a cleared field. Reject NaN, non-positive
  // entry prices, and negative share counts — to clear a position the user
  // uses the explicit "remove position" control, not an emptied input.
  const rawEntry = entryPriceInput.value
  const rawShares = shareCountInput.value
  if (
    Number.isNaN(rawEntry) ||
    Number.isNaN(rawShares) ||
    rawEntry <= 0 ||
    rawShares < 0
  ) {
    entryPriceInput.value = item.entryPrice ?? 0
    shareCountInput.value = item.shareCount ?? 0
    return
  }
  const entry = rawEntry
  const shares = rawShares
  // No-op short-circuit with float tolerance (exact === misfires on FP rounding).
  if (
    Math.abs(entry - (item.entryPrice ?? 0)) < 0.0001 &&
    Math.abs(shares - (item.shareCount ?? 0)) < 0.0001
  ) return
  await persistPosition(item, entry, shares)
}

async function onRemovePosition() {
  const item = selectedItem.value
  if (!item) return
  await persistPosition(item, null, null)
}

async function persistPosition(
  item: WatchlistItem,
  entryPrice: number | null,
  shareCount: number | null,
) {
  const prevEntry = item.entryPrice
  const prevShares = item.shareCount
  // Optimistic update so P&L reflects immediately.
  item.entryPrice = entryPrice
  item.shareCount = shareCount
  try {
    const updated = await api.patchWatchlistPosition(item.id, { entryPrice, shareCount })
    item.entryPrice = updated.entryPrice
    item.shareCount = updated.shareCount
  } catch {
    item.entryPrice = prevEntry
    item.shareCount = prevShares
    entryPriceInput.value = prevEntry ?? 0
    shareCountInput.value = prevShares ?? 0
  }
}

// ── Add / tag / delete (preserved real features) ──
const addOpen = ref(false)
const addSymbol = ref('')
const addTag = ref<WatchlistTag>('TRACKING')
const addSubmitting = ref(false)
const addError = ref<string | null>(null)

const rowBusyId = ref<string | null>(null)

async function onAddSymbol() {
  if (!/^[A-Z][A-Z0-9.\-]{0,9}$/.test(addSymbol.value)) return
  addSubmitting.value = true
  addError.value = null
  try {
    const created = await api.createWatchlistItem({ symbol: addSymbol.value, tag: addTag.value })
    items.value = [created, ...items.value.filter(i => i.id !== created.id)]
    addOpen.value = false
    addSymbol.value = ''
    addTag.value = 'TRACKING'
  } catch (e) {
    addError.value = (e as Error).message
  } finally {
    addSubmitting.value = false
  }
}

async function onToggleTag(item: WatchlistItem) {
  const newTag: WatchlistTag = item.tag === 'HELD' ? 'TRACKING' : 'HELD'
  const prev = item.tag
  item.tag = newTag
  rowBusyId.value = item.id
  try {
    await api.patchWatchlistItem(item.id, { tag: newTag })
  } catch {
    item.tag = prev
  } finally {
    rowBusyId.value = null
  }
}

async function onDelete(item: WatchlistItem) {
  if (!confirm(t('watchlist.confirmDelete', { ticker: item.ticker }))) return
  const idx = items.value.findIndex(i => i.id === item.id)
  if (idx === -1) return
  const removed = items.value[idx]
  if (selectedId.value === item.id) selectedId.value = null
  items.value = items.value.filter(i => i.id !== item.id)
  rowBusyId.value = item.id
  try {
    await api.deleteWatchlistItem(item.id)
  } catch {
    items.value = [...items.value.slice(0, idx), removed, ...items.value.slice(idx)]
  } finally {
    rowBusyId.value = null
  }
}

function formatDate(isoDate: string): string {
  return new Date(isoDate).toLocaleDateString(locale.value, { day: 'numeric', month: 'long', year: 'numeric' })
}
</script>

<style scoped>
/* ── Master / detail grid (ported from styles.css:343-407) ── */
.watch-grid { display: grid; grid-template-columns: 420px 1fr; height: 100%; }
.watch-list-pane {
  border-right: var(--hairline);
  display: flex;
  flex-direction: column;
  padding: var(--space-5);
  gap: var(--space-4);
  overflow-y: auto;
}
.watch-search {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  background: var(--crypt-black-deep);
  border: 1px solid rgba(245, 241, 232, 0.1);
  border-radius: 4px;
  padding: 0 var(--space-3);
  color: var(--ash-gray);
}
.watch-search input {
  flex: 1;
  background: none;
  border: none;
  outline: none;
  color: var(--bone-ivory);
  font-size: var(--text-body-sm);
  padding: 11px 0;
}
.watch-search input::placeholder { color: var(--ash-gray); }
.watch-search input:focus { color: var(--bone-ivory); }

.watch-tabs { display: flex; flex-wrap: wrap; gap: var(--space-1); }
.watch-tab {
  font-size: var(--text-micro);
  color: var(--ash-gray);
  background: none;
  border: none;
  padding: var(--space-2);
  border-radius: 4px;
  cursor: pointer;
  transition: color var(--transition-fast), background var(--transition-fast);
}
.watch-tab:hover { color: var(--bone-ivory-dim); }
.watch-tab.active { color: var(--bone-ivory); background: rgba(161, 29, 44, 0.12); }

.watch-add { justify-content: center; }

.watchlist__skeleton { margin-bottom: var(--space-2); }

.watch-rows { display: flex; flex-direction: column; }
.watch-row {
  display: grid;
  grid-template-columns: 1fr auto auto;
  gap: var(--space-3);
  align-items: center;
  padding: var(--space-3);
  border-bottom: 1px solid var(--rule);
  background: none;
  border-left: 3px solid transparent;
  border-top: none;
  border-right: none;
  cursor: pointer;
  text-align: left;
  transition: background var(--transition-fast);
}
.watch-row:hover { background: rgba(184, 148, 92, 0.05); }
.watch-row.active { background: rgba(161, 29, 44, 0.08); border-left-color: var(--blood-crimson); }
.watch-row:focus-visible { outline: 2px solid var(--cathedral-gold); outline-offset: -2px; }
.wr-id { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.wr-ticker { font-size: var(--text-body); color: var(--bone-ivory); }
.wr-name { font-size: var(--text-micro); color: var(--ash-gray); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.wr-owner { font-size: 11px; color: var(--ash-gray); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 160px; }
.wr-flags { display: flex; gap: var(--space-2); flex-wrap: wrap; }
.wr-track { font-size: 10px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--cathedral-gold); margin-top: 2px; }
.wr-held { font-size: 10px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--signal-positive-bright); margin-top: 2px; }
.wr-price { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
.wr-px { font-size: var(--text-body-sm); color: var(--bone-ivory); }
.wr-chg { font-size: var(--text-micro); }
.wr-meta { display: flex; flex-direction: column; align-items: flex-end; gap: var(--space-2); }
.wr-dot { width: 8px; height: 8px; border-radius: 50%; }
.dot-positive { background: var(--signal-positive); }
.dot-warning { background: var(--cathedral-gold); }
.dot-danger { background: var(--blood-crimson); box-shadow: 0 0 4px var(--blood-crimson); }

.wr-tag-toggle {
  background: transparent;
  border: 1px solid rgba(184, 148, 92, 0.3);
  border-radius: 2px;
  padding: 1px 6px;
  font-size: var(--text-micro);
  letter-spacing: 0.02em;
  cursor: pointer;
  transition: border-color var(--transition-fast), color var(--transition-fast);
}
.wr-tag-toggle--held { color: var(--cathedral-gold); }
.wr-tag-toggle--tracking { color: var(--bone-ivory-dim); }
.wr-tag-toggle:hover:not([disabled]) { border-color: var(--cathedral-gold); }
.wr-tag-toggle[disabled] { opacity: 0.5; cursor: not-allowed; }
.wr-delete {
  background: transparent;
  border: none;
  color: var(--ash-gray);
  cursor: pointer;
  padding: 0 var(--space-1);
  font-size: var(--text-body-sm);
  line-height: 1;
}
.wr-delete:hover { color: var(--blood-crimson); }
.wr-delete[disabled] { opacity: 0.5; cursor: not-allowed; }

/* ── Detail pane ── */
.watch-detail-pane { padding: var(--space-8); overflow-y: auto; }
.watch-detail-pane .back-link { display: none; }

.wd-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-4); flex-wrap: wrap; margin-bottom: var(--space-6); }
.wd-ticker { font-family: var(--font-mono); font-size: var(--text-h2); color: var(--bone-ivory); margin: 0; }
.wd-name { font-size: var(--text-body-lg); color: var(--bone-ivory-dim); margin-top: var(--space-1); }
.wd-since { font-size: var(--text-body-sm); color: var(--cathedral-gold); margin-top: var(--space-2); }
.wd-quote { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
.wd-px { font-size: var(--text-h3); color: var(--bone-ivory); }
.wd-chg { font-size: var(--text-body-sm); }

/* ── Position panel (ported from styles.css:383-397) ── */
.wd-position-card { background: var(--crypt-black-elevated); border: var(--hairline); border-radius: 4px; padding: var(--space-5); margin-bottom: var(--space-8); }
.wd-pos-fields { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-5); }
.wd-field { display: flex; flex-direction: column; gap: var(--space-2); }
.wd-field-k { font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.1em; color: var(--ash-gray); }
.wd-input-wrap {
  display: flex;
  align-items: center;
  gap: 2px;
  background: var(--crypt-black-deep);
  border: 1px solid rgba(184, 148, 92, 0.25);
  border-radius: 4px;
  padding: 0 var(--space-3);
  transition: border-color var(--transition-fast);
}
.wd-input-wrap:focus-within { border-color: var(--cathedral-gold); }
.wd-curr { color: var(--ash-gray); font-family: var(--font-mono); }
.wd-input {
  flex: 1;
  background: none;
  border: none;
  outline: none;
  color: var(--bone-ivory);
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--text-body-lg);
  padding: 9px 0;
  width: 100%;
  -moz-appearance: textfield;
  appearance: textfield;
}
.wd-input::-webkit-outer-spin-button,
.wd-input::-webkit-inner-spin-button { -webkit-appearance: none; margin: 0; }
.wd-field-hint { font-size: var(--text-micro); color: var(--ash-gray); }
.wd-pnl { font-size: var(--text-h4); padding: 7px 0; }
.wd-pos-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
  margin-top: var(--space-4);
  padding-top: var(--space-3);
  border-top: 1px solid var(--rule);
}
.wd-pos-note { font-size: var(--text-micro); color: var(--ash-gray); display: inline-flex; align-items: center; gap: var(--space-2); }
.wd-pos-remove {
  background: none;
  border: none;
  color: var(--ash-gray);
  font-size: var(--text-micro);
  cursor: pointer;
  padding: 0;
  transition: color var(--transition-fast);
}
.wd-pos-remove:hover { color: var(--blood-crimson); }

.wd-addpos {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-5);
  flex-wrap: wrap;
  background: var(--crypt-black-elevated);
  border: 1px dashed rgba(184, 148, 92, 0.3);
  border-radius: 4px;
  padding: var(--space-5);
  margin-bottom: var(--space-8);
}
.wd-addpos-text { display: flex; flex-direction: column; gap: var(--space-2); }
.wd-addpos-sub { font-size: var(--text-micro); color: var(--ash-gray); max-width: 52ch; }

.alert-feed { display: flex; flex-direction: column; gap: var(--space-3); }

/* ── Linked verdict ── */
.wd-verdict-card {
  border: 1px solid rgba(184, 148, 92, 0.15);
  border-radius: 4px;
  padding: var(--space-3) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.wd-verdict-ticker { font-size: var(--text-body-sm); color: var(--cathedral-gold); }
.wd-verdict-link { font-size: var(--text-micro); color: var(--blood-crimson); text-decoration: none; }
.wd-verdict-link:hover { color: var(--blood-crimson-bright); }

/* ── Add-symbol dialog ── */
.watchlist__dialog {
  background-color: var(--crypt-black-elevated);
  padding: var(--space-6);
  border-radius: 4px;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  border: 1px solid rgba(184, 148, 92, 0.2);
}
.watchlist__dialog-title { font-size: var(--text-body); color: var(--bone-ivory); letter-spacing: 0.02em; }
.watchlist__dialog-input {
  background-color: var(--crypt-black-deep);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 4px;
  color: var(--bone-ivory);
  font-family: var(--font-mono);
  padding: var(--space-3);
  font-size: var(--text-body-sm);
}
.watchlist__dialog-input:focus { outline: none; border-color: var(--cathedral-gold); }
.watchlist__dialog-tag { display: flex; gap: var(--space-4); font-size: var(--text-body-sm); color: var(--bone-ivory); }
.watchlist__dialog-tag input { margin-right: var(--space-2); }
.watchlist__dialog-error { color: var(--blood-crimson); font-size: var(--text-micro); margin: 0; }
.watchlist__dialog-actions { display: flex; justify-content: flex-end; gap: var(--space-2); }
.watchlist__dialog-cancel, .watchlist__dialog-submit {
  padding: var(--space-2) var(--space-4);
  border-radius: 4px;
  font-size: var(--text-body-sm);
  cursor: pointer;
}
.watchlist__dialog-cancel { background: transparent; border: 1px solid var(--ash-gray); color: var(--bone-ivory); }
.watchlist__dialog-submit { background-color: var(--blood-crimson); border: 1px solid var(--blood-crimson); color: var(--bone-ivory); }
.watchlist__dialog-submit[disabled] { opacity: 0.5; cursor: not-allowed; }

/* ── Responsive drill-in (ported from styles.css:529-535) ── */
@media (max-width: 959.98px) {
  .watch-grid { grid-template-columns: 1fr; }
  .watch-grid .watch-detail-pane { display: none; }
  .watch-grid.show-detail .watch-list-pane { display: none; }
  .watch-grid.show-detail .watch-detail-pane { display: block; padding: var(--space-5) var(--space-4); }
  .watch-detail-pane .back-link { display: inline-flex; }
  .watch-list-pane { border-right: none; }
  .wd-pos-fields { grid-template-columns: 1fr; gap: var(--space-4); }
  .wd-addpos { flex-direction: column; align-items: stretch; }
}
</style>
