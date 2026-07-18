<template>
  <v-dialog
    :model-value="modelValue"
    :fullscreen="smAndDown"
    max-width="860"
    scrollable
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <div class="pc-dialog" data-testid="pattern-cases-dialog">
      <header class="pc-head">
        <div class="pc-head-main">
          <div class="pc-title">{{ t('patterns.cases.title') }}</div>
          <p v-if="pattern" class="pc-statement">{{ pattern.statement }}</p>
          <p v-if="pattern && pattern.supportedCount !== undefined" class="pc-stat mono">
            {{ t('patterns.cases.stat', { n: pattern.supportedCount, total: pattern.evidenceCount }) }}<template
              v-if="pattern.avgUpliftPercent != null"
            >{{ t('patterns.cases.uplift', { n: pattern.avgUpliftPercent }) }}</template>
          </p>
        </div>
        <button
          class="pc-close"
          type="button"
          :aria-label="t('patterns.cases.close')"
          @click="emit('update:modelValue', false)"
        >
          <i class="ph ph-x" aria-hidden="true" />
        </button>
      </header>

      <div class="pc-body">
        <div v-if="loading" class="pc-state">{{ t('patterns.cases.loading') }}</div>
        <div v-else-if="error" class="pc-state pc-error" role="alert">{{ t('patterns.cases.error') }}</div>
        <div v-else-if="cases.length === 0" class="empty small">
          <p class="em-text">{{ t('patterns.cases.empty') }}</p>
        </div>
        <table v-else class="dt">
          <thead>
            <tr>
              <th>{{ t('patterns.cases.columns.ticker') }}</th>
              <th>{{ t('patterns.cases.columns.company') }}</th>
              <th>{{ t('patterns.cases.columns.anomaly') }}</th>
              <th>{{ t('patterns.cases.columns.date') }}</th>
              <th>{{ t('patterns.cases.columns.outcome') }}</th>
              <th class="num">{{ t('patterns.cases.columns.return') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(c, i) in cases"
              :key="`${c.symbol}-${c.occurredAt}-${i}`"
              data-testid="pattern-case-row"
            >
              <td class="tkr"><TickerButton :symbol="c.symbol" /></td>
              <td>{{ displayName(c.symbol, c.companyName) }}</td>
              <td>{{ anomalyTypeLabel(c.anomalyType) }}</td>
              <td class="mono pc-date">{{ formatDate(c.occurredAt) }}</td>
              <td>
                <span v-if="c.supported" class="pc-outcome pc-outcome--yes">
                  <i class="ph ph-check" aria-hidden="true" /> {{ t('patterns.cases.supported') }}
                </span>
                <span v-else class="pc-outcome pc-outcome--no">
                  <i class="ph ph-x" aria-hidden="true" /> {{ t('patterns.cases.refuted') }}
                </span>
              </td>
              <td
                class="num"
                :class="c.returnPercent == null ? '' : c.returnPercent >= 0 ? 'pos' : 'neg'"
              >
                {{ formatReturn(c.returnPercent) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </v-dialog>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useDisplay } from 'vuetify'
import type { Pattern, PatternCase } from '../../api/types'
import { useEnumLabels } from '../../composables/useEnumLabels'
import { displayName } from '../../utils/instrument'
import TickerButton from '../instrument/TickerButton.vue'

defineProps<{
  modelValue: boolean
  pattern: Pattern | null
  cases: PatternCase[]
  loading: boolean
  error: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const { t } = useI18n()
const { smAndDown } = useDisplay()
const { anomalyTypeLabel } = useEnumLabels()

function formatDate(iso: string): string {
  return iso.slice(0, 10)
}

function formatReturn(value: number | null): string {
  if (value == null) return '—'
  return `${value >= 0 ? '+' : ''}${value}%`
}
</script>

<style scoped>
.pc-dialog {
  background: var(--crypt-black-elevated);
  border: var(--hairline);
  border-radius: 4px;
  display: flex;
  flex-direction: column;
  max-height: 90vh;
}

.pc-head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-4);
  padding: var(--space-5) var(--space-5) var(--space-4);
  border-bottom: 1px solid rgba(184, 148, 92, 0.14);
}
.pc-head-main { flex: 1; min-width: 0; }
.pc-title {
  font-family: var(--font-display);
  font-size: var(--text-h4);
  color: var(--cathedral-gold);
  margin-bottom: var(--space-2);
}
.pc-statement {
  color: var(--bone-ivory-dim);
  font-size: var(--text-body-sm);
  line-height: 1.6;
  margin: 0 0 var(--space-3);
  max-width: 90ch;
  text-wrap: pretty;
}
.pc-stat {
  font-size: var(--text-micro);
  color: var(--ash-gray);
  margin: 0;
}

.pc-close {
  flex: 0 0 auto;
  background: none;
  border: none;
  color: var(--ash-gray);
  cursor: pointer;
  font-size: 18px;
  line-height: 1;
  padding: 4px;
}
.pc-close:hover { color: var(--bone-ivory); }
.pc-close:focus-visible { outline: 1px solid var(--cathedral-gold); outline-offset: 2px; }

.pc-body {
  overflow-y: auto;
  padding: var(--space-2) 0;
}

.pc-state {
  text-align: center;
  padding: var(--space-10) var(--space-6);
  color: var(--ash-gray);
  font-size: var(--text-body-sm);
}
.pc-error { color: var(--blood-crimson-bright); }

.pc-date { color: var(--ash-gray); }

.pc-outcome {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--text-body-sm);
}
.pc-outcome .ph { font-size: 14px; }
.pc-outcome--yes { color: var(--signal-positive-bright); }
.pc-outcome--no { color: var(--blood-crimson-bright); }
</style>
