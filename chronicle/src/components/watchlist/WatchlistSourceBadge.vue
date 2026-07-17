<template>
  <TagPill tone="ash" class="wl-source-badge" data-testid="wl-source-badge">{{ label }}</TagPill>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import TagPill from '../common/TagPill.vue'

const props = defineProps<{ source: string }>()

const { t, te } = useI18n()

// Known provenance values are 'seed' | 'manual' | 'verdict' plus the open-ended
// 'agent:<name>' shape (one entry per Strigoi that can add to the watchlist).
// The agent name is interpolated rather than looked up per-agent, since the set
// of agents is not closed. Anything unrecognized falls back to the raw value
// verbatim (crash-safety, same pattern as useEnumLabels).
const label = computed(() => {
  const source = props.source
  if (source.startsWith('agent:')) {
    return t('watchlist.source.agent', { name: source.slice('agent:'.length) })
  }
  const key = `watchlist.source.${source}`
  return te(key) ? t(key) : source
})
</script>

<style scoped>
.wl-source-badge { text-transform: none; }
</style>
