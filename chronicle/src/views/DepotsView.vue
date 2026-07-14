<template>
  <div class="content-inner depots-view">
    <PageHead :title="t('depots.title')" :sub="t('depots.subtitle')">
      <template #right>
        <button class="btn btn-secondary" data-testid="depots-refresh" @click="load(true)">
          <i class="ph ph-arrow-clockwise" aria-hidden="true" /> {{ t('depots.refresh') }}
        </button>
      </template>
    </PageHead>

    <div v-if="error" class="empty small"><div class="em-text">{{ error }}</div></div>

    <template v-else-if="loading">
      <v-skeleton-loader v-for="i in 3" :key="i" type="list-item-two-line" />
    </template>

    <div v-else-if="depots.length === 0" class="empty small" data-testid="depots-empty">
      <div class="em-text">{{ t('depots.empty') }}</div>
    </div>

    <template v-else>
      <label class="depots-selector">
        <span class="depots-selector-label">{{ t('depots.selector.label') }}</span>
        <select
          v-model="selectedId"
          class="depots-selector-select"
          data-testid="depot-select"
        >
          <option v-for="d in depots" :key="d.id" :value="d.id">{{ d.id }} · {{ d.provider }}{{ d.environment === 'live' ? ' · LIVE' : '' }}</option>
        </select>
      </label>

      <DepotSection v-if="selectedDepot" :key="selectedDepot.id" :depot="selectedDepot" />
    </template>

    <div class="depots-calibration">
      <SectionHeader :label="t('calibration.sectionLabel')" />
      <CalibrationCard />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import PageHead from '../components/common/PageHead.vue'
import SectionHeader from '../components/common/SectionHeader.vue'
import DepotSection from '../components/depot/DepotSection.vue'
import CalibrationCard from '../components/CalibrationCard.vue'
import { useApi } from '../api'
import type { Depot } from '../api/types'

const { t } = useI18n()
const api = useApi()

const SELECTED_STORAGE_KEY = 'dracul.depots.selected'

const depots = ref<Depot[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const selectedId = ref<string | null>(null)

const selectedDepot = computed(() => depots.value.find(d => d.id === selectedId.value) ?? null)

/** Restores the persisted selection if it still points at a visible depot,
 *  otherwise falls back to the first depot in the (backend-ordered) list. */
function resolveSelection(list: Depot[]): string | null {
  if (list.length === 0) return null
  let persisted: string | null = null
  try {
    persisted = localStorage.getItem(SELECTED_STORAGE_KEY)
  } catch {
    persisted = null
  }
  return persisted != null && list.some(d => d.id === persisted) ? persisted : list[0].id
}

// Persist every selection change (user pick or the initial resolved default)
// so the choice survives a reload.
watch(selectedId, id => {
  if (id == null) return
  try {
    localStorage.setItem(SELECTED_STORAGE_KEY, id)
  } catch {
    // private browsing / storage disabled — selection still works in-memory
  }
})

async function load(refresh = false) {
  loading.value = true
  error.value = null
  try {
    const res = await api.getDepots(refresh)
    depots.value = res.depots
    selectedId.value = resolveSelection(res.depots)
    if (res.error) error.value = res.error
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('depots.loadError')
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<style scoped>
.depots-selector {
  display: flex; align-items: center; gap: var(--space-3);
  margin-bottom: var(--space-5);
}
.depots-selector-label {
  font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.1em; color: var(--ash-gray);
}
.depots-selector-select {
  background: var(--crypt-black-elevated); border: var(--hairline); color: var(--bone-ivory);
  border-radius: 4px; padding: var(--space-2) var(--space-4); font-size: var(--text-body);
  min-width: 240px;
}
.depots-calibration { margin-top: var(--space-8); }
</style>
