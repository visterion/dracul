<template>
  <div class="sf-field">
    <label class="sf-label" :for="id">{{ field.label }}</label>

    <textarea
      v-if="field.kind === 'textarea'" :id="id" class="sf-input sf-textarea" rows="8"
      :value="(modelValue as string) ?? ''"
      @input="emitVal(($event.target as HTMLTextAreaElement).value)"
    />
    <select
      v-else-if="field.kind === 'select'" :id="id" class="sf-input"
      :value="modelValue" @change="emitVal(($event.target as HTMLSelectElement).value)"
    >
      <option v-for="o in field.options" :key="o.value" :value="o.value">{{ o.label }}</option>
    </select>
    <input
      v-else-if="field.kind === 'toggle'" :id="id" type="checkbox" class="sf-checkbox"
      :checked="!!modelValue" @change="emitVal(($event.target as HTMLInputElement).checked)"
    />
    <input
      v-else-if="field.kind === 'number'" :id="id" class="sf-input" type="number"
      :min="field.min" :max="field.max" :value="modelValue as number"
      @input="emitVal(numberVal(($event.target as HTMLInputElement).value))"
    />
    <input
      v-else :id="id" class="sf-input" type="text"
      :value="(modelValue as string) ?? ''"
      @input="emitVal(($event.target as HTMLInputElement).value)"
    />

    <p v-if="field.help" class="sf-help">{{ field.help }}</p>
    <p v-if="error" class="sf-error" role="alert">{{ error }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { FieldDescriptor } from './schemaForm'

const props = defineProps<{ field: FieldDescriptor; modelValue: unknown; error?: string }>()
const emit = defineEmits<{ 'update:modelValue': [unknown] }>()

const id = computed(() => `sf-${props.field.key}`)
function emitVal(v: unknown) { emit('update:modelValue', v) }
function numberVal(s: string): number | null { return s === '' ? null : Number(s) }
</script>

<style scoped>
.sf-field { display: flex; flex-direction: column; gap: var(--space-1); margin-bottom: var(--space-4); }
.sf-label { font-size: var(--text-body-sm); color: var(--ash-gray); letter-spacing: 0.02em; }
.sf-input {
  background-color: var(--crypt-black-deep);
  border: 1px solid rgba(255, 255, 255, 0.1); border-radius: 4px;
  color: var(--bone-ivory); padding: var(--space-2); font: inherit;
}
.sf-input:focus { outline: none; border-color: var(--cathedral-gold); }
.sf-textarea { resize: vertical; min-height: 8rem; font-family: var(--font-mono); }
.sf-checkbox { width: 1rem; height: 1rem; align-self: flex-start; }
.sf-help { font-size: var(--text-micro); color: var(--ash-gray); margin: 0; }
.sf-error { color: var(--blood-crimson); font-size: var(--text-micro); margin: 0; }
</style>
