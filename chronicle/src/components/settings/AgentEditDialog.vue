<template>
  <v-dialog
    :model-value="modelValue" :fullscreen="smAndDown" max-width="640" scrollable
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <div class="ae-dialog" data-testid="agent-edit-dialog">
      <header class="ae-head">
        <div class="ae-title">{{ t('settings.agentConfig.edit.title', { name: agentName }) }}</div>
        <button
          class="ae-reset" type="button" :disabled="busy"
          data-testid="agent-edit-reset" @click="onReset"
        >{{ t('settings.agentConfig.edit.reset') }}</button>
      </header>

      <div v-if="loadError" class="ae-error" role="alert">{{ loadError }}</div>
      <div v-else-if="loading" class="ae-loading">…</div>

      <div v-else class="ae-body">
        <SchemaForm
          v-model="form" :fields="fields" :errors="fieldErrors"
          :advanced-label="t('settings.agentConfig.edit.advanced')"
        />
        <div class="ae-tools">
          <div class="ae-section-label">{{ t('settings.agentConfig.edit.tools') }}</div>
          <ToolBindingsEditor v-model="tools" :catalog="catalog" />
        </div>
        <p v-if="saveError" class="ae-error" role="alert" data-testid="agent-edit-error">{{ saveError }}</p>
      </div>

      <footer class="ae-actions">
        <button class="ae-cancel" type="button" @click="emit('update:modelValue', false)">
          {{ t('settings.agentConfig.edit.cancel') }}
        </button>
        <button
          class="ae-save" type="button" :disabled="busy || !!loadError"
          data-testid="agent-edit-save" @click="onSave"
        >{{ t('settings.agentConfig.edit.save') }}</button>
      </footer>
    </div>
  </v-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useDisplay } from 'vuetify'
import { useApi } from '../../api'
import SchemaForm from '../common/SchemaForm.vue'
import ToolBindingsEditor from './ToolBindingsEditor.vue'
import type { FieldDescriptor } from '../common/schemaForm'
import type { AgentDefinition, AgentDefinitionEdit, ToolBinding, ToolCatalogView } from '../../api/types'

const props = defineProps<{ modelValue: boolean; agentName: string }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const { t } = useI18n()
const { smAndDown } = useDisplay()
const api = useApi()

const loading = ref(false)
const loadError = ref<string | null>(null)
const saveError = ref<string | null>(null)
const saving = ref(false)
const resetting = ref(false)
const fieldErrors = ref<Record<string, string>>({})
const catalog = ref<ToolCatalogView[]>([])
const tools = ref<ToolBinding[]>([])
const form = ref<Record<string, unknown>>({})

const busy = computed(() => saving.value || resetting.value || loading.value)

const fields = computed<FieldDescriptor[]>(() => [
  { key: 'prompt', label: t('settings.agentConfig.edit.prompt'), kind: 'textarea', required: true },
  { key: 'enabled', label: t('settings.agentConfig.edit.enabled'), kind: 'toggle' },
  { key: 'modelPurpose', label: t('settings.agentConfig.edit.modelPurpose'), kind: 'select', advanced: true,
    options: [{ value: 'routine', label: 'routine' }, { value: 'reasoning', label: 'reasoning' }] },
  { key: 'schedule', label: t('settings.agentConfig.edit.schedule'), kind: 'text', advanced: true },
  { key: 'maxTurns', label: t('settings.agentConfig.edit.maxTurns'), kind: 'number', min: 1, max: 200, advanced: true },
  { key: 'maxRunSeconds', label: t('settings.agentConfig.edit.maxRunSeconds'), kind: 'number', min: 1, max: 7200, advanced: true },
])

function populate(def: AgentDefinition) {
  form.value = {
    prompt: def.promptText,
    enabled: def.enabled,
    modelPurpose: def.modelPurpose,
    schedule: def.schedule ?? '',
    maxTurns: def.maxTurns,
    maxRunSeconds: def.maxRunSeconds,
  }
  tools.value = def.tools.map(b => ({ toolName: b.toolName, description: b.description ?? null }))
}

watch(() => props.modelValue, (open) => { if (open) load() })

async function load() {
  loading.value = true
  loadError.value = null; saveError.value = null; fieldErrors.value = {}
  try {
    const [def, cat] = await Promise.all([
      api.getAgentDefinition(props.agentName),
      api.getToolCatalog(),
    ])
    populate(def)
    catalog.value = cat
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : t('settings.agentConfig.edit.loadError')
  } finally {
    loading.value = false
  }
}

function buildEdit(): AgentDefinitionEdit {
  return {
    prompt: String(form.value.prompt ?? ''),
    schedule: String(form.value.schedule ?? ''),
    modelPurpose: String(form.value.modelPurpose ?? 'reasoning'),
    enabled: !!form.value.enabled,
    maxTurns: Number(form.value.maxTurns),
    maxRunSeconds: Number(form.value.maxRunSeconds),
    tools: tools.value.map(b => ({ toolName: b.toolName, description: b.description })),
  }
}

async function onSave() {
  saving.value = true; saveError.value = null
  try {
    await api.putAgentDefinition(props.agentName, buildEdit())
    emit('saved')
    emit('update:modelValue', false)
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : t('settings.agentConfig.edit.saveError')
  } finally {
    saving.value = false
  }
}

async function onReset() {
  if (!confirm(t('settings.agentConfig.edit.resetConfirm'))) return
  resetting.value = true; saveError.value = null
  try {
    populate(await api.resetAgentDefinition(props.agentName))
    emit('saved')
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : t('settings.agentConfig.edit.saveError')
  } finally {
    resetting.value = false
  }
}
</script>

<style scoped>
.ae-dialog {
  background-color: var(--crypt-black-elevated); padding: var(--space-6);
  border-radius: 4px; border: 1px solid rgba(184, 148, 92, 0.2);
  display: flex; flex-direction: column; gap: var(--space-4);
  max-height: 90vh;
}
.ae-head { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-4); }
.ae-title { font-size: var(--text-body); color: var(--bone-ivory); letter-spacing: 0.02em; }
.ae-reset { background: transparent; border: 1px solid var(--ash-gray); color: var(--bone-ivory); border-radius: 4px; padding: var(--space-1) var(--space-3); cursor: pointer; }
.ae-body { display: flex; flex-direction: column; gap: var(--space-4); flex: 1 1 auto; overflow-y: auto; min-height: 0; }
.ae-section-label { font-size: var(--text-body-sm); color: var(--ash-gray); margin-bottom: var(--space-2); }
.ae-error { color: var(--blood-crimson); font-size: var(--text-micro); margin: 0; }
.ae-actions { display: flex; justify-content: flex-end; gap: var(--space-2); }
.ae-cancel { background: transparent; border: 1px solid var(--ash-gray); color: var(--bone-ivory); border-radius: 4px; padding: var(--space-2) var(--space-4); cursor: pointer; }
.ae-save { background-color: var(--blood-crimson); border: 1px solid var(--blood-crimson); color: var(--bone-ivory); border-radius: 4px; padding: var(--space-2) var(--space-4); cursor: pointer; }
.ae-save[disabled], .ae-reset[disabled] { opacity: 0.5; cursor: not-allowed; }
</style>
