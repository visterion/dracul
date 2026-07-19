<template>
  <div class="explainer-overlay" data-testid="explainer-overlay" @click.self="$emit('close')">
    <div
      class="explainer-panel"
      role="dialog"
      aria-modal="true"
      :aria-label="explainer.title"
      tabindex="-1"
      @keydown.esc.prevent="$emit('close')"
    >
      <header class="ex-head">
        <h2 class="ex-title">{{ explainer.title }}</h2>
        <button
          ref="closeBtn"
          class="ex-close"
          data-testid="explainer-close"
          :aria-label="t('explainer.close')"
          @click="$emit('close')"
        >
          <i class="ph ph-x" aria-hidden="true" />
        </button>
      </header>
      <section
        v-for="s in explainer.sections"
        :key="s.anchor ?? s.heading"
        :ref="el => registerSection(s.anchor, el)"
        class="ex-section"
        data-testid="explainer-section"
      >
        <h3 class="ex-section-head">{{ s.heading }}</h3>
        <p class="ex-section-body">{{ s.body }}</p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Explainer } from '../../i18n/explainers'

const props = defineProps<{ explainer: Explainer; anchor?: string }>()
defineEmits<{ close: [] }>()

const { t } = useI18n()
const closeBtn = ref<HTMLElement | null>(null)
const sectionEls = new Map<string, HTMLElement>()

function registerSection(anchor: string | undefined, el: unknown) {
  if (anchor && el instanceof HTMLElement) sectionEls.set(anchor, el)
}

onMounted(async () => {
  await nextTick()
  if (props.anchor) {
    // Defensive: happy-dom (test env) may not implement scrollIntoView.
    sectionEls.get(props.anchor)?.scrollIntoView?.({ block: 'start' })
  }
  closeBtn.value?.focus()
})
</script>

<style scoped>
.explainer-overlay {
  position: fixed; inset: 0; z-index: 60;
  background: rgba(0,0,0,0.55);
  display: flex; align-items: center; justify-content: center;
  padding: var(--space-4);
}
.explainer-panel {
  background: var(--crypt-black-elevated); border: var(--hairline); border-radius: 6px;
  max-width: 560px; width: 100%; max-height: 80vh; overflow-y: auto;
  padding: var(--space-5); outline: none;
}
.ex-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-3); margin-bottom: var(--space-4); }
.ex-title { font-size: var(--text-h3); color: var(--bone-ivory); }
.ex-close { background: transparent; border: none; color: var(--ash-gray-light); cursor: pointer; font-size: var(--text-body-lg); }
.ex-close:hover { color: var(--bone-ivory); }
.ex-section { margin-bottom: var(--space-4); }
.ex-section-head { font-size: var(--text-body); color: var(--cathedral-gold); margin-bottom: var(--space-1); }
.ex-section-body { font-size: var(--text-body-sm); color: var(--bone-ivory-dim); line-height: 1.5; }

@media (max-width: 600px) {
  .explainer-overlay { align-items: flex-end; padding: 0; }
  .explainer-panel { max-width: 100%; max-height: 90vh; border-radius: 12px 12px 0 0; }
}
</style>
