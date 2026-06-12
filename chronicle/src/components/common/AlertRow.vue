<template>
  <div class="alert-item" :class="`sev-${sev}`">
    <span class="ai-time mono">{{ displayTime }}</span>
    <div class="ai-body">
      <span class="ai-text">{{ alert.message }}</span>
      <span class="ai-tag">
        <i class="ph" :class="icon" aria-hidden="true" />
        {{ levelLabel }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { WatchlistAlert } from '../../api/types'
import { formatRelativeTime } from '../../utils/time'

const props = defineProps<{ alert: WatchlistAlert }>()
const { t, locale } = useI18n()

const displayTime = computed(() => formatRelativeTime(props.alert.at, locale.value))

// Map domain level → severity class (elevated→warning, info→info, neutral→neutral)
const sev = computed(() => {
  switch (props.alert.level) {
    case 'elevated':
      return 'warning'
    case 'info':
      return 'info'
    default:
      return 'neutral'
  }
})

const icon = computed(() => {
  switch (sev.value) {
    case 'warning':
      return 'ph-warning'
    case 'info':
      return 'ph-info'
    default:
      return 'ph-circle'
  }
})

const levelLabel = computed(() => {
  switch (props.alert.level) {
    case 'elevated':
      return t('watchlist.alertLevel.elevated')
    case 'info':
      return t('watchlist.alertLevel.info')
    default:
      return t('watchlist.alertLevel.neutral')
  }
})
</script>

<style scoped>
/* Ported from styles.css:399-407 (.alert-item / .ai-*) */
.alert-item {
  display: grid;
  grid-template-columns: 110px 1fr;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
  border-left: 2px solid var(--ash-gray);
  background: var(--crypt-black-elevated);
  border-radius: 0 4px 4px 0;
}
.alert-item.sev-warning { border-left-color: var(--cathedral-gold); }
.alert-item.sev-danger { border-left-color: var(--blood-crimson); }
.alert-item.sev-info { border-left-color: var(--ash-gray-light); }
.alert-item.sev-neutral { border-left-color: var(--ash-gray); }
.ai-time { font-size: var(--text-micro); color: var(--ash-gray); padding-top: 2px; }
.ai-body { display: flex; flex-direction: column; gap: var(--space-2); }
.ai-text {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory);
  line-height: 1.5;
  text-wrap: pretty;
}
.ai-tag {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--ash-gray);
}
</style>
