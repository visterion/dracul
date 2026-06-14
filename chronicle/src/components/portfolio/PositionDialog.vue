<template>
  <v-dialog :model-value="modelValue" max-width="420" @update:model-value="(v) => emit('update:modelValue', v)">
    <div class="pf-dialog">
      <div class="pf-dialog__title">{{ mode === 'edit' ? t('portfolio.dialog.editTitle') : t('portfolio.dialog.addTitle') }}</div>

      <label class="pf-dialog__label">{{ t('portfolio.dialog.symbol') }}</label>
      <input
        v-model="symbol" class="pf-dialog__input mono" type="text"
        :placeholder="t('portfolio.dialog.symbolPlaceholder')" maxlength="10"
        :disabled="mode === 'edit'" data-testid="pf-symbol"
        @input="symbol = symbol.toUpperCase()"
      />

      <label class="pf-dialog__label">{{ t('portfolio.dialog.entry') }}</label>
      <input v-model.number="entry" class="pf-dialog__input mono" type="number" step="0.01" min="0" data-testid="pf-entry" />

      <label class="pf-dialog__label">{{ t('portfolio.dialog.size') }}</label>
      <input v-model.number="size" class="pf-dialog__input mono" type="number" step="1" min="0" data-testid="pf-size" />

      <p v-if="error" class="pf-dialog__error" role="alert">{{ error }}</p>

      <div class="pf-dialog__actions">
        <button class="pf-dialog__cancel" @click="emit('update:modelValue', false)">{{ t('portfolio.dialog.cancel') }}</button>
        <button class="pf-dialog__submit" :disabled="!valid || !!submitting" data-testid="pf-submit" @click="onSubmit">
          {{ mode === 'edit' ? t('portfolio.dialog.save') : t('portfolio.dialog.add') }}
        </button>
      </div>
    </div>
  </v-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{
  modelValue: boolean
  mode: 'add' | 'edit'
  initialSymbol?: string
  initialEntry?: number | null
  initialSize?: number | null
  error?: string | null
  submitting?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [boolean]
  submit: [{ symbol: string; entryPrice: number; shareCount: number }]
}>()
const { t } = useI18n()

const TICKER_RE = /^[A-Z][A-Z0-9.\-]{0,9}$/
const symbol = ref('')
const entry = ref<number>(0)
const size = ref<number>(0)

watch(() => props.modelValue, (open) => {
  if (open) {
    symbol.value = props.initialSymbol ?? ''
    entry.value = props.initialEntry ?? 0
    size.value = props.initialSize ?? 0
  }
})

const valid = computed(() =>
  TICKER_RE.test(symbol.value) &&
  !Number.isNaN(entry.value) && entry.value > 0 &&
  !Number.isNaN(size.value) && size.value >= 0)

function onSubmit() {
  if (!valid.value) return
  emit('submit', { symbol: symbol.value, entryPrice: entry.value, shareCount: size.value })
}
</script>

<style scoped>
.pf-dialog { background-color: var(--crypt-black-elevated); padding: var(--space-6); border-radius: 4px;
  display: flex; flex-direction: column; gap: var(--space-2); border: 1px solid rgba(184,148,92,0.2); }
.pf-dialog__title { font-size: var(--text-body); color: var(--bone-ivory); margin-bottom: var(--space-2); }
.pf-dialog__label { font-size: var(--text-body-sm); color: var(--ash-gray); }
.pf-dialog__input { background-color: var(--crypt-black-deep); border: 1px solid rgba(255,255,255,0.1);
  border-radius: 4px; color: var(--bone-ivory); padding: var(--space-2); font: inherit; }
.pf-dialog__input:focus { outline: none; border-color: var(--cathedral-gold); }
.pf-dialog__input:disabled { opacity: 0.6; }
.pf-dialog__error { color: var(--blood-crimson); font-size: var(--text-micro); margin: 0; }
.pf-dialog__actions { display: flex; justify-content: flex-end; gap: var(--space-2); margin-top: var(--space-2); }
.pf-dialog__cancel { background: transparent; border: 1px solid var(--ash-gray); color: var(--bone-ivory); border-radius: 4px; padding: var(--space-2) var(--space-4); cursor: pointer; }
.pf-dialog__submit { background-color: var(--blood-crimson); border: 1px solid var(--blood-crimson); color: var(--bone-ivory); border-radius: 4px; padding: var(--space-2) var(--space-4); cursor: pointer; }
.pf-dialog__submit:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
