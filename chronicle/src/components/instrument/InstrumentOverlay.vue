<template>
  <v-dialog :model-value="store.openSymbol != null" max-width="720" scrollable @update:model-value="v => { if (!v) store.close() }">
    <div class="io-card" v-if="store.openSymbol">
      <div class="io-head">
        <div class="io-id">
          <h2 class="io-symbol mono" data-testid="io-symbol">{{ store.openSymbol }}</h2>
          <div v-if="header.name" class="io-name" data-testid="io-header-name">{{ header.name }}</div>
        </div>
        <div class="io-price-wrap">
          <span v-if="header.lastPrice != null" class="io-price mono" data-testid="io-header-price">{{ formatNumber(header.lastPrice, 2) }}</span>
          <span v-if="header.change != null" class="io-change" :class="pnlClass(header.change)">{{ formatNumber(header.change, 2) }} ({{ header.changePct != null ? formatNumber(header.changePct, 2) + '%' : '—' }})</span>
        </div>
        <button class="io-close" :aria-label="t('instrument.close')" @click="store.close()">✕</button>
      </div>

      <RouterLink
        v-if="holding"
        :to="{ name: 'depot-position-detail', params: { connection: holding.connection, symbol: store.openSymbol } }"
        class="io-banner"
        data-testid="io-banner"
        @click="store.close()"
      >
        {{ t('instrument.held') }} · {{ holding.position.name ?? store.openSymbol }} →
      </RouterLink>

      <InstrumentInfoPanel :symbol="store.openSymbol" @header="onHeader" />
    </div>
  </v-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import InstrumentInfoPanel from './InstrumentInfoPanel.vue'
import { useInstrumentOverlayStore } from '../../stores/instrumentOverlay'
import { useDepotsStore } from '../../stores/depots'
import { formatNumber } from '../../utils/format'

const { t } = useI18n()
const store = useInstrumentOverlayStore()
const depots = useDepotsStore()

const header = ref<{ name: string; lastPrice: number | null; change: number | null; changePct: number | null }>({ name: '', lastPrice: null, change: null, changePct: null })
function onHeader(h: typeof header.value) { header.value = h }

// Banner is a computed over the CURRENT openSymbol — never an imperative capture,
// so a fast A→close→B reopen can't show A's holding under B.
const holding = computed(() => (store.openSymbol ? depots.findHolding(store.openSymbol) : null))

function pnlClass(v: number | null): string { return v == null ? '' : v > 0 ? 'pos' : v < 0 ? 'neg' : '' }

// On each open: reset header, fire-and-forget refresh of the holdings snapshot.
watch(() => store.openSymbol, sym => {
  if (sym == null) return
  header.value = { name: '', lastPrice: null, change: null, changePct: null }
  void depots.load()
})
</script>

<style scoped>
/* The v-dialog's `scrollable` prop only wires up a scroll container for a
   v-card/v-card-text; this custom card must scroll itself. Without a bounded
   height + overflow the overflowing content is simply clipped by
   .v-overlay__content and cannot be touch-scrolled on iOS. */
.io-card { background: var(--crypt-black); padding: var(--space-5); display: flex; flex-direction: column; gap: var(--space-4); max-height: 88vh; overflow-y: auto; -webkit-overflow-scrolling: touch; overscroll-behavior: contain; }
.io-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-3); position: sticky; top: 0; margin: calc(var(--space-5) * -1) calc(var(--space-5) * -1) 0; padding: var(--space-5) var(--space-5) var(--space-3); background: var(--crypt-black); z-index: 1; }
.io-symbol { color: var(--bone-ivory); font-size: 1.5rem; margin: 0; }
.io-name { color: var(--ash-gray); font-size: var(--text-body-sm); }
.io-price-wrap { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
.io-price { color: var(--bone-ivory); font-size: var(--text-h3); }
.io-change.pos { color: var(--signal-positive-bright); }
.io-change.neg { color: var(--blood-crimson-bright); }
.io-close { background: none; border: none; color: var(--ash-gray); cursor: pointer; font-size: 1.1rem; }
.io-banner { display: block; background: var(--crypt-black-elevated); border: var(--hairline); border-radius: 4px; padding: var(--space-2) var(--space-3); color: var(--cathedral-gold); text-decoration: none; font-size: var(--text-body-sm); }
.io-banner:hover { border-color: var(--cathedral-gold); }
</style>
