<template>
  <div class="schema-form">
    <SchemaFormField
      v-for="f in mainFields" :key="f.key" :field="f"
      :model-value="modelValue[f.key]" :error="errors?.[f.key]"
      @update:model-value="(v) => update(f.key, v)"
    />

    <div v-if="advancedFields.length" class="sf-advanced">
      <button
        type="button" class="sf-advanced-toggle" :aria-expanded="advancedOpen"
        data-testid="schema-form-advanced" @click="advancedOpen = !advancedOpen"
      >{{ advancedOpen ? '▾' : '▸' }} {{ resolvedAdvancedLabel }}</button>
      <div v-show="advancedOpen" class="sf-advanced-body">
        <SchemaFormField
          v-for="f in advancedFields" :key="f.key" :field="f"
          :model-value="modelValue[f.key]" :error="errors?.[f.key]"
          @update:model-value="(v) => update(f.key, v)"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import SchemaFormField from './SchemaFormField.vue'
import type { FieldDescriptor } from './schemaForm'

const props = defineProps<{
  fields: FieldDescriptor[]
  modelValue: Record<string, unknown>
  errors?: Record<string, string>
  advancedLabel?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [Record<string, unknown>] }>()

const advancedOpen = ref(false)
const resolvedAdvancedLabel = computed(() => props.advancedLabel ?? 'Advanced')
const mainFields = computed(() => props.fields.filter(f => !f.advanced))
const advancedFields = computed(() => props.fields.filter(f => f.advanced))

function update(key: string, value: unknown) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}
</script>

<style scoped>
.sf-advanced { margin-top: var(--space-2); }
.sf-advanced-toggle {
  background: transparent; border: none; color: var(--cathedral-gold);
  font-size: var(--text-body-sm); cursor: pointer; padding: var(--space-1) 0; margin-bottom: var(--space-2);
}
.sf-advanced-body { padding-left: var(--space-2); border-left: 1px solid rgba(184, 148, 92, 0.2); }
</style>
