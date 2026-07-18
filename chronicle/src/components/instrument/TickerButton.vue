<template>
  <button
    type="button"
    class="ticker-btn"
    :aria-label="t('instrument.openInfo', { symbol })"
    @click.stop="() => store.open(symbol)"
    @keydown.enter.stop.prevent="() => store.open(symbol)"
    @keydown.space.stop.prevent="() => store.open(symbol)"
  >{{ symbol }}</button>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useInstrumentOverlayStore } from '../../stores/instrumentOverlay'

defineProps<{ symbol: string }>()
const { t } = useI18n()
const store = useInstrumentOverlayStore()
</script>

<style scoped>
/* Inline text-like button: inherit the caller's font/color (the passed class
   like .vc-ticker/.mono still applies via the parent scope); add affordance. */
.ticker-btn {
  background: none; border: none; padding: 0; margin: 0;
  font: inherit; color: inherit; cursor: pointer; letter-spacing: inherit;
}
.ticker-btn:hover { text-decoration: underline; }
.ticker-btn:focus-visible { outline: 2px solid var(--cathedral-gold); outline-offset: 2px; }
</style>
