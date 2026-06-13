<template>
  <div class="patterns content-inner">
    <PageHead :sub="t('patterns.subtitle')">
      <template #eyebrow>
        <BatGlyph :size="13" :dim="false" />
        {{ t('patterns.eyebrow') }}
      </template>
      <template #title>
        <span class="patterns__title">{{ t('patterns.pageTitle') }}</span>
      </template>
    </PageHead>

    <template v-if="loading">
      <v-skeleton-loader v-for="i in 3" :key="i" type="card" class="mb-3" />
    </template>

    <template v-else>
      <div v-if="actionError" class="patterns__action-error">{{ actionError }}</div>

      <!-- Pending section -->
      <div class="section-head">
        <span class="sh-rule" />
        {{ t('patterns.sections.pendingTitle') }}
        <span class="sh-sub">{{ pendingPatterns.length }} {{ t('patterns.sections.pendingCount') }}</span>
      </div>

      <div v-if="pendingPatterns.length === 0" class="empty small">
        <p class="em-text">{{ t('patterns.empty.pending') }}</p>
      </div>

      <div class="stack-5" style="margin-bottom: var(--space-10)">
        <PatternCard
          v-for="pattern in pendingPatterns"
          :key="pattern.id"
          :pattern="pattern"
          :pending="true"
          :loading="pendingLoadingId === pattern.id"
          data-testid="pending-pattern-card"
          @act="(action) => handlePendingAction(pattern.id, action)"
          @view-cases="openCases(pattern)"
        />
      </div>

      <!-- Active section -->
      <div class="section-head">
        <span class="sh-rule" />
        {{ t('patterns.sections.activeTitle') }}
        <span class="sh-sub">{{ activePatterns.length }} {{ t('patterns.sections.activeCount') }}</span>
      </div>

      <div class="patterns__filter-chips">
        <button
          class="patterns__chip"
          :class="{ 'patterns__chip--active': strigoiFilter === 'all' }"
          @click="strigoiFilter = 'all'"
        >
          {{ t('patterns.filter.allStrigoi', { n: activePatterns.length }) }}
        </button>
        <button
          v-for="name in strigoiNames"
          :key="name"
          class="patterns__chip"
          :class="{ 'patterns__chip--active': strigoiFilter === name }"
          @click="strigoiFilter = name"
        >
          {{ name.replace('strigoi-', '') }} ({{ activePatterns.filter(p => p.appliesToStrigoi === name).length }})
        </button>
      </div>

      <div class="stack-4">
        <div
          v-for="pattern in filteredActivePatterns"
          :key="pattern.id"
          class="patterns__active-row"
          data-testid="active-pattern-row"
        >
          <div
            class="patterns__active-header"
            data-testid="active-pattern-expand"
            @click="toggleExpand(pattern.id)"
          >
            <BatGlyph :size="13" :dim="false" class="patterns__bat" />
            <span class="patterns__active-name mono">{{ pattern.name ?? pattern.id }}</span>
            <span class="patterns__strigoi-chip">{{ pattern.appliesToStrigoi.replace('strigoi-', '') }}</span>
            <span class="patterns__evidence-count mono">{{ t('patterns.evidenceCount', { n: pattern.evidenceCount }) }}</span>
            <span class="patterns__activated">{{ monthsAgo(pattern.proposedAt) }}</span>
            <button class="patterns__expand-btn" @click.stop="toggleExpand(pattern.id)">
              {{ expandedIds.has(pattern.id) ? '▼' : '▶' }}
            </button>
          </div>
          <div v-if="expandedIds.has(pattern.id)" class="patterns__active-body">
            <p class="patterns__active-text">{{ pattern.statement }}</p>
            <button
              class="btn btn-ghost"
              :disabled="activeLoadingId === pattern.id"
              @click="handleDeactivate(pattern.id)"
            >{{ activeLoadingId === pattern.id ? t('patterns.buttons.loading') : t('patterns.buttons.deactivate') }}</button>
          </div>
        </div>

        <div v-if="filteredActivePatterns.length === 0" class="empty small">
          <p class="em-text">{{ t('patterns.empty.active') }}</p>
        </div>
      </div>
    </template>

    <PatternCasesDialog
      v-model="casesOpen"
      :pattern="casesPattern"
      :cases="cases"
      :loading="casesLoading"
      :error="casesError"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api'
import type { Pattern, PatternAction, PatternCase } from '../api/types'
import PageHead from '../components/common/PageHead.vue'
import BatGlyph from '../components/common/BatGlyph.vue'
import PatternCard from '../components/common/PatternCard.vue'
import PatternCasesDialog from '../components/common/PatternCasesDialog.vue'
import { useRelativeTime } from '../composables/useRelativeTime'

const { t } = useI18n()
const { monthsAgo } = useRelativeTime()
const api = useApi()
const allPatterns = ref<Pattern[]>([])
const loading = ref(true)
const strigoiFilter = ref('all')
const expandedIds = ref<Set<string>>(new Set())

const pendingLoadingId = ref<string | null>(null)
const activeLoadingId  = ref<string | null>(null)
const actionError      = ref<string | null>(null)

// Supporting-cases dialog (state owned here)
const casesOpen    = ref(false)
const casesPattern = ref<Pattern | null>(null)
const cases        = ref<PatternCase[]>([])
const casesLoading = ref(false)
const casesError   = ref(false)

async function openCases(pattern: Pattern) {
  casesPattern.value = pattern
  cases.value = []
  casesError.value = false
  casesLoading.value = true
  casesOpen.value = true
  try {
    cases.value = await api.getPatternCases(pattern.id)
  } catch {
    casesError.value = true
  } finally {
    casesLoading.value = false
  }
}

async function handlePendingAction(id: string, action: PatternAction) {
  if (action === 'deactivate') return  // only for active cards
  pendingLoadingId.value = id
  actionError.value = null
  try {
    await api.patchPattern(id, action)
    allPatterns.value = allPatterns.value.filter(p => p.id !== id)
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : t('patterns.actionError.failed')
  } finally {
    pendingLoadingId.value = null
  }
}

async function handleDeactivate(id: string) {
  activeLoadingId.value = id
  actionError.value = null
  try {
    await api.patchPattern(id, 'deactivate')
    allPatterns.value = allPatterns.value.map(p =>
      p.id === id ? { ...p, status: 'REJECTED' as const } : p
    )
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : t('patterns.actionError.deactivateFailed')
  } finally {
    activeLoadingId.value = null
  }
}

onMounted(async () => {
  try {
    allPatterns.value = await api.getPatterns()
  } finally {
    loading.value = false
  }
})

const pendingPatterns = computed(() =>
  allPatterns.value.filter(p => p.status === 'PENDING')
)

const activePatterns = computed(() =>
  allPatterns.value.filter(p => p.status === 'ACTIVE')
)

const strigoiNames = computed(() =>
  [...new Set(activePatterns.value.map(p => p.appliesToStrigoi))].sort()
)

const filteredActivePatterns = computed(() =>
  strigoiFilter.value === 'all'
    ? activePatterns.value
    : activePatterns.value.filter(p => p.appliesToStrigoi === strigoiFilter.value)
)

function toggleExpand(id: string) {
  const next = new Set(expandedIds.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  expandedIds.value = next
}
</script>

<style scoped>
.patterns {
  max-width: 960px;
  margin: 0 auto;
  padding: 28px 32px;
}

.patterns__action-error {
  color: var(--blood-crimson);
  font-size: var(--text-micro);
  margin-bottom: var(--space-4);
}

.patterns__filter-chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.patterns__chip {
  padding: 4px 10px;
  border-radius: 2px;
  font-size: 11px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: var(--ash-gray);
  cursor: pointer;
  background: none;
  font-family: var(--font-body);
  transition: border-color 0.1s, color 0.1s;
}
.patterns__chip--active { border-color: var(--cathedral-gold); color: var(--cathedral-gold); }
.patterns__chip:hover:not(.patterns__chip--active) { color: var(--bone-ivory-dim); }

.patterns__active-row {
  border-bottom: 1px solid rgba(255, 255, 255, 0.04);
  background: var(--crypt-black-elevated);
  border: var(--hairline);
  border-radius: 4px;
}

.patterns__active-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  cursor: pointer;
  transition: background 0.1s;
}
.patterns__active-header:hover { background: rgba(184, 148, 92, 0.04); }

.patterns__bat { flex-shrink: 0; }

.patterns__active-name {
  font-size: 13px;
  color: var(--bone-ivory);
  flex: 1;
}

.patterns__strigoi-chip {
  padding: 2px 8px;
  border: 1px solid rgba(184, 148, 92, 0.3);
  border-radius: 2px;
  font-size: 11px;
  color: var(--cathedral-gold);
  font-family: var(--font-mono);
}

.patterns__evidence-count {
  font-size: 11px;
  color: var(--ash-gray);
  min-width: 80px;
}
.patterns__activated {
  font-size: 11px;
  color: var(--ash-gray);
  min-width: 110px;
}
.patterns__expand-btn {
  background: none;
  border: none;
  color: var(--ash-gray);
  cursor: pointer;
  font-size: 12px;
  flex-shrink: 0;
}

.patterns__active-body {
  padding: 12px 16px 12px 36px;
  background: rgba(255, 255, 255, 0.01);
  border-top: 1px solid rgba(255, 255, 255, 0.04);
}
.patterns__active-text {
  font-size: 13px;
  color: var(--bone-ivory-dim);
  line-height: 1.6;
  font-style: italic;
  margin: 0 0 10px 0;
}

@media (max-width: 959.98px) {
  .patterns { padding: 16px; }
  .patterns__active-header { flex-wrap: wrap; }
}
</style>
