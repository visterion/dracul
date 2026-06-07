<template>
  <div class="trace" data-testid="run-trace">
    <div
      v-for="(event, i) in trace"
      :key="i"
      class="trace-line"
      :class="{ event: isEvent(event), llm: event.type === 'llm-call' }"
    >
      <span class="t-time">{{ event.offset }}</span>
      <span class="t-body">
        <span v-if="symFor(event)" class="sym">{{ symFor(event) }}</span>{{ bodyFor(event) }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { TraceEvent } from '../../api/types'

defineProps<{ trace: TraceEvent[] }>()

// start/end render as bold event rows with a crimson chevron symbol
function isEvent(e: TraceEvent): boolean {
  return e.type === 'start' || e.type === 'end'
}

// ▼ opens the run (start), ▲ closes it (end); other types have no symbol
function symFor(e: TraceEvent): string {
  if (e.type === 'start') return '▼'
  if (e.type === 'end') return '▲'
  return ''
}

// The crimson glyph is rendered separately via .sym, so strip a leading
// ▼/▲ from the message body to avoid a doubled symbol.
function bodyFor(e: TraceEvent): string {
  return e.message.replace(/^[▼▲]\s*/, '')
}
</script>

<style scoped>
/* ported from styles.css:284-290 */
.trace {
  font-family: var(--font-mono);
  font-size: var(--text-body-sm);
  display: flex;
  flex-direction: column;
}
.trace-line {
  display: grid;
  grid-template-columns: 56px 1fr;
  gap: var(--space-4);
  padding: var(--space-1) 0;
  align-items: baseline;
}
.trace-line .t-time { color: var(--ash-gray); font-size: var(--text-body-sm); }
.trace-line .t-body { color: var(--bone-ivory-dim); }
.trace-line.event .t-body { color: var(--bone-ivory); }
.trace-line.event .sym { color: var(--blood-crimson); margin-right: var(--space-2); }
.trace-line.llm {
  background: rgba(184, 148, 92, 0.05);
  border-radius: 3px;
  margin: 1px -8px;
  padding: var(--space-1) var(--space-2);
}
.trace-line.llm .t-body { color: var(--cathedral-gold); }
</style>
