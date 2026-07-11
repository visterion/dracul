<template>
  <article class="provider-card">
    <header class="pv-head">
      <span class="pv-name">{{ provider.name }}</span>
      <span class="pv-status" :class="`pv-status--${provider.status}`">
        <span class="pv-dot" :class="`pv-dot--${provider.status}`" />
        {{ statusLabel }}
      </span>
    </header>

    <div class="pv-body mono">
      <div v-if="provider.status === 'local'">
        {{ t('settings.providers.endpoint') }}:
        <span class="pv-val">{{ provider.endpoint }}</span>
      </div>
      <div v-else>
        {{ t('settings.providers.apiKey') }}:
        <span class="pv-val">{{ t('settings.providers.apiKeyConfigured') }} {{ provider.apiKeyMasked }}</span>
        <button type="button" class="pv-link" disabled>{{ t('settings.providers.reveal') }}</button>
      </div>

      <div>
        {{ t('settings.providers.models') }}:
        <span class="pv-val">{{ provider.models.join(', ') }}</span>
      </div>

      <div class="pv-muted">{{ todayLine }}</div>
    </div>

    <div class="pv-actions">
      <button class="btn btn-secondary" type="button">{{ t('settings.providers.edit') }}</button>
      <button class="btn btn-secondary" type="button">{{ t('settings.providers.testConnection') }}</button>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LlmProvider } from '../../api/types'
import { formatNumber } from '../../utils/format'

const props = defineProps<{ provider: LlmProvider }>()
const { t } = useI18n()

const statusLabel = computed(() => {
  switch (props.provider.status) {
    case 'connected': return t('settings.providers.statusConnected')
    case 'fallback':  return t('settings.providers.statusFallback')
    default:          return t('settings.providers.statusRunning')
  }
})

const todayLine = computed(() => {
  const p = props.provider
  if (p.status === 'local') {
    return t('settings.providers.todayLocal', { calls: p.callsToday ?? 0 })
  }
  if (p.todayInputTokens === 0) {
    return t('settings.providers.todayZero')
  }
  return t('settings.providers.todayUsage', {
    input: formatNumber(p.todayInputTokens),
    output: formatNumber(p.todayOutputTokens),
    cost: formatNumber(p.todayCostUsd, 2),
  })
})
</script>

<style scoped>
/* .provider-card / .pv-* are NOT in global.css — scoped here (styles.css:468-477) */
.provider-card {
  background: var(--crypt-black-elevated);
  border: var(--hairline);
  border-radius: 4px;
  padding: var(--space-5);
}
.pv-head { display: flex; align-items: center; gap: var(--space-3); margin-bottom: var(--space-4); }
.pv-name { font-size: var(--text-body-lg); color: var(--bone-ivory); }
.pv-status {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-body-sm);
  color: var(--signal-positive-bright);
}
.pv-status--fallback { color: var(--cathedral-gold); }
.pv-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--signal-positive); }
.pv-dot--fallback { background: var(--cathedral-gold); }
.pv-body {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}
.pv-val { color: var(--bone-ivory); }
.pv-link { color: var(--cathedral-gold); cursor: pointer; margin-left: var(--space-2); background: none; border: none; padding: 0; font: inherit; }
.pv-link:hover { color: var(--cathedral-gold-bright); }
.pv-link:disabled { color: var(--cathedral-gold); opacity: 0.6; cursor: not-allowed; }
.pv-muted { color: var(--ash-gray); }
.pv-actions { display: flex; gap: var(--space-2); }
</style>
