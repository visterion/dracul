<template>
  <div class="content-inner full-bleed">
    <div class="watch-toolbar">
      <div class="watch-mode" role="tablist">
        <button
          class="watch-mode-btn"
          :class="{ active: mode === 'list' }"
          data-testid="wl-mode-list"
          @click="mode = 'list'"
        >{{ t('watchlist.compare.modeList') }}</button>
        <button
          class="watch-mode-btn"
          :class="{ active: mode === 'compare' }"
          data-testid="wl-mode-compare"
          :disabled="otherOwners.length === 0"
          :title="otherOwners.length === 0 ? t('watchlist.compare.noOtherUser') : ''"
          @click="mode = 'compare'"
        >{{ t('watchlist.compare.modeCompare') }}</button>
      </div>
      <label v-if="mode === 'compare' && otherOwners.length > 0" class="watch-vs">
        <span class="watch-vs-k">{{ me }} {{ t('watchlist.compare.vs') }}</span>
        <select v-model="compareWith" class="watch-vs-select mono" data-testid="wl-compare-with">
          <option v-for="o in otherOwners" :key="o" :value="o">{{ o }}</option>
        </select>
      </label>
    </div>

    <WatchlistCompare
      v-if="mode === 'compare' && compareWith"
      :items="trackingItems"
      :me="me"
      :compare-with="compareWith"
    />

    <div
      v-else
      class="watch-grid"
      :class="{ 'show-detail': smAndDown && selectedId !== null }"
    >
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

        <button class="btn btn-crimson-ghost watch-add" data-testid="wl-open-add" @click="openAddDialog">
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
              @keydown.enter="onAddSymbol"
            />
            <p v-if="addError" class="watchlist__dialog-error" role="alert">{{ addError }}</p>
            <div class="watchlist__dialog-actions">
              <button class="watchlist__dialog-cancel" @click="addOpen = false">{{ t('watchlist.dialog.cancel') }}</button>
              <button
                class="watchlist__dialog-submit"
                :disabled="!TICKER_RE.test(addSymbol) || addSubmitting"
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
          <template v-if="addableSymbol">
            <div class="em-text">{{ t('watchlist.search.notFound', { symbol: addableSymbol }) }}</div>
            <button
              class="btn btn-crimson-ghost wl-search-add"
              data-testid="wl-search-add"
              @click="onAddFromSearch"
            >
              {{ t('watchlist.search.addCta', { symbol: addableSymbol }) }}
            </button>
          </template>
          <div v-else class="em-text">{{ t('watchlist.empty') }}</div>
        </div>

        <div v-else class="watch-rows">
          <template v-for="group in groupedItems" :key="group.owner || '__mine__'">
            <div
              v-if="group.owner"
              class="watch-owner-sep"
              :data-testid="`wl-owner-${group.owner}`"
            >{{ t('watchlist.ownerGroup', { owner: group.owner }) }}</div>
            <div
              v-for="item in group.items"
              :key="item.id"
              class="watch-row"
              role="button"
              tabindex="0"
              :class="{ active: selectedId === item.id }"
              :data-owner="item.owner"
              data-testid="watchlist-item"
              @click="selectedId = item.id"
              @keydown.enter="selectedId = item.id"
              @keydown.space.prevent="selectedId = item.id"
            >
              <div class="wr-id">
                <span class="wr-ticker mono">{{ item.ticker }}</span>
                <span v-if="displayName(item.ticker, item.companyName)" class="wr-name">{{ displayName(item.ticker, item.companyName) }}</span>
                <span class="wr-flags">
                  <span v-if="showsVerdictBadge(item)" class="wr-track">{{ t('watchlist.flags.tracked') }}</span>
                  <span v-if="item.entryPrice !== null" class="wr-held">{{ t('watchlist.flags.position') }}</span>
                </span>
              </div>
              <div class="wr-price">
                <span class="wr-px mono"><MoneyDisplay :amount="item.currentPrice" :currency="item.currency" :native-amount="item.nativeCurrentPrice" :native-currency="item.nativeCurrency" /></span>
                <span class="wr-chg mono" :class="pctClass(item.dayChangePercent)">
                  {{ formatPercent(item.dayChangePercent) }}
                </span>
              </div>
              <div class="wr-meta">
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
          </template>
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
              <div v-if="displayName(selectedItem.ticker, selectedItem.companyName)" class="wd-name">{{ displayName(selectedItem.ticker, selectedItem.companyName) }}</div>
              <div v-if="showsVerdictBadge(selectedItem)" class="wd-since">
                {{ t('watchlist.detail.subtitleTracking', { date: formatDate(selectedItem.addedAt) }) }}
              </div>
            </div>
            <div class="wd-quote">
              <span class="wd-px mono"><MoneyDisplay :amount="selectedItem.currentPrice" :currency="selectedItem.currency" :native-amount="selectedItem.nativeCurrentPrice" :native-currency="selectedItem.nativeCurrency" /></span>
              <span class="wd-chg mono" :class="pctClass(selectedItem.dayChangePercent)">
                {{ formatPercent(selectedItem.dayChangePercent) }} {{ t('watchlist.detail.today') }}
              </span>
            </div>
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
import WatchlistCompare from '../components/watchlist/WatchlistCompare.vue'
import MoneyDisplay from '../components/common/MoneyDisplay.vue'
import { useApi } from '../api'
import { useMe } from '../composables/useMe'
import { useToast } from '../composables/useToast'
import { ApiError } from '../api/errors'
import type { WatchlistItem, WatchlistStatus } from '../api/types'
import { formatPercent, pctClass } from '../utils/format'
import { displayName } from '../utils/instrument'
import { showsVerdictBadge, groupByOwner } from '../lib/watchlistDisplay'

const { t, locale } = useI18n()
const { smAndDown } = useDisplay()

const api = useApi()
const me = useMe()
const toast = useToast()
const items = ref<WatchlistItem[]>([])
const loading = ref(true)
const selectedId = ref<string | null>(null)
const searchQuery = ref('')
// Valid ticker shape: leading letter or digit, then up to 11 of letter/digit/dot/hyphen.
// Shared by the add dialog's submit guard, onAddSymbol, and the search CTA.
const TICKER_RE = /^[A-Z0-9][A-Z0-9.\-]{0,11}$/
const activeFilter = ref<'all' | 'alerts'>('all')
const mode = ref<'list' | 'compare'>('list')
const compareWith = ref<string | null>(null)

// Tracking-only watchlist: items with no captured entry price. The manual
// entry/shareCount position path is retired (depot-1 is the source of truth
// for held positions now); this filter just excludes any legacy rows that
// still carry one.
const trackingItems = computed(() => items.value.filter(i => i.entryPrice == null))

// Distinct owners other than me — drives the compare picker.
const otherOwners = computed(() =>
  [...new Set(trackingItems.value.map(i => i.owner))].filter(o => o && o !== me.value).sort()
)

// Keep the compare target valid: if the selected owner disappears (e.g. their
// last item is removed), fall back to the first available owner, or leave
// compare mode entirely when nobody else remains.
watch(otherOwners, (owners) => {
  if (!owners.includes(compareWith.value ?? '')) {
    compareWith.value = owners[0] ?? null
    if (owners.length === 0) mode.value = 'list'
  }
})

onMounted(async () => {
  try {
    items.value = await api.getWatchlistItems()
    // Desktop: auto-select the first item so the right pane is populated.
    // Mobile (drill-in): keep the list as the entry point — no auto-select.
    if (trackingItems.value.length > 0 && !smAndDown.value) selectedId.value = trackingItems.value[0].id
    if (otherOwners.value.length > 0) compareWith.value = otherOwners.value[0]
  } finally {
    loading.value = false
  }
})
function isMine(item: WatchlistItem): boolean { return item.owner === me.value }

const selectedItem = computed(() =>
  items.value.find(i => i.id === selectedId.value) ?? null
)

const counts = computed(() => ({
  all: trackingItems.value.length,
  alerts: trackingItems.value.filter(i => i.alerts.length > 0).length,
}))

const filters = computed(() => [
  { key: 'all' as const, label: t('watchlist.filter.all', { n: counts.value.all }) },
  { key: 'alerts' as const, label: t('watchlist.filter.alerts', { n: counts.value.alerts }) },
])

const filteredItems = computed(() =>
  trackingItems.value
    .filter(item => {
      if (activeFilter.value === 'alerts') return item.alerts.length > 0
      return true
    })
    .filter(item => {
      if (!searchQuery.value) return true
      const q = searchQuery.value.toLowerCase()
      return item.ticker.toLowerCase().includes(q) || item.companyName.toLowerCase().includes(q)
    })
)

// Own items unlabelled first, then foreign owners grouped under a "von
// <email>" separator — keeps the row itself free of owner e-mail noise.
const groupedItems = computed(() => groupByOwner(filteredItems.value, me.value))

// The uppercased search query if it is a valid ticker NOT already on the
// watchlist; otherwise null. Used to offer an "add this ticker" CTA when the
// filter is empty. Returns null when the ticker exists (even if hidden by the
// active filter tab) so we never claim a held/tracked ticker is missing.
const addableSymbol = computed<string | null>(() => {
  const q = searchQuery.value.trim().toUpperCase()
  if (!TICKER_RE.test(q)) return null
  if (items.value.some(i => i.ticker.toUpperCase() === q)) return null
  return q
})

function dotClass(status: WatchlistStatus): 'positive' | 'warning' | 'danger' {
  if (status === 'calm') return 'positive'
  if (status === 'elevated') return 'warning'
  return 'danger'
}

// ── Add / delete (preserved real features) ──
const addOpen = ref(false)
const addSymbol = ref('')
const addSubmitting = ref(false)
const addError = ref<string | null>(null)

const rowBusyId = ref<string | null>(null)

function openAddDialog() {
  addSymbol.value = ''
  addError.value = null
  addOpen.value = true
}

async function onAddSymbol() {
  if (!TICKER_RE.test(addSymbol.value)) return
  addSubmitting.value = true
  addError.value = null
  try {
    const created = await api.createWatchlistItem({ symbol: addSymbol.value, tag: 'TRACKING' })
    items.value = [created, ...items.value.filter(i => i.id !== created.id)]
    toast.show(t('watchlist.toast.added', { symbol: created.ticker }))
    addOpen.value = false
    addSymbol.value = ''
  } catch (e) {
    addError.value = e instanceof ApiError && (e.status === 404 || e.status === 422)
      ? t('watchlist.dialog.notFound', { symbol: addSymbol.value })
      : (e as Error).message
  } finally {
    addSubmitting.value = false
  }
}

function onAddFromSearch() {
  if (!addableSymbol.value) return
  addSymbol.value = addableSymbol.value
  addError.value = null
  addOpen.value = true
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
/* ── Mode toggle + owner picker toolbar ── */
.watch-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  gap: var(--space-4); flex-wrap: wrap;
  padding: var(--space-3) var(--space-5);
  border-bottom: var(--hairline);
}
.watch-mode { display: inline-flex; gap: var(--space-1); }
.watch-mode-btn {
  font-size: var(--text-body-sm); color: var(--ash-gray);
  background: none; border: 1px solid transparent; border-radius: 4px;
  padding: var(--space-2) var(--space-4); cursor: pointer;
  transition: color var(--transition-fast), background var(--transition-fast);
}
.watch-mode-btn:hover:not([disabled]) { color: var(--bone-ivory-dim); }
.watch-mode-btn.active { color: var(--bone-ivory); background: rgba(161, 29, 44, 0.12); }
.watch-mode-btn[disabled] { opacity: 0.4; cursor: not-allowed; }
.watch-vs { display: inline-flex; align-items: center; gap: var(--space-2); font-size: var(--text-body-sm); color: var(--ash-gray); }
.watch-vs-k { color: var(--bone-ivory-dim); }
.watch-vs-select {
  background: var(--crypt-black-deep);
  border: 1px solid rgba(184, 148, 92, 0.25);
  border-radius: 4px; color: var(--bone-ivory);
  padding: var(--space-1) var(--space-2); font-size: var(--text-body-sm);
}

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
.watch-owner-sep {
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--ash-gray);
  padding: var(--space-4) var(--space-3) var(--space-2);
  border-bottom: 1px solid var(--rule);
}
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
  font-size: var(--text-body-sm);
  line-height: 1;
  /* >=44x44 hit area without growing the visual row */
  min-width: 44px;
  min-height: 44px;
  display: grid;
  place-items: center;
  padding: var(--space-2);
  margin: calc(var(--space-2) * -1) calc(var(--space-2) * -1) calc(var(--space-2) * -1) 0;
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
