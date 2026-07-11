<template>
  <div class="alert-item" :class="`sev-${tone}`">
    <div class="ai-head">
      <span class="ai-time mono">{{ displayTime }}</span>
      <span class="ai-tag" :class="`ai-tag--${tone}`">
        <i class="ph" :class="icon" aria-hidden="true" />
        {{ label }}
      </span>
    </div>
    <span class="ai-text">{{ alert.message }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { WatchlistAlert } from '../../api/types'
import { formatRelativeTime } from '../../utils/time'
import { alertTone } from '../../utils/alertTone'
import { useEnumLabels } from '../../composables/useEnumLabels'

const props = defineProps<{ alert: WatchlistAlert }>()
const { t, locale } = useI18n()
const { severityLabel } = useEnumLabels()

const displayTime = computed(() => formatRelativeTime(props.alert.at, locale.value))
const tone = computed(() => alertTone(props.alert.severity, props.alert.level))

const icon = computed(() => {
  switch (tone.value) {
    case 'critical': return 'ph-warning-octagon'
    case 'warning': return 'ph-warning'
    case 'info': return 'ph-info'
    default: return 'ph-circle'
  }
})

// Precise severity label when the row carries one; coarse level label otherwise.
const label = computed(() => props.alert.severity
  ? severityLabel(props.alert.severity.toUpperCase())
  : t(`watchlist.alertLevel.${props.alert.level === 'elevated' ? 'elevated' : props.alert.level === 'info' ? 'info' : 'neutral'}`))
</script>

<style scoped>
.alert-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border-left: 2px solid var(--ash-gray);
  background: var(--crypt-black-elevated);
  border-radius: 0 4px 4px 0;
}
/* Severity tokens: INFO gray, WARN gold, CRITICAL crimson (design system). */
.alert-item.sev-critical { border-left-color: var(--blood-crimson); }
.alert-item.sev-warning { border-left-color: var(--cathedral-gold); }
.alert-item.sev-info { border-left-color: var(--ash-gray-light); }
.alert-item.sev-neutral { border-left-color: var(--ash-gray); }
/* Date and severity label share the top row; text stacks below (mobile-friendly). */
.ai-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
.ai-time { font-size: var(--text-micro); color: var(--ash-gray); }
.ai-tag {
  display: inline-flex; align-items: center; gap: var(--space-1);
  font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.06em;
  color: var(--ash-gray);
}
.ai-tag--critical { color: var(--blood-crimson-bright); }
.ai-tag--warning { color: var(--cathedral-gold); }
.ai-tag--info { color: var(--ash-gray-light); }
.ai-text { font-size: var(--text-body-sm); color: var(--bone-ivory); line-height: 1.5; text-wrap: pretty; }
</style>
