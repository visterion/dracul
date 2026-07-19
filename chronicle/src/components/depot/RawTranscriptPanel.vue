<template>
  <div class="rtp" data-testid="transcript-panel">
    <button
      class="rtp-toggle"
      type="button"
      data-testid="transcript-toggle"
      :aria-expanded="open"
      @click="toggleOpen()"
    >{{ t('depots.transcript.toggle') }} {{ open ? '▾' : '▸' }}</button>

    <div v-if="open" class="rtp-body">
      <div v-if="loading" data-testid="transcript-loading">{{ t('depots.transcript.loading') }}</div>
      <div v-else-if="expired" class="rtp-expired" data-testid="transcript-expired">
        {{ t('depots.transcript.expired') }}
      </div>
      <TranscriptView v-else-if="loaded" :transcript="loadedTranscript" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api'
import type { RunTranscript } from '../../api/types'
import TranscriptView from './TranscriptView.vue'

const props = withDefaults(defineProps<{ runId: string; source?: 'depot' | 'inspector' }>(), {
  source: 'depot',
})

const { t } = useI18n()
const api = useApi()

const open = ref(false)
const loading = ref(false)
const expired = ref(false)
const loadedTranscript = ref<unknown>(null)
const loaded = ref(false)

function toggleOpen() {
  open.value = !open.value
  if (open.value && !loaded.value) void load()
}

async function load() {
  loading.value = true
  try {
    const res: RunTranscript = props.source === 'inspector'
      ? await api.getInspectorTranscript(props.runId)
      : await api.getRunTranscript(props.runId)
    expired.value = res.expired
    if (!res.expired) {
      loadedTranscript.value = res.transcript
    }
    loaded.value = true
  } catch {
    // Network failure or a malformed 200 body: never leave the panel silently
    // blank — surface it as unavailable (and don't retry-loop on re-open).
    expired.value = true
    loaded.value = true
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.rtp { margin-top: 4px; }
.rtp-toggle {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--color-text-muted, inherit);
  font-size: 0.85em;
  padding: 0;
}
.rtp-body { margin-top: 4px; }
</style>
