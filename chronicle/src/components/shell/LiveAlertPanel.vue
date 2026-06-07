<template>
  <aside v-if="open" class="live-panel" data-testid="live-alert-panel">
    <header class="live-panel__head">
      <span class="live-panel__title">{{ t('app.liveAlerts.title') }}</span>
      <span
        class="live-panel__status"
        :class="`live-panel__status--${store.status}`"
        data-testid="live-indicator"
      >{{ store.status }}</span>
      <button class="live-panel__close" aria-label="Close" @click="$emit('close')">✕</button>
    </header>

    <ul v-if="store.alerts.length" class="live-panel__list">
      <li
        v-for="(a, i) in store.alerts"
        :key="`${a.symbol}-${a.ts}-${i}`"
        class="live-panel__item"
        data-testid="live-alert-item"
      >
        <div class="live-panel__row">
          <span class="live-panel__sev" :class="`live-panel__sev--${a.severity.toLowerCase()}`">
            {{ a.severity }}
          </span>
          <span class="live-panel__symbol">{{ a.symbol }}</span>
          <span class="live-panel__trigger">{{ a.triggerType }}</span>
        </div>
        <p class="live-panel__thesis">{{ a.thesis }}</p>
      </li>
    </ul>
    <p v-else class="live-panel__empty">{{ t('app.liveAlerts.empty') }}</p>
  </aside>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useLiveAlertsStore } from '../../stores/liveAlerts'

defineProps<{ open: boolean }>()
defineEmits<{ close: [] }>()

const { t } = useI18n()
const store = useLiveAlertsStore()
</script>

<style scoped>
.live-panel {
  position: fixed;
  top: 64px;
  right: 0;
  width: 360px;
  max-height: calc(100vh - 64px);
  overflow-y: auto;
  background: var(--crypt-black-elevated);
  border-left: 1px solid rgba(184, 148, 92, 0.15);
  z-index: 200;
  padding: var(--space-4);
}
.live-panel__head { display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-3); }
.live-panel__title { font-family: var(--font-display); color: var(--bone-ivory); flex: 1; }
.live-panel__status { font-size: 11px; text-transform: uppercase; }
.live-panel__status--open { color: var(--signal-positive); }
.live-panel__status--connecting { color: var(--cathedral-gold); }
.live-panel__status--closed { color: var(--ash-gray); }
.live-panel__close { background: none; border: none; color: var(--ash-gray); cursor: pointer; font-size: 14px; }
.live-panel__list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-2); }
.live-panel__item { border: 1px solid rgba(184, 148, 92, 0.1); border-radius: 4px; padding: var(--space-2); }
.live-panel__row { display: flex; align-items: center; gap: var(--space-2); }
.live-panel__sev { font-size: 10px; font-weight: 600; text-transform: uppercase; }
.live-panel__sev--critical { color: var(--blood-crimson-bright); }
.live-panel__sev--warning { color: var(--cathedral-gold); }
.live-panel__sev--info { color: var(--signal-positive); }
.live-panel__symbol { color: var(--bone-ivory); font-weight: 600; }
.live-panel__trigger { color: var(--ash-gray); font-size: 12px; }
.live-panel__thesis { color: var(--bone-ivory-dim); font-size: 12px; margin: var(--space-1) 0 0; }
.live-panel__empty { color: var(--ash-gray); font-size: 13px; }
</style>
