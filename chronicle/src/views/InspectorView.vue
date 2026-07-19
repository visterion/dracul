<template>
  <div class="content-inner insp-view">
    <div class="insp-toolbar">
      <label class="insp-filter">
        <span class="insp-filter-k">{{ t('inspector.filter.label') }}</span>
        <select
          v-model="selectedAgent"
          class="insp-filter-select mono"
          data-testid="inspector-agent-filter"
        >
          <option :value="null">{{ t('inspector.filter.all') }}</option>
          <option v-for="a in AGENTS" :key="a" :value="a">{{ a }}</option>
        </select>
      </label>
    </div>

    <div v-if="loading && runs.length === 0" class="empty small">
      <div class="em-text">{{ t('inspector.loading') }}</div>
    </div>

    <div v-else-if="runs.length === 0" class="empty small">
      <div class="em-text">{{ t('inspector.empty') }}</div>
    </div>

    <div v-else class="insp-rows">
      <div
        v-for="r in runs"
        :key="r.runId"
        class="insp-row"
        role="button"
        tabindex="0"
        data-testid="inspector-run"
        :class="{ active: expandedRunId === r.runId }"
        @click="toggleExpanded(r.runId)"
        @keydown.enter="toggleExpanded(r.runId)"
        @keydown.space.prevent="toggleExpanded(r.runId)"
      >
        <div class="insp-row-head">
          <span class="insp-agent mono">{{ r.agent }}</span>
          <span class="insp-status">{{ r.status }}</span>
          <span v-if="r.hasError" class="insp-error" data-testid="inspector-run-error">{{ t('inspector.error') }}</span>
          <span class="insp-time mono">{{ r.startedAt ? formatAbsoluteTime(r.startedAt) : '—' }}</span>
        </div>
        <div v-if="r.snippet" class="insp-snippet">{{ r.snippet }}</div>

        <RawTranscriptPanel
          v-if="expandedRunId === r.runId"
          :run-id="r.runId"
          source="inspector"
          @click.stop
        />
      </div>
    </div>

    <button
      v-if="runs.length > 0"
      class="btn btn-secondary insp-more"
      data-testid="inspector-load-more"
      :disabled="loadingMore"
      @click="loadMore"
    >{{ loadingMore ? t('inspector.loadingMore') : t('inspector.loadMore') }}</button>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import RawTranscriptPanel from '../components/depot/RawTranscriptPanel.vue'
import { useApi } from '../api'
import type { InspectorRun } from '../api/types'
import { formatAbsoluteTime } from '../lib/depotDisplay'

/** Known Vistierie agent names. Client-side constant — no backend endpoint
 *  enumerates agents, and this list changes rarely (see project brief). */
const AGENTS = [
  'strigoi-echo', 'strigoi-index', 'strigoi-insider', 'strigoi-lazarus',
  'strigoi-merger', 'strigoi-spin', 'voievod', 'voievod-outcome',
  'daywalker', 'renfield', 'executor', 'gropar',
] as const

const PAGE_SIZE = 50

const { t } = useI18n()
const api = useApi()

const selectedAgent = ref<string | null>(null)
const runs = ref<InspectorRun[]>([])
const offset = ref(0)
const loading = ref(true)
const loadingMore = ref(false)
const expandedRunId = ref<string | null>(null)

function toggleExpanded(runId: string) {
  expandedRunId.value = expandedRunId.value === runId ? null : runId
}

async function loadFirstPage() {
  loading.value = true
  expandedRunId.value = null
  offset.value = 0
  try {
    const res = await api.getInspectorRuns(selectedAgent.value, PAGE_SIZE, 0)
    runs.value = res.runs
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  loadingMore.value = true
  try {
    const nextOffset = runs.value.length
    const res = await api.getInspectorRuns(selectedAgent.value, PAGE_SIZE, nextOffset)
    runs.value = [...runs.value, ...res.runs]
    offset.value = nextOffset
  } finally {
    loadingMore.value = false
  }
}

watch(selectedAgent, () => { void loadFirstPage() })

onMounted(() => { void loadFirstPage() })
</script>

<style scoped>
.insp-view { display: flex; flex-direction: column; gap: var(--space-4); padding: var(--space-5); }

.insp-toolbar { display: flex; align-items: center; gap: var(--space-4); }
.insp-filter { display: inline-flex; align-items: center; gap: var(--space-2); font-size: var(--text-body-sm); color: var(--ash-gray); }
.insp-filter-k { color: var(--bone-ivory-dim); }
.insp-filter-select {
  background: var(--crypt-black-deep);
  border: 1px solid rgba(184, 148, 92, 0.25);
  border-radius: 4px; color: var(--bone-ivory);
  padding: var(--space-1) var(--space-2); font-size: var(--text-body-sm);
}

.insp-rows { display: flex; flex-direction: column; }
.insp-row {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3);
  border-bottom: 1px solid var(--rule);
  border-left: 3px solid transparent;
  cursor: pointer;
  transition: background var(--transition-fast);
}
.insp-row:hover { background: rgba(184, 148, 92, 0.05); }
.insp-row.active { background: rgba(161, 29, 44, 0.08); border-left-color: var(--blood-crimson); }
.insp-row:focus-visible { outline: 2px solid var(--cathedral-gold); outline-offset: -2px; }

.insp-row-head { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
.insp-agent { color: var(--bone-ivory); font-size: var(--text-body-sm); }
.insp-status { color: var(--ash-gray-light); font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.06em; }
.insp-error {
  color: var(--blood-crimson-bright);
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}
.insp-time { color: var(--ash-gray); font-size: var(--text-micro); margin-left: auto; }
.insp-snippet {
  color: var(--ash-gray-light);
  font-size: var(--text-body-sm);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.insp-more { align-self: center; margin-top: var(--space-2); }
</style>
