<template>
  <button
    class="decision-doc-btn"
    type="button"
    :aria-label="title"
    :title="title"
    data-testid="decision-info"
    @click.stop="open = true"
  >
    <i class="ph ph-info" aria-hidden="true" />
  </button>
  <Teleport to="body">
    <!-- Doc present: wide, sanitized-Markdown dialog. -->
    <div
      v-if="wideOpen"
      class="dd-overlay"
      data-testid="decision-doc-overlay"
      @click.self="open = false"
    >
      <div
        class="dd-panel"
        data-testid="decision-doc-panel"
        role="dialog"
        aria-modal="true"
        :aria-label="title"
        tabindex="-1"
        @keydown.esc.prevent="open = false"
      >
        <header class="dd-head">
          <h2 class="dd-title">{{ title }}</h2>
          <button
            ref="closeBtn"
            class="dd-close"
            data-testid="decision-doc-close"
            :aria-label="t('explainer.close')"
            @click="open = false"
          >
            <i class="ph ph-x" aria-hidden="true" />
          </button>
        </header>
        <MarkdownView :source="markdown!" />
      </div>
    </div>

    <!-- No doc: fall back to the static decision.overview explainer. -->
    <ExplainerPanel
      v-if="fallbackOpen"
      :explainer="explainer"
      @close="open = false"
    />
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import MarkdownView from '../common/MarkdownView.vue'
import ExplainerPanel from '../common/ExplainerPanel.vue'
import { useDecisionDoc } from '../../composables/useDecisionDoc'
import { useScrollLock } from '../../composables/useScrollLock'
import { getExplainer } from '../../i18n/explainers'

const { t, locale } = useI18n()
const { markdown } = useDecisionDoc()

const open = ref(false)
const title = computed(() => t('app.decisionInfo.title'))

// Treat "not yet loaded" the same as "no doc": the fallback path renders a
// full, working explainer regardless of fetch timing, so exactly one (i)
// button is visible at all times and neither panel ever flashes twice.
const hasDoc = computed(
  () => typeof markdown.value === 'string' && markdown.value.length > 0,
)
const wideOpen = computed(() => open.value && hasDoc.value)
const fallbackOpen = computed(() => open.value && !hasDoc.value)

// The static explainer is only read when the fallback path is taken.
const explainer = computed(() => getExplainer(locale.value, 'decision.overview'))

// Lock page scroll while the wide panel is open; ExplainerPanel owns its own
// lock in the fallback path.
useScrollLock(wideOpen)

const closeBtn = ref<HTMLElement | null>(null)
watch(wideOpen, async (on) => {
  if (on) {
    await nextTick()
    closeBtn.value?.focus()
  }
})
</script>

<style scoped>
.decision-doc-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-2);
  background: transparent;
  border: none;
  cursor: pointer;
  color: var(--bone-ivory-dim);
  font-size: var(--text-body-lg);
  line-height: 1;
}
.decision-doc-btn:hover {
  color: var(--cathedral-gold);
}

.dd-overlay {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-4);
}
.dd-panel {
  background: var(--crypt-black-elevated);
  border: var(--hairline);
  border-radius: 6px;
  width: 100%;
  max-width: min(1120px, 94vw);
  max-height: 85vh;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: var(--space-5);
  outline: none;
}
.dd-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}
.dd-title {
  font-size: var(--text-h3);
  color: var(--bone-ivory);
}
.dd-close {
  background: transparent;
  border: none;
  color: var(--ash-gray-light);
  cursor: pointer;
  font-size: var(--text-body-lg);
}
.dd-close:hover {
  color: var(--bone-ivory);
}

@media (max-width: 959.98px) {
  .dd-overlay {
    align-items: flex-end;
    padding: 0;
  }
  .dd-panel {
    max-width: 100%;
    max-height: 90vh;
    border-radius: 12px 12px 0 0;
  }
}
</style>
