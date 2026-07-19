<template>
  <div class="tv" data-testid="transcript-view">
    <template v-if="isStructured">
      <div class="tv-head" data-testid="transcript-head">
        <span v-if="full?.agent" class="tv-head-item"><b>{{ t('depots.transcript.agent') }}:</b> {{ full.agent }}</span>
        <span v-if="full?.model" class="tv-head-item"><b>{{ t('depots.transcript.model') }}:</b> {{ full.model }}</span>
        <span v-if="full?.status" class="tv-head-item"><b>{{ t('depots.transcript.status') }}:</b> {{ full.status }}</span>
        <span v-if="durationLabel" class="tv-head-item"><b>{{ t('depots.transcript.duration') }}:</b> {{ durationLabel }}</span>
        <span v-if="full?.turn_count != null" class="tv-head-item"><b>{{ t('depots.transcript.turnCount') }}:</b> {{ full.turn_count }}</span>
        <span v-if="tokenSummary" class="tv-head-item"><b>{{ t('depots.transcript.tokens') }}:</b> {{ tokenSummary }}</span>
      </div>

      <div v-if="imageRedacted" class="tv-image-note" data-testid="transcript-image-redacted">
        {{ t('depots.transcript.imageRedactedInline') }}
      </div>

      <div
        v-for="(turn, ti) in full?.turns ?? []"
        :key="ti"
        class="tv-turn"
        data-testid="transcript-turn"
      >
        <button
          class="tv-turn-toggle"
          type="button"
          @click="toggleTurn(ti)"
          :aria-expanded="isTurnOpen(ti)"
        >{{ t('depots.transcript.turn') }} {{ turn.index ?? ti }} {{ isTurnOpen(ti) ? '▾' : '▸' }}</button>

        <div v-if="isTurnOpen(ti)" class="tv-turn-body">
          <div
            v-for="(call, ci) in turn.tool_calls ?? []"
            :key="ci"
            class="tv-tool-call"
            :class="{ 'tv-tool-call-error': call?.is_error }"
            :data-testid="call?.is_error ? 'tool-call-error' : 'tool-call'"
          >
            <div class="tv-tool-name">
              <b>{{ t('depots.transcript.tool') }}:</b> {{ call?.name ?? '?' }}
              <span v-if="call?.is_error" class="tv-tool-error-badge">{{ t('depots.transcript.toolError') }}</span>
            </div>
            <pre v-if="call?.input !== undefined" class="tv-pre mono">{{ prettyJson(call.input) }}</pre>
            <div class="tv-tool-output">
              <b>{{ t('depots.transcript.toolResult') }}:</b>
              <span v-if="isShortText(call?.output)">{{ asText(call?.output) }}</span>
              <pre v-else class="tv-pre mono">{{ truncated(asText(call?.output), toolOutputKey(ti, ci)) }}</pre>
              <button
                v-if="isTruncatable(call?.output)"
                type="button"
                class="tv-more-toggle"
                :aria-expanded="isMoreOpen(toolOutputKey(ti, ci))"
                @click="toggleMore(toolOutputKey(ti, ci))"
              >{{ isMoreOpen(toolOutputKey(ti, ci)) ? t('depots.transcript.less') : t('depots.transcript.more') }}</button>
            </div>
            <div v-if="call?.is_error && call?.error_detail !== undefined" class="tv-tool-error-detail">
              {{ asText(call.error_detail) }}
            </div>
          </div>

          <div v-if="turn.text" class="tv-answer">
            <b>{{ t('depots.transcript.answer') }}:</b>
            <p class="tv-answer-text">{{ turn.text }}</p>
          </div>

          <div v-if="turn.llm_input_messages?.length" class="tv-prompt">
            <button type="button" class="tv-prompt-toggle" :aria-expanded="isPromptOpen(ti)" @click="togglePrompt(ti)">
              {{ isPromptOpen(ti) ? t('depots.transcript.hidePrompt') : t('depots.transcript.showPrompt') }}
            </button>
            <div v-if="isPromptOpen(ti)" class="tv-prompt-body">
              <div v-for="(msg, mi) in turn.llm_input_messages" :key="mi" class="tv-prompt-msg">
                <b>{{ msg?.role ?? '?' }}:</b>
                <span v-if="typeof msg?.content === 'string'">{{ msg.content }}</span>
                <template v-else-if="Array.isArray(msg?.content)">
                  <div v-for="(block, bi) in msg.content" :key="bi" class="tv-prompt-block">
                    <template v-if="block?.type === 'tool_use'">
                      {{ t('depots.transcript.toolUseInline', { name: block?.name ?? '?' }) }}
                      <pre v-if="block?.input !== undefined" class="tv-pre mono">{{ prettyJson(block.input) }}</pre>
                    </template>
                    <template v-else-if="block?.type === 'tool_result'">
                      <pre class="tv-pre mono">{{ prettyJson(block?.content) }}</pre>
                    </template>
                    <template v-else-if="block?.type === 'image' || block?.type === 'redacted_image'">
                      {{ t('depots.transcript.imageRedactedInline') }}
                    </template>
                    <template v-else>
                      {{ block?.text ?? '' }}
                    </template>
                  </div>
                </template>
              </div>
            </div>
          </div>

          <div class="tv-meta">
            <span v-if="turn.stop_reason">{{ t('depots.transcript.stopReason') }}: {{ turn.stop_reason }}</span>
            <span v-if="turn.tokens">
              {{ t('depots.transcript.tokens') }}: {{ turn.tokens?.input ?? 0 }}/{{ turn.tokens?.output ?? 0 }}
            </span>
          </div>
        </div>
      </div>

      <div v-if="full?.final_output !== undefined" class="tv-final" data-testid="transcript-final-output">
        <b>{{ t('depots.transcript.result') }}:</b>
        <pre class="tv-pre mono">{{ prettyJson(full?.final_output) }}</pre>
      </div>
    </template>

    <pre v-else class="tv-pre mono" data-testid="transcript-raw">{{ prettyJson(transcript) }}</pre>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import type { RunTranscriptFull } from '../../api/types'

const props = defineProps<{ transcript: unknown }>()

const { t } = useI18n()

/** Heuristic marker for a SHA256-redacted image block inside the raw
 *  transcript text. Vistierie's transcript format is out of Dracul's
 *  control and may change; this is a best-effort flag, not a parser. */
const IMAGE_REDACTED_PATTERN = /sha256[:=]?\s*[a-f0-9]{64}/i

const MAX_INLINE_LENGTH = 600

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

const full = computed<RunTranscriptFull | null>(() => {
  const v = props.transcript
  return isObject(v) ? (v as RunTranscriptFull) : null
})

const isStructured = computed(() => full.value !== null && Array.isArray(full.value.turns))

function prettyJson(v: unknown): string {
  try {
    return JSON.stringify(v, null, 2) ?? String(v)
  } catch {
    return String(v)
  }
}

function asText(v: unknown): string {
  if (v === undefined || v === null) return ''
  if (typeof v === 'string') return v
  return prettyJson(v)
}

function isShortText(v: unknown): boolean {
  return typeof v === 'string' && v.length <= MAX_INLINE_LENGTH
}

function isTruncatable(v: unknown): boolean {
  const text = asText(v)
  return text.length > MAX_INLINE_LENGTH
}

const openTurns = reactive<Record<number, boolean>>({ 0: true })
function isTurnOpen(i: number): boolean {
  return openTurns[i] !== false
}
function toggleTurn(i: number) {
  openTurns[i] = !isTurnOpen(i)
}

const openPrompts = reactive<Record<number, boolean>>({})
function isPromptOpen(i: number): boolean {
  return !!openPrompts[i]
}
function togglePrompt(i: number) {
  openPrompts[i] = !isPromptOpen(i)
}

const openMore = reactive<Record<string, boolean>>({})
function toolOutputKey(ti: number, ci: number): string {
  return `${ti}:${ci}`
}
function isMoreOpen(key: string): boolean {
  return !!openMore[key]
}
function toggleMore(key: string) {
  openMore[key] = !isMoreOpen(key)
}
function truncated(text: string, key: string): string {
  if (text.length <= MAX_INLINE_LENGTH || isMoreOpen(key)) return text
  return `${text.slice(0, MAX_INLINE_LENGTH)}…`
}

const durationLabel = computed(() => {
  const start = full.value?.started_at
  const end = full.value?.finished_at
  if (!start || !end) return null
  const ms = new Date(end).getTime() - new Date(start).getTime()
  if (!Number.isFinite(ms) || ms < 0) return null
  return `${(ms / 1000).toFixed(1)}s`
})

const tokenSummary = computed(() => {
  const turns = full.value?.turns ?? []
  let input = 0
  let output = 0
  let any = false
  for (const turn of turns) {
    if (turn?.tokens) {
      any = true
      input += turn.tokens.input ?? 0
      output += turn.tokens.output ?? 0
    }
  }
  return any ? `${input}/${output}` : null
})

const imageRedacted = computed(() => IMAGE_REDACTED_PATTERN.test(JSON.stringify(props.transcript ?? '')))
</script>

<style scoped>
.tv-head { font-size: 0.85em; display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 8px; }
.tv-image-note { font-size: 0.8em; font-style: italic; margin-bottom: 4px; }
.tv-turn { margin-bottom: 8px; border-top: 1px solid var(--color-border, #444); padding-top: 4px; }
.tv-turn-toggle {
  background: none; border: none; cursor: pointer; font-weight: bold;
  color: var(--color-text, inherit); padding: 0; font-size: 0.9em;
}
.tv-turn-body { margin: 6px 0 0 8px; }
.tv-tool-call { border: 1px solid var(--color-border, #444); border-radius: 4px; padding: 6px 8px; margin-bottom: 6px; }
.tv-tool-call-error { border-color: var(--color-danger, #c0392b); }
.tv-tool-name { font-size: 0.85em; margin-bottom: 4px; }
.tv-tool-error-badge {
  color: var(--color-danger, #c0392b); font-weight: bold; margin-left: 6px; font-size: 0.85em;
}
.tv-tool-error-detail { color: var(--color-danger, #c0392b); font-size: 0.8em; margin-top: 4px; }
.tv-tool-output { font-size: 0.85em; margin-top: 4px; }
.tv-answer { margin: 6px 0; font-size: 0.9em; }
.tv-answer-text { white-space: pre-wrap; margin: 4px 0 0 0; }
.tv-prompt { margin: 6px 0; }
.tv-prompt-toggle {
  background: none; border: none; cursor: pointer; font-size: 0.8em;
  color: var(--color-text-muted, inherit); padding: 0;
}
.tv-prompt-body { margin-top: 4px; font-size: 0.8em; }
.tv-prompt-msg { margin-bottom: 4px; }
.tv-prompt-block { margin-left: 8px; }
.tv-meta { font-size: 0.75em; color: var(--color-text-muted, inherit); margin-top: 4px; display: flex; gap: 12px; }
.tv-final { margin-top: 8px; font-size: 0.85em; }
.tv-more-toggle {
  background: none; border: none; cursor: pointer; font-size: 0.8em;
  color: var(--color-text-muted, inherit); padding: 0; margin-left: 4px;
}
.tv-pre {
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow: auto;
  font-size: 0.8em;
  padding: 8px;
  border: 1px solid var(--color-border, #444);
  border-radius: 4px;
  margin: 4px 0 0 0;
}
</style>
