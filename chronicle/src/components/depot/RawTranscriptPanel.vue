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
      <template v-else-if="rawText !== null">
        <div v-if="imageRedacted" class="rtp-image-note" data-testid="transcript-image-redacted">
          {{ t('depots.transcript.imageRedacted') }}
        </div>
        <pre class="rtp-pre mono" data-testid="transcript-raw">{{ rawText }}</pre>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api'
import type { RunTranscript } from '../../api/types'

const props = defineProps<{ runId: string }>()

const { t } = useI18n()
const api = useApi()

const open = ref(false)
const loading = ref(false)
const expired = ref(false)
const rawText = ref<string | null>(null)
const imageRedacted = ref(false)
let loaded = false

/** Heuristic marker for a SHA256-redacted image block inside the raw
 *  transcript text. Vistierie's transcript format is out of Dracul's
 *  control and may change; this is a best-effort flag, not a parser. */
const IMAGE_REDACTED_PATTERN = /sha256[:=]?\s*[a-f0-9]{64}/i

function toggleOpen() {
  open.value = !open.value
  if (open.value && !loaded) void load()
}

async function load() {
  loading.value = true
  try {
    const res: RunTranscript = await api.getRunTranscript(props.runId)
    expired.value = res.expired
    if (!res.expired) {
      rawText.value = JSON.stringify(res.transcript, null, 2)
      imageRedacted.value = IMAGE_REDACTED_PATTERN.test(rawText.value ?? '')
    }
    loaded = true
  } catch {
    // Network failure or a malformed 200 body: never leave the panel silently
    // blank — surface it as unavailable (and don't retry-loop on re-open).
    expired.value = true
    loaded = true
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
.rtp-pre {
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow: auto;
  font-size: 0.8em;
  padding: 8px;
  border: 1px solid var(--color-border, #444);
  border-radius: 4px;
}
.rtp-image-note { font-size: 0.8em; font-style: italic; margin-bottom: 4px; }
</style>
