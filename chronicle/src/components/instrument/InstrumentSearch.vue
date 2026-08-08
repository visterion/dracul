<template>
  <div class="is-wrap">
    <input
      v-model="query"
      type="text"
      class="is-input"
      data-testid="is-input"
      role="combobox"
      autocomplete="off"
      aria-autocomplete="list"
      :aria-expanded="showDropdown"
      :placeholder="t('instrumentSearch.placeholder')"
      @keydown="onKeydown"
    />

    <div v-if="showDropdown" class="is-dropdown" role="listbox">
      <div
        v-for="(hit, i) in results"
        :key="hit.symbol"
        class="is-row"
        data-testid="is-row"
        role="option"
        :aria-selected="i === highlightIndex"
        :class="{ active: i === highlightIndex }"
        @mouseenter="highlightIndex = i"
        @click="selectHit(hit)"
      >
        <span class="is-symbol mono">{{ hit.symbol }}</span>
        <span class="is-name">{{ hit.name }}</span>
        <span class="is-exchange">{{ hit.exchange }}</span>
      </div>

      <div v-if="empty" class="is-empty" data-testid="is-empty">
        <div class="is-empty-msg">{{ t('instrumentSearch.empty') }}</div>
        <div class="is-empty-hint">{{ t('instrumentSearch.hint') }}</div>
      </div>

      <div v-if="failed" class="is-error" data-testid="is-error">{{ t('instrumentSearch.unavailable') }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onBeforeUnmount, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api'
import type { InstrumentSearchHit } from '../../api/types'

const { t } = useI18n()
const api = useApi()

const emit = defineEmits<{ select: [symbol: string, name: string] }>()

const DEBOUNCE_MS = 250
const MIN_QUERY_LENGTH = 2

const query = ref('')
const results = ref<InstrumentSearchHit[]>([])
const highlightIndex = ref(-1)
const loading = ref(false)
const failed = ref(false)
// Whether the last completed lookup came back empty — distinct from "user
// hasn't typed enough yet", which must never show the empty state.
const searchedEmpty = ref(false)

let debounceHandle: ReturnType<typeof setTimeout> | null = null
// Sequence number, bumped per request: the ONLY thing that decides whether a
// response is still current. This is what discards an overtaking response —
// not the AbortController below, which is UI correctness only.
let sequence = 0
let controller: AbortController | null = null

const empty = computed(() => searchedEmpty.value && !loading.value && !failed.value)
const showDropdown = computed(() => results.value.length > 0 || empty.value || failed.value)

function resetState() {
  results.value = []
  highlightIndex.value = -1
  failed.value = false
  searchedEmpty.value = false
}

function cancelPending() {
  if (debounceHandle) {
    clearTimeout(debounceHandle)
    debounceHandle = null
  }
  // Invalidate any request already in flight so a late response is ignored.
  sequence++
}

watch(query, value => {
  cancelPending()
  const q = value.trim()
  if (q.length < MIN_QUERY_LENGTH) {
    resetState()
    return
  }
  debounceHandle = setTimeout(() => { void runSearch(q) }, DEBOUNCE_MS)
})

async function runSearch(q: string) {
  const id = ++sequence
  // A fresh AbortController per request, aborting whichever request preceded
  // it. This is purely for the browser-side fetch bookkeeping — it does NOT
  // stop the backend from doing the work: Agora's tool call is synchronous
  // and blocking, so an aborted request's server-side cost is already spent
  // by the time we abort. The sequence number above is what actually keeps
  // a stale response from overwriting a newer one in the UI.
  controller?.abort()
  controller = new AbortController()

  loading.value = true
  failed.value = false
  try {
    const hits = await api.searchInstruments(q, 10)
    if (id !== sequence) return
    results.value = hits
    highlightIndex.value = -1
    searchedEmpty.value = hits.length === 0
  } catch {
    if (id !== sequence) return
    results.value = []
    highlightIndex.value = -1
    searchedEmpty.value = false
    failed.value = true
  } finally {
    if (id === sequence) loading.value = false
  }
}

function selectHit(hit: InstrumentSearchHit) {
  emit('select', hit.symbol, hit.name)
  cancelPending()
  resetState()
  query.value = ''
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    if (results.value.length === 0) return
    e.preventDefault()
    highlightIndex.value = Math.min(highlightIndex.value + 1, results.value.length - 1)
  } else if (e.key === 'ArrowUp') {
    if (results.value.length === 0) return
    e.preventDefault()
    highlightIndex.value = Math.max(highlightIndex.value - 1, 0)
  } else if (e.key === 'Enter') {
    const hit = results.value[highlightIndex.value]
    if (!hit) return
    e.preventDefault()
    selectHit(hit)
  } else if (e.key === 'Escape') {
    cancelPending()
    resetState()
  }
}

onBeforeUnmount(() => {
  cancelPending()
  controller?.abort()
})
</script>

<style scoped>
.is-wrap { position: relative; }
.is-input {
  width: 100%;
  background: var(--crypt-black-deep);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 4px;
  color: var(--bone-ivory);
  font-size: var(--text-body);
  padding: var(--space-2) var(--space-3);
}
.is-input::placeholder { color: var(--ash-gray); }
.is-input:focus { outline: none; border-color: var(--cathedral-gold); }
.is-input:focus-visible { outline: 2px solid var(--cathedral-gold); outline-offset: 2px; }

.is-dropdown {
  position: absolute;
  top: calc(100% + var(--space-1));
  left: 0;
  right: 0;
  z-index: 10;
  background: var(--crypt-black-elevated);
  border: var(--hairline);
  border-radius: 4px;
  max-height: 320px;
  overflow-y: auto;
}

.is-row {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--rule);
  cursor: pointer;
}
.is-row:last-child { border-bottom: none; }
.is-row:hover, .is-row.active { background: rgba(184, 148, 92, 0.08); }
.is-symbol { color: var(--bone-ivory); font-size: var(--text-mono); flex: 0 0 auto; }
.is-name { color: var(--bone-ivory-dim); font-size: var(--text-body-sm); flex: 1 1 auto; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.is-exchange { color: var(--ash-gray); font-size: var(--text-micro); flex: 0 0 auto; }

.is-empty, .is-error { padding: var(--space-3); font-size: var(--text-body-sm); }
.is-empty { color: var(--ash-gray); }
.is-empty-msg { color: var(--bone-ivory-dim); }
.is-empty-hint { color: var(--ash-gray); font-size: var(--text-micro); margin-top: var(--space-1); }
.is-error { color: var(--blood-crimson-bright); }
</style>
