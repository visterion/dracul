<template>
  <div class="watchlist">
    <div class="watchlist__left">
      <input
        v-model="searchQuery"
        class="watchlist__search"
        type="text"
        placeholder="search by ticker or company…"
      />

      <div class="watchlist__chips">
        <button
          v-for="f in filters"
          :key="f.key"
          class="watchlist__chip"
          :class="{ 'watchlist__chip--active': activeFilter === f.key }"
          @click="activeFilter = f.key"
        >
          {{ f.label }}
        </button>
      </div>

      <button class="watchlist__add" @click="() => {}">+ Add to watchlist</button>

      <template v-if="loading">
        <v-skeleton-loader
          v-for="i in 5"
          :key="i"
          type="list-item-two-line"
          class="watchlist__skeleton"
        />
      </template>

      <div v-else-if="filteredItems.length === 0" class="watchlist__empty">
        No instruments match this filter.
      </div>

      <div v-else class="watchlist__list">
        <div
          v-for="item in filteredItems"
          :key="item.id"
          class="watchlist__item"
          :class="{ 'watchlist__item--selected': selectedId === item.id }"
          data-testid="watchlist-item"
          @click="selectedId = item.id"
        >
          <div class="watchlist__item-top">
            <div>
              <div class="watchlist__ticker">{{ item.ticker }}</div>
              <div class="watchlist__company">{{ item.companyName }}</div>
            </div>
            <div class="watchlist__price-col">
              <div class="watchlist__price">${{ item.currentPrice.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}</div>
              <div
                class="watchlist__change"
                :class="item.dayChangePercent >= 0 ? 'watchlist__change--pos' : 'watchlist__change--neg'"
              >
                {{ item.dayChangePercent >= 0 ? '+' : '' }}{{ item.dayChangePercent.toFixed(1) }}%
              </div>
            </div>
          </div>
          <div class="watchlist__item-bottom">
            <span class="watchlist__meta">added {{ daysAgo(item.addedAt) }} · {{ item.tag === 'HELD' ? 'held position' : 'tracking verdict' }}</span>
            <span class="watchlist__dot" :class="`watchlist__dot--${item.status}`" />
          </div>
        </div>
      </div>
    </div>

    <div class="watchlist__right">
      <template v-if="loading">
        <v-skeleton-loader type="heading" />
        <v-skeleton-loader type="paragraph" class="mt-4" />
      </template>

      <template v-else-if="selectedItem">
        <div class="watchlist__detail-header">
          <div class="watchlist__detail-ticker">{{ selectedItem.ticker }}</div>
          <div class="watchlist__detail-company">{{ selectedItem.companyName }}</div>
          <div class="watchlist__detail-subtitle">
            {{ selectedItem.verdictId ? `tracking verdict from ${formatDate(selectedItem.addedAt)}` : `held position since ${formatDate(selectedItem.addedAt)}` }}
          </div>
        </div>

        <div class="watchlist__position-card">
          <template v-if="selectedItem.tag === 'TRACKING'">
            <span class="watchlist__position-empty">Not held — only tracking verdict</span>
            <a class="watchlist__crimson-link" href="#">Mark as Held →</a>
          </template>
          <template v-else>
            <span class="watchlist__position-empty">Entry price —  ·  Size —  ·  P&amp;L —</span>
          </template>
        </div>

        <div class="watchlist__section-header">── recent daywalker alerts</div>

        <div v-if="selectedItem.alerts.length === 0" class="watchlist__no-alerts">
          No alerts yet.
        </div>
        <div v-else>
          <div
            v-for="alert in selectedItem.alerts.slice(0, 5)"
            :key="alert.id"
            class="watchlist__alert-row"
          >
            <span class="watchlist__alert-time">{{ alert.at }}</span>
            <div>
              <div class="watchlist__alert-msg">{{ alert.message }}</div>
              <div
                class="watchlist__alert-level"
                :class="`watchlist__alert-level--${alert.level}`"
              >
                {{ alert.level === 'elevated' ? '⚠ elevated' : alert.level === 'info' ? 'ℹ info' : '— neutral' }}
              </div>
            </div>
          </div>
        </div>

        <div class="watchlist__section-header">── price (30d)</div>
        <div class="watchlist__chart">
          <apexchart
            type="area"
            :height="80"
            :options="sparklineOptions"
            :series="[{ data: selectedItem.priceHistory30d }]"
          />
        </div>

        <template v-if="selectedItem.verdictId">
          <div class="watchlist__section-header">── linked verdicts</div>
          <div class="watchlist__verdict-card">
            <div class="watchlist__verdict-ticker">{{ selectedItem.ticker }} · Verdict #{{ selectedItem.verdictId }}</div>
            <div class="watchlist__verdict-meta">tracking verdict · consensus 0.84</div>
            <router-link
              :to="{ name: 'verdict-detail', params: { id: selectedItem.verdictId } }"
              class="watchlist__crimson-link"
            >Read full verdict →</router-link>
          </div>
        </template>

        <div class="watchlist__section-header">── actions</div>
        <button class="watchlist__ghost-btn" @click="() => {}">Remove from Watchlist</button>
        <button class="watchlist__ghost-btn" @click="() => {}">Pause Daywalker for this item</button>
        <button class="watchlist__ghost-btn" @click="() => {}">Edit Notes</button>
      </template>

      <div v-else class="watchlist__no-selection">Select an item from the list.</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import VueApexCharts from 'vue3-apexcharts'
import { useApi } from '../api'
import type { WatchlistItem } from '../api/types'

const apexchart = VueApexCharts

const api = useApi()
const items = ref<WatchlistItem[]>([])
const loading = ref(true)
const selectedId = ref<string | null>(null)
const searchQuery = ref('')
const activeFilter = ref<'all' | 'held' | 'tracking' | 'alerts'>('all')

onMounted(async () => {
  try {
    items.value = await api.getWatchlistItems()
    if (items.value.length > 0) selectedId.value = items.value[0].id
  } finally {
    loading.value = false
  }
})

const selectedItem = computed(() =>
  items.value.find(i => i.id === selectedId.value) ?? null
)

const counts = computed(() => ({
  all: items.value.length,
  held: items.value.filter(i => i.tag === 'HELD').length,
  tracking: items.value.filter(i => i.tag === 'TRACKING').length,
  alerts: items.value.filter(i => i.alerts.length > 0).length,
}))

const filters = computed(() => [
  { key: 'all' as const, label: `All (${counts.value.all})` },
  { key: 'held' as const, label: `Held Positions (${counts.value.held})` },
  { key: 'tracking' as const, label: `Tracking Verdicts (${counts.value.tracking})` },
  { key: 'alerts' as const, label: `Recent Alerts (${counts.value.alerts})` },
])

const filteredItems = computed(() =>
  items.value
    .filter(item => {
      if (activeFilter.value === 'held') return item.tag === 'HELD'
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

const sparklineOptions = {
  chart: {
    type: 'area' as const,
    sparkline: { enabled: true },
    background: 'transparent',
    animations: { enabled: false },
  },
  stroke: { curve: 'smooth' as const, width: 1.5, colors: ['#A11D2C'] },
  fill: {
    type: 'gradient',
    gradient: {
      shadeIntensity: 1,
      colorStops: [
        { offset: 0, color: '#A11D2C', opacity: 0.15 },
        { offset: 100, color: '#A11D2C', opacity: 0 },
      ],
    },
  },
  tooltip: { enabled: false },
}

function daysAgo(isoDate: string): string {
  const diffMs = Date.now() - new Date(isoDate).getTime()
  const days = Math.floor(diffMs / 86_400_000)
  if (days === 0) return 'today'
  if (days === 1) return 'yesterday'
  return `${days} days ago`
}

function formatDate(isoDate: string): string {
  return new Date(isoDate).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' })
}
</script>

<style scoped>
.watchlist {
  display: grid;
  grid-template-columns: 60% 40%;
  height: calc(100vh - 96px); /* topbar 64px + statusbar 32px */
  overflow: hidden;
}

.watchlist__left {
  border-right: 1px solid rgba(255, 255, 255, 0.05);
  overflow-y: auto;
  padding: 20px 24px;
}

.watchlist__right {
  overflow-y: auto;
  padding: 20px 24px;
}

.watchlist__search {
  width: 100%;
  padding: 8px 12px;
  background: var(--crypt-black-deep);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: var(--bone-ivory);
  font-family: var(--font-body);
  font-size: 13px;
  border-radius: 2px;
  outline: none;
  margin-bottom: 12px;
}
.watchlist__search:focus { border-color: var(--cathedral-gold); }
.watchlist__search::placeholder { color: var(--ash-gray); }

.watchlist__chips { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 12px; }
.watchlist__chip {
  padding: 4px 10px;
  border-radius: 2px;
  font-size: 11px;
  letter-spacing: 0.03em;
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: var(--ash-gray);
  cursor: pointer;
  background: none;
  font-family: var(--font-body);
  transition: border-color 0.1s, color 0.1s;
}
.watchlist__chip--active { border-color: var(--cathedral-gold); color: var(--cathedral-gold); }
.watchlist__chip:hover:not(.watchlist__chip--active) { color: var(--bone-ivory-dim); }

.watchlist__add {
  display: block;
  margin-left: auto;
  margin-bottom: 16px;
  padding: 6px 14px;
  background: var(--blood-crimson);
  border: none;
  border-radius: 2px;
  color: var(--bone-ivory);
  font-family: var(--font-body);
  font-size: 12px;
  cursor: pointer;
}
.watchlist__add:hover { background: var(--blood-crimson-bright); }

.watchlist__skeleton { margin-bottom: 8px; }

.watchlist__empty {
  padding: 24px;
  text-align: center;
  color: var(--ash-gray);
  font-style: italic;
  font-size: 13px;
}

.watchlist__item {
  padding: 14px 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.04);
  border-left: 2px solid transparent;
  cursor: pointer;
  transition: background 0.1s;
}
.watchlist__item:hover { background: rgba(184, 148, 92, 0.03); }
.watchlist__item--selected {
  background: rgba(161, 29, 44, 0.06);
  border-left-color: var(--blood-crimson);
}

.watchlist__item-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 6px;
}
.watchlist__ticker { font-family: var(--font-mono); font-size: 14px; font-weight: 500; }
.watchlist__company { font-size: 12px; color: var(--bone-ivory-dim); margin-top: 1px; }
.watchlist__price-col { text-align: right; }
.watchlist__price { font-family: var(--font-mono); font-size: 13px; font-feature-settings: "tnum"; }
.watchlist__change { font-family: var(--font-mono); font-size: 11px; margin-top: 2px; font-feature-settings: "tnum"; }
.watchlist__change--pos { color: var(--signal-positive); }
.watchlist__change--neg { color: var(--blood-crimson); }

.watchlist__item-bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.watchlist__meta { font-size: 11px; color: var(--ash-gray); }
.watchlist__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.watchlist__dot--calm { background: var(--signal-positive); }
.watchlist__dot--elevated { background: var(--cathedral-gold); }
.watchlist__dot--alert { background: var(--blood-crimson); box-shadow: 0 0 4px var(--blood-crimson); }

/* Right pane */
.watchlist__detail-header { margin-bottom: 16px; }
.watchlist__detail-ticker { font-family: var(--font-mono); font-size: 22px; font-weight: 500; }
.watchlist__detail-company { font-size: 14px; color: var(--bone-ivory-dim); margin-top: 2px; }
.watchlist__detail-subtitle { font-size: 12px; color: var(--ash-gray); margin-top: 4px; }

.watchlist__position-card {
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 2px;
  margin-bottom: 4px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.watchlist__position-empty { font-size: 12px; color: var(--ash-gray); font-style: italic; }

.watchlist__crimson-link {
  font-size: 11px;
  color: var(--blood-crimson);
  text-decoration: none;
}
.watchlist__crimson-link:hover { color: var(--blood-crimson-bright); }

.watchlist__section-header {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ash-gray);
  letter-spacing: 0.05em;
  margin: 20px 0 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.watchlist__section-header::after {
  content: '';
  flex: 1;
  height: 1px;
  background: rgba(255, 255, 255, 0.06);
}

.watchlist__no-alerts {
  font-size: 12px;
  color: var(--ash-gray);
  font-style: italic;
}

.watchlist__alert-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 8px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.04);
}
.watchlist__alert-time {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ash-gray);
  min-width: 90px;
  flex-shrink: 0;
}
.watchlist__alert-msg { font-size: 12px; color: var(--bone-ivory-dim); }
.watchlist__alert-level { font-size: 11px; margin-top: 2px; }
.watchlist__alert-level--elevated { color: var(--cathedral-gold); }
.watchlist__alert-level--info { color: var(--signal-positive); }
.watchlist__alert-level--neutral { color: var(--ash-gray); }

.watchlist__chart {
  background: var(--crypt-black-elevated);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 2px;
  margin-bottom: 4px;
  overflow: hidden;
}

.watchlist__verdict-card {
  border: 1px solid rgba(184, 148, 92, 0.15);
  border-radius: 2px;
  padding: 10px 12px;
  margin-bottom: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.watchlist__verdict-ticker {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--cathedral-gold);
}
.watchlist__verdict-meta { font-size: 11px; color: var(--ash-gray); }

.watchlist__ghost-btn {
  display: block;
  width: 100%;
  padding: 7px 0;
  background: none;
  border: none;
  color: var(--bone-ivory-dim);
  font-family: var(--font-body);
  font-size: 12px;
  cursor: pointer;
  text-align: left;
}
.watchlist__ghost-btn:hover { color: var(--bone-ivory); }

.watchlist__no-selection {
  padding: 48px 0;
  text-align: center;
  color: var(--ash-gray);
  font-style: italic;
}
</style>
