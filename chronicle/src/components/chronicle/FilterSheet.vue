<template>
  <div v-if="open" class="filter-sheet" data-testid="filter-sheet">
    <div class="filter-sheet__backdrop" data-testid="filter-sheet-backdrop" @click="$emit('close')" />
    <section class="filter-sheet__panel" role="dialog" aria-modal="true" :aria-label="t('chronicle.filters.title')">
      <header class="filter-sheet__head">
        <span>{{ t('chronicle.filters.title') }}</span>
        <button class="filter-sheet__close" :aria-label="t('chronicle.filters.close')" @click="$emit('close')">✕</button>
      </header>

      <div class="fg-head">{{ t('chronicle.filters.title') }}</div>
      <button class="filter-chip sheet-chip" :class="{ active: filter === 'all' }"
              data-testid="sheet-chip-all" @click="$emit('select', 'all')">
        {{ t('chronicle.filters.allPrey') }} <span class="fc-count font-mono">{{ counts.all ?? 0 }}</span>
      </button>
      <button class="filter-chip sheet-chip" :class="{ active: filter === 'high' }"
              data-testid="sheet-chip-high" @click="$emit('select', 'high')">
        {{ t('chronicle.filters.highConfidence') }} <span class="fc-count font-mono">{{ counts.high ?? 0 }}</span>
      </button>

      <template v-if="anomalyTypes.length > 0">
        <div class="fg-head">{{ t('chronicle.filters.anomalyClass') }}</div>
        <button v-for="a in anomalyTypes" :key="a" class="filter-chip sheet-chip"
                :class="{ active: filter === a }" :data-testid="`sheet-chip-${a}`"
                @click="$emit('select', a)">
          {{ anomalyTypeLabel(a) }} <span class="fc-count font-mono">{{ counts[a] ?? 0 }}</span>
        </button>
      </template>

      <div class="fg-head">{{ t('chronicle.filters.broodProfiles') }}</div>
      <BroodMini :strigoi="strigoi" :counts="broodCounts" @open="s => $emit('openStrigoi', s)" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import type { StrigoiStatus } from '../../api/types'
import BroodMini from '../common/BroodMini.vue'
import { useEnumLabels } from '../../composables/useEnumLabels'

defineProps<{
  open: boolean
  filter: string
  anomalyTypes: string[]
  counts: Record<string, number>
  strigoi: StrigoiStatus[]
  broodCounts: Record<string, number>
}>()
const emit = defineEmits<{ close: []; select: [filter: string]; openStrigoi: [s: StrigoiStatus] }>()

const { t } = useI18n()
const { anomalyTypeLabel } = useEnumLabels()

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}
onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
.filter-sheet__backdrop { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); z-index: 290; }
.filter-sheet__panel {
  position: fixed; left: 0; right: 0; bottom: 0; z-index: 300;
  max-height: 75vh; overflow-y: auto;
  background: var(--crypt-black-elevated);
  border-top: var(--hairline);
  border-radius: 12px 12px 0 0;
  padding: var(--space-4) var(--space-4) calc(var(--space-6) + env(safe-area-inset-bottom));
}
.filter-sheet__head { display: flex; align-items: center; justify-content: space-between;
  color: var(--bone-ivory); margin-bottom: var(--space-3); }
.filter-sheet__close { background: none; border: none; color: var(--ash-gray);
  min-width: 44px; min-height: 44px; cursor: pointer; }
.fg-head { font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.12em;
  color: var(--ash-gray); margin: var(--space-4) 0 var(--space-2); }
/* chips: same look as the desktop rail, touch-sized */
.filter-chip { display: flex; align-items: center; gap: var(--space-2); width: 100%; text-align: left;
  background: transparent; border: none; color: var(--bone-ivory-dim); font-family: var(--font-body);
  font-size: var(--text-body-sm); padding: var(--space-2) var(--space-3); border-radius: 4px;
  cursor: pointer; min-height: 44px; }
.filter-chip.active { color: var(--bone-ivory); background: rgba(161, 29, 44, 0.1); }
.filter-chip .fc-count { margin-left: auto; font-size: var(--text-micro); color: var(--ash-gray); }
</style>
