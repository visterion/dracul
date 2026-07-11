<template>
  <div
    class="pf-row" :class="{ clickable: !!signal }"
    data-testid="portfolio-row" :data-symbol="item.ticker"
    @click="signal && emit('open', signal.id)"
  >
    <TagPill :tone="badgeTone" class="pf-row__badge">{{ signal ? signal.action : t('portfolio.noSignal') }}</TagPill>
    <div class="pf-row__main">
      <span class="pf-row__sym mono">{{ item.ticker }}</span>
      <span class="pf-row__name">{{ item.companyName }}</span>
    </div>
    <div class="pf-row__nums mono">
      <span>{{ t('portfolio.cols.entry') }} <MoneyDisplay :amount="item.entryPrice" :currency="item.currency" :native-amount="item.nativeEntryPrice" :native-currency="item.entryCurrency" /></span>
      <span>{{ t('portfolio.cols.size') }} {{ fmt(item.shareCount) }}</span>
      <span>{{ t('portfolio.cols.current') }} <MoneyDisplay :amount="item.currentPrice" :currency="item.currency" :native-amount="item.nativeCurrentPrice" :native-currency="item.nativeCurrency" /></span>
      <span class="pf-row__pnl" :class="pnlPct >= 0 ? 'pos' : 'neg'">{{ formatPercent(pnlPct) }}</span>
    </div>
    <span v-if="visibleThesisStatus(signal)" class="pf-row__thesis mono">{{ visibleThesisStatus(signal) }}</span>
    <div class="pf-row__actions" @click.stop>
      <button class="pf-row__btn" :data-testid="`pf-edit-${item.ticker}`" aria-label="edit" @click="emit('edit', item)">
        <i class="ph ph-pencil-simple" aria-hidden="true" />
      </button>
      <button class="pf-row__btn" :data-testid="`pf-delete-${item.ticker}`" aria-label="delete" @click="emit('delete', item)">
        <i class="ph ph-trash" aria-hidden="true" />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import TagPill from '../common/TagPill.vue'
import MoneyDisplay from '../common/MoneyDisplay.vue'
import type { WatchlistItem, ExitSignal } from '../../api/types'
import { formatNumber, formatPercent } from '../../utils/format'
import { visibleThesisStatus } from '../../lib/portfolioDisplay'

const props = defineProps<{ item: WatchlistItem; signal: ExitSignal | null }>()
const emit = defineEmits<{ open: [string]; edit: [WatchlistItem]; delete: [WatchlistItem] }>()
const { t } = useI18n()

const pnlPct = computed(() => {
  const e = props.item.entryPrice
  if (!e) return 0
  return ((props.item.currentPrice - e) / e) * 100
})
const badgeTone = computed<'gold' | 'crimson' | 'green' | 'ash'>(() => {
  switch (props.signal?.action) {
    case 'SELL': return 'crimson'
    case 'TRIM': return 'gold'
    default: return 'ash'
  }
})
function fmt(n: number | null): string {
  return n == null ? '—' : formatNumber(n, Number.isInteger(n) ? 0 : 4)
}
</script>

<style scoped>
.pf-row {
  display: grid;
  grid-template-columns: auto minmax(8rem, auto) auto 1fr auto;
  grid-template-areas: "badge main signal nums actions";
  align-items: center;
  gap: var(--space-3) var(--space-4);
  padding: var(--space-2) var(--space-4);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 4px;
  background-color: var(--crypt-black-elevated);
}
.pf-row.clickable { cursor: pointer; }
.pf-row.clickable:hover { border-color: rgba(184,148,92,0.3); }
.pf-row__badge { grid-area: badge; }
.pf-row__main { grid-area: main; display: flex; flex-direction: column; min-width: 0; }
.pf-row__sym { color: var(--bone-ivory); }
.pf-row__name { color: var(--ash-gray); font-size: var(--text-body-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pf-row__thesis { grid-area: signal; color: var(--ash-gray); font-size: var(--text-micro); }
.pf-row__nums { grid-area: nums; display: flex; gap: var(--space-4); justify-content: flex-end; color: var(--ash-gray); font-size: var(--text-body-sm); flex-wrap: wrap; }
.pf-row__pnl.pos { color: var(--cathedral-gold); }
.pf-row__pnl.neg { color: var(--blood-crimson); }
.pf-row__actions { grid-area: actions; display: flex; flex: 0 0 auto; }
/* >=44x44 hit area, icon size unchanged; negative margin keeps the visual row height. */
.pf-row__btn {
  background: transparent; border: none; color: var(--ash-gray); cursor: pointer;
  min-width: 44px; min-height: 44px; display: grid; place-items: center;
  padding: var(--space-2); margin: calc(var(--space-2) * -1) 0;
}
.pf-row__btn:hover { color: var(--bone-ivory); }

/* Mobile: fixed 2-row layout — badge|main|actions on top, signal+numbers below. */
@media (max-width: 959.98px) {
  .pf-row {
    grid-template-columns: auto 1fr auto;
    grid-template-areas:
      "badge main actions"
      "signal nums nums";
    row-gap: var(--space-1);
  }
  .pf-row__nums { justify-content: flex-start; }
}
</style>
