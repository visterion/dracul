<template>
  <div class="wcmp" data-testid="watchlist-compare">
    <!-- BEIDE -->
    <SectionHeader :label="t('watchlist.compare.bucketBoth', { n: result.both.length })" />
    <div v-if="result.both.length === 0" class="empty small">
      <div class="em-text">{{ t('watchlist.compare.noOverlap') }}</div>
    </div>
    <div
      v-for="row in result.both"
      :key="row.ticker"
      class="wcmp-shared"
      :data-testid="`compare-both-${row.ticker}`"
    >
      <div class="wcmp-shared-head">
        <span class="wcmp-ticker mono">{{ row.ticker }}</span>
        <span v-if="displayName(row.ticker, row.companyName)" class="wcmp-name">{{ displayName(row.ticker, row.companyName) }}</span>
        <span class="wcmp-px mono"><MoneyDisplay :amount="row.currentPrice" :currency="row.mine.currency" :native-amount="row.mine.nativeCurrentPrice" :native-currency="row.mine.nativeCurrency" /></span>
        <span class="wcmp-chg mono" :class="row.dayChangePercent >= 0 ? 'pos' : 'neg'">
          {{ formatPercent(row.dayChangePercent) }}
        </span>
      </div>
      <div class="wcmp-side">
        <span class="wcmp-who mono">{{ t('watchlist.compare.me') }}</span>
        <span class="wcmp-dot" :class="`dot-${dotClass(row.mine.status)}`" />
        <span class="wcmp-pos">{{ posLabel(row.mine) }}</span>
        <span class="wcmp-alerts mono">{{ alertsLabel(row.mine) }}</span>
      </div>
      <div class="wcmp-side">
        <span class="wcmp-who mono">{{ shortOwner }}</span>
        <span class="wcmp-dot" :class="`dot-${dotClass(row.theirs.status)}`" />
        <span class="wcmp-pos">{{ posLabel(row.theirs) }}</span>
        <span class="wcmp-alerts mono">{{ alertsLabel(row.theirs) }}</span>
      </div>
    </div>

    <!-- NUR ICH -->
    <SectionHeader :label="t('watchlist.compare.bucketOnlyMine', { n: result.onlyMine.length })" />
    <div
      v-for="item in result.onlyMine"
      :key="item.id"
      class="wcmp-solo"
      :data-testid="`compare-mine-${item.ticker}`"
    >
      <span class="wcmp-ticker mono">{{ item.ticker }}</span>
      <span v-if="displayName(item.ticker, item.companyName)" class="wcmp-name">{{ displayName(item.ticker, item.companyName) }}</span>
      <span class="wcmp-px mono"><MoneyDisplay :amount="item.currentPrice" :currency="item.currency" :native-amount="item.nativeCurrentPrice" :native-currency="item.nativeCurrency" /></span>
      <span class="wcmp-dot" :class="`dot-${dotClass(item.status)}`" />
      <span class="wcmp-pos">{{ posLabel(item) }}</span>
    </div>

    <!-- NUR <user> -->
    <SectionHeader :label="t('watchlist.compare.bucketOnlyTheirs', { owner: shortOwner, n: result.onlyTheirs.length })" />
    <div
      v-for="item in result.onlyTheirs"
      :key="item.id"
      class="wcmp-solo"
      :data-testid="`compare-theirs-${item.ticker}`"
    >
      <span class="wcmp-ticker mono">{{ item.ticker }}</span>
      <span v-if="displayName(item.ticker, item.companyName)" class="wcmp-name">{{ displayName(item.ticker, item.companyName) }}</span>
      <span class="wcmp-px mono"><MoneyDisplay :amount="item.currentPrice" :currency="item.currency" :native-amount="item.nativeCurrentPrice" :native-currency="item.nativeCurrency" /></span>
      <span class="wcmp-dot" :class="`dot-${dotClass(item.status)}`" />
      <span class="wcmp-pos">{{ posLabel(item) }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import SectionHeader from '../common/SectionHeader.vue'
import MoneyDisplay from '../common/MoneyDisplay.vue'
import { buildComparison } from '../../lib/watchlistComparison'
import type { WatchlistItem, WatchlistStatus } from '../../api/types'
import { formatPercent } from '../../utils/format'
import { displayName } from '../../utils/instrument'

const props = defineProps<{
  items: WatchlistItem[]
  me: string
  compareWith: string
}>()

const { t } = useI18n()

const result = computed(() => buildComparison(props.items, props.me, props.compareWith))

// Display just the local-part of the email for compactness (bob@x → bob).
const shortOwner = computed(() => props.compareWith.split('@')[0])

function dotClass(status: WatchlistStatus): 'positive' | 'warning' | 'danger' {
  if (status === 'calm') return 'positive'
  if (status === 'elevated') return 'warning'
  return 'danger'
}

function posLabel(_item: WatchlistItem): string {
  return t('watchlist.compare.tracking')
}

function alertsLabel(item: WatchlistItem): string {
  return item.alerts.length > 0
    ? t('watchlist.compare.alertsCount', { n: item.alerts.length })
    : t('watchlist.compare.noAlerts')
}
</script>

<style scoped>
.wcmp { padding: var(--space-6) var(--space-8); overflow-y: auto; height: 100%; }
.wcmp-shared {
  border: var(--hairline);
  border-radius: 4px;
  padding: var(--space-3) var(--space-4);
  margin-bottom: var(--space-3);
  background: var(--crypt-black-elevated);
}
.wcmp-shared-head {
  display: flex; align-items: baseline; gap: var(--space-3);
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--rule);
  margin-bottom: var(--space-2);
}
.wcmp-side {
  display: flex; align-items: center; gap: var(--space-3);
  padding: 2px 0;
}
.wcmp-solo {
  display: flex; align-items: center; gap: var(--space-3);
  padding: var(--space-2) var(--space-1);
  border-bottom: 1px solid var(--rule);
}
.wcmp-ticker { font-size: var(--text-body); color: var(--bone-ivory); }
.wcmp-name { font-size: var(--text-micro); color: var(--ash-gray); flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wcmp-px { font-size: var(--text-body-sm); color: var(--bone-ivory); }
.wcmp-chg { font-size: var(--text-micro); }
.wcmp-who { font-size: 11px; color: var(--cathedral-gold); width: 56px; }
.wcmp-dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.wcmp-pos { font-size: var(--text-body-sm); color: var(--bone-ivory-dim); flex: 1; }
.wcmp-alerts { font-size: var(--text-micro); color: var(--ash-gray); }
.dot-positive { background: var(--signal-positive); }
.dot-warning { background: var(--cathedral-gold); }
.dot-danger { background: var(--blood-crimson); box-shadow: 0 0 4px var(--blood-crimson); }
.pos { color: var(--signal-positive); }
.neg { color: var(--blood-crimson); }
</style>
