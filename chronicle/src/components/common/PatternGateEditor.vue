<template>
  <details class="pt-gate">
    <summary class="pt-gate-summary mono">
      {{ t('patterns.gate.title') }}
      <span v-if="pattern.gate" class="pt-gate-armed">{{ t('patterns.gate.armed') }}</span>
      <span v-if="(pattern.blockedCount ?? 0) > 0" class="pt-gate-blocked">
        {{ t('patterns.gate.blocked', { n: pattern.blockedCount }) }}
      </span>
    </summary>
    <textarea
      v-model="gateText"
      class="pt-gate-editor mono"
      rows="5"
      :placeholder="t('patterns.gate.hint')"
      data-testid="gate-editor"
    />
    <div class="pt-gate-actions">
      <button class="btn btn-secondary" :disabled="loading" @click="saveGate">
        {{ t('patterns.gate.save') }}
      </button>
      <span v-if="gateError" class="pt-gate-error">{{ gateError }}</span>
    </div>
  </details>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Pattern } from '../../api/types'

const props = defineProps<{
  pattern: Pattern
  loading?: boolean
}>()

const emit = defineEmits<{
  'save-gate': [gate: unknown | null]
}>()

const { t } = useI18n()

const gateText = ref(props.pattern.gate ? JSON.stringify(props.pattern.gate, null, 2) : '')
const gateError = ref<string | null>(null)

function saveGate() {
  gateError.value = null
  const raw = gateText.value.trim()
  if (raw === '') {
    emit('save-gate', null) // explicit clear
    return
  }
  try {
    emit('save-gate', JSON.parse(raw))
  } catch {
    gateError.value = t('patterns.gate.invalid')
  }
}
</script>

<style scoped>
.pt-gate { margin: 0 0 var(--space-4); }
.pt-gate-summary {
  cursor: pointer;
  font-size: var(--text-micro);
  color: var(--ash-gray);
  display: flex;
  gap: var(--space-3);
  align-items: center;
}
.pt-gate-armed { color: var(--cathedral-gold); }
.pt-gate-blocked { color: var(--blood-crimson-bright); }
.pt-gate-editor {
  width: 100%;
  margin-top: var(--space-2);
  background: var(--crypt-black-elevated);
  color: var(--bone-ivory);
  border: var(--hairline);
  border-radius: 4px;
  padding: var(--space-2);
  font-size: var(--text-micro);
}
.pt-gate-actions {
  margin-top: var(--space-2);
  display: flex;
  gap: var(--space-3);
  align-items: center;
}
.pt-gate-error { color: var(--blood-crimson-bright); font-size: var(--text-micro); }
</style>
