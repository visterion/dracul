<template>
  <footer class="status-bar statusbar">
    <span class="sb-item">
      <span aria-hidden="true">☾</span>
      <span class="sb-strong mono">{{ status.strigoiCount }}</span> {{ t('app.status.strigoi') }}
    </span>
    <span class="sb-sep">·</span>
    <span class="sb-item">
      <span class="pulse-dot" aria-hidden="true"></span>
      <span class="sb-strong mono">{{ status.huntingCount }}</span> {{ t('app.status.hunting') }}
    </span>
    <span class="sb-sep">·</span>
    <span class="sb-item">
      <i18n-t v-if="hasVerdict" keypath="app.status.lastVerdict" tag="span">
        <template #time><span class="sb-strong mono">{{ status.lastVerdictRelative }}</span></template>
      </i18n-t>
      <template v-else>{{ t('app.status.lastVerdictNone') }}</template>
    </span>
    <span class="sb-sep">·</span>
    <span class="sb-item">
      <i18n-t keypath="app.status.costToday" tag="span">
        <template #cost><span class="sb-strong mono">${{ cost }}</span></template>
      </i18n-t>
    </span>
    <span class="flex-spacer"></span>
    <span class="sb-item">{{ t('app.status.motto') }}</span>
  </footer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useStatusStore } from '../../stores/status'
import { formatNumber } from '../../utils/format'

const { t } = useI18n()
const status = useStatusStore()

const hasVerdict = computed(() => status.lastVerdictRelative !== '—')
const cost = computed(() => formatNumber(status.dailyCost, 2))
</script>

<style scoped>
.status-bar {
  height: var(--statusbar-h);
  flex: 0 0 var(--statusbar-h);
  background-color: var(--crypt-black-deep);
  border-top: 1px solid rgba(245, 241, 232, 0.04);
  display: flex;
  align-items: center;
  padding: 0 var(--space-6);
  gap: var(--space-3);
  font-family: var(--font-mono);
  font-size: var(--text-micro);
  color: var(--ash-gray);
  letter-spacing: 0.02em;
  z-index: 100;
}

.sb-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  white-space: nowrap;
}
.sb-sep { color: rgba(107, 107, 112, 0.5); }
.sb-strong { color: var(--bone-ivory-dim); }
.flex-spacer { flex: 1; }

.pulse-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--blood-crimson);
  animation: dot-pulse 2.4s ease-out infinite;
}
@keyframes dot-pulse {
  0% { box-shadow: 0 0 0 0 rgba(161, 29, 44, 0.45); }
  60%, 100% { box-shadow: 0 0 0 6px rgba(161, 29, 44, 0); }
}
</style>
