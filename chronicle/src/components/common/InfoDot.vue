<template>
  <button
    v-if="known"
    class="info-dot"
    :class="{ 'info-dot--icon': variant === 'icon' }"
    type="button"
    :aria-label="ariaLabel"
    :title="ariaLabel"
    v-bind="$attrs"
    @click.stop="open = true"
  >
    <i class="ph ph-info" aria-hidden="true" />
  </button>
  <Teleport to="body">
    <ExplainerPanel
      v-if="known && open"
      :explainer="explainer"
      :anchor="anchor"
      @close="open = false"
    />
  </Teleport>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import ExplainerPanel from './ExplainerPanel.vue'
import { getExplainer, hasExplainer } from '../../i18n/explainers'

// The component renders a fragment (button + Teleport), so fall-through attrs
// cannot inherit automatically — bind them explicitly onto the trigger button.
defineOptions({ inheritAttrs: false })

const props = withDefaults(
  defineProps<{ topic: string; anchor?: string; variant?: 'dot' | 'icon'; label?: string }>(),
  { variant: 'dot' },
)

const { t, locale } = useI18n()
const open = ref(false)
const ariaLabel = computed(() => props.label ?? t('explainer.open'))
// Render nothing for an unknown/new topic instead of crashing the parent.
const known = computed(() => hasExplainer(locale.value, props.topic))
const explainer = computed(() => getExplainer(locale.value, props.topic))
</script>

<style scoped>
.info-dot {
  display: inline-flex; align-items: center; justify-content: center;
  background: transparent; border: none; padding: 0 2px; cursor: pointer;
  color: var(--ash-gray-light); font-size: var(--text-body-sm); line-height: 1;
  vertical-align: baseline;
}
.info-dot:hover { color: var(--cathedral-gold); }

.info-dot--icon {
  padding: var(--space-2);
  color: inherit;
  font-size: var(--text-body-lg);
}
.info-dot--icon:hover { color: var(--bone-ivory); }
</style>
