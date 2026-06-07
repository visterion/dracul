<template>
  <article class="pattern-card" :class="pending ? 'pending' : 'active'">
    <header class="pt-head">
      <span class="pt-by">
        <BatGlyph :size="14" :dim="false" />
        <span class="mono">{{ pattern.appliesToStrigoi }}</span>
      </span>
      <span class="pt-meta">
        <template v-if="pending">
          {{ t('patterns.proposedBy', { when: daysAgo(pattern.proposedAt) }) }}
        </template>
        <template v-else>
          {{ t('patterns.activeSince', { when: monthsAgo(pattern.proposedAt) }) }}
        </template>
      </span>
    </header>

    <p class="pt-rule">{{ pattern.statement }}</p>

    <footer class="pt-foot">
      <span class="pt-stats mono">
        {{ t('patterns.evidence.basedOn', { n: pattern.evidenceCount }) }}
        <template v-if="pending && pattern.supportedCount !== undefined">
          {{ t('patterns.evidence.supported', { n: pattern.supportedCount, total: pattern.evidenceCount }) }}
        </template>
        <template v-if="pending && pattern.avgUpliftPercent != null">
          {{ t('patterns.evidence.avgUplift', { n: pattern.avgUpliftPercent }) }}
        </template>
      </span>
      <span v-if="pending" class="pt-cases" aria-hidden="true">
        {{ t('patterns.evidence.viewCases') }}
      </span>
      <div v-if="pending" class="pt-actions">
        <button class="btn btn-ghost" :disabled="loading" @click="emit('act', 'defer')">
          {{ t('patterns.buttons.defer') }}
        </button>
        <button class="btn btn-secondary" :disabled="loading" @click="emit('act', 'reject')">
          {{ t('patterns.buttons.reject') }}
        </button>
        <button class="btn btn-primary" :disabled="loading" @click="emit('act', 'approve')">
          {{ loading ? t('patterns.buttons.loading') : t('patterns.buttons.approveActivate') }}
        </button>
      </div>
    </footer>
  </article>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import BatGlyph from './BatGlyph.vue'
import { useRelativeTime } from '../../composables/useRelativeTime'
import type { Pattern, PatternAction } from '../../api/types'

const props = defineProps<{
  pattern: Pattern
  pending: boolean
  loading?: boolean
}>()

const emit = defineEmits<{
  act: [action: PatternAction]
}>()

const { t } = useI18n()
const { monthsAgo, daysAgo } = useRelativeTime()
</script>

<style scoped>
/* .pattern-card, .pt-head, .pt-by, .pt-meta, .pt-rule, .pt-foot, .pt-stats,
   .pt-cases, .pt-actions are scoped to this component per spec */
.pattern-card {
  background: var(--crypt-black-elevated);
  border: var(--hairline);
  border-radius: 4px;
  padding: var(--space-5);
}
.pattern-card.pending { border-left: 3px solid var(--cathedral-gold); }
.pattern-card.active  { border-left: 3px solid var(--signal-positive); }

.pt-head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
  flex-wrap: wrap;
}
.pt-by {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-body-sm);
  color: var(--bone-ivory);
  flex: 0 0 auto;
  white-space: nowrap;
}
.pt-meta {
  margin-left: auto;
  font-size: var(--text-micro);
  color: var(--ash-gray);
}

.pt-rule {
  color: var(--bone-ivory);
  font-size: var(--text-body);
  line-height: 1.6;
  margin: 0 0 var(--space-4);
  max-width: 90ch;
  text-wrap: pretty;
}

.pt-foot {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  flex-wrap: wrap;
}
.pt-stats {
  font-size: var(--text-micro);
  color: var(--ash-gray);
}
.pt-cases {
  font-size: var(--text-micro);
  color: var(--cathedral-gold);
  cursor: pointer;
  text-decoration: none;
}
.pt-cases:hover { color: var(--cathedral-gold-bright, var(--cathedral-gold)); }

.pt-actions {
  margin-left: auto;
  display: flex;
  gap: var(--space-2);
}
.pt-actions .btn { padding: 8px 14px; }

@media (max-width: 599.98px) {
  .pt-actions { width: 100%; flex-wrap: wrap; }
  .pt-actions .btn { flex: 1; justify-content: center; }
}
</style>
