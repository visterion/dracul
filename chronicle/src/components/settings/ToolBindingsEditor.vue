<template>
  <div class="tool-bindings">
    <div v-for="tool in catalog" :key="tool.toolName" class="tb-row" :data-tool="tool.toolName">
      <label class="tb-check">
        <input
          type="checkbox" :checked="isBound(tool.toolName)"
          :data-testid="`tool-toggle-${tool.toolName}`"
          @change="toggle(tool.toolName, ($event.target as HTMLInputElement).checked)"
        />
        <span class="tb-name mono">{{ tool.toolName }}</span>
      </label>
      <textarea
        v-if="isBound(tool.toolName)" class="tb-desc"
        :placeholder="tool.defaultDescription"
        :value="descriptionOf(tool.toolName) ?? ''"
        @input="setDescription(tool.toolName, ($event.target as HTMLTextAreaElement).value)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import type { ToolBinding, ToolCatalogView } from '../../api/types'

const props = defineProps<{ catalog: ToolCatalogView[]; modelValue: ToolBinding[] }>()
const emit = defineEmits<{ 'update:modelValue': [ToolBinding[]] }>()

function isBound(name: string): boolean { return props.modelValue.some(b => b.toolName === name) }
function descriptionOf(name: string): string | null {
  return props.modelValue.find(b => b.toolName === name)?.description ?? null
}
function toggle(name: string, on: boolean) {
  if (on) emit('update:modelValue', [...props.modelValue, { toolName: name, description: null }])
  else emit('update:modelValue', props.modelValue.filter(b => b.toolName !== name))
}
function setDescription(name: string, text: string) {
  emit('update:modelValue', props.modelValue.map(b =>
    b.toolName === name ? { ...b, description: text === '' ? null : text } : b))
}
</script>

<style scoped>
.tool-bindings { display: flex; flex-direction: column; gap: var(--space-3); }
.tb-row { display: flex; flex-direction: column; gap: var(--space-1); }
.tb-check { display: flex; align-items: center; gap: var(--space-2); color: var(--bone-ivory); font-size: var(--text-body-sm); }
.tb-name { color: var(--bone-ivory); }
.tb-desc {
  background-color: var(--crypt-black-deep); border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 4px; color: var(--bone-ivory); padding: var(--space-2);
  font: inherit; font-family: var(--font-mono); min-height: 3rem; resize: vertical;
  margin-left: var(--space-5);
}
.tb-desc:focus { outline: none; border-color: var(--cathedral-gold); }
</style>
