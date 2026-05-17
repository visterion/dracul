<template>
  <div class="patterns">
    <div class="patterns__header">
      <div class="patterns__header-icon">📜</div>
      <h1 class="patterns__title font-display">Pattern Library</h1>
      <p class="patterns__subtitle">Lessons learned by the Voievod, governing how the Strigoi hunt</p>
    </div>

    <template v-if="loading">
      <v-skeleton-loader v-for="i in 3" :key="i" type="card" class="mb-3" />
    </template>

    <template v-else>
      <div v-if="actionError" class="patterns__action-error">{{ actionError }}</div>
      <!-- Pending section -->
      <div class="patterns__section-header">── pending review ({{ pendingPatterns.length }})</div>

      <div v-if="pendingPatterns.length === 0" class="patterns__empty">No patterns pending review.</div>

      <div
        v-for="pattern in pendingPatterns"
        :key="pattern.id"
        class="patterns__pending-card"
      >
        <div class="patterns__pending-header">
          <div>
            <span class="patterns__bat">🦇</span>
            <span class="patterns__strigoi-name">{{ pattern.appliesToStrigoi }}</span>
          </div>
          <span class="patterns__pending-when">proposed by Voievod, {{ daysAgo(pattern.proposedAt) }}</span>
        </div>
        <p class="patterns__lesson">{{ pattern.statement }}</p>
        <div class="patterns__evidence">
          Based on {{ pattern.evidenceCount }} cases
          <template v-if="pattern.supportedCount !== undefined">
            · {{ pattern.supportedCount }} of {{ pattern.evidenceCount }} supported
          </template>
          <template v-if="pattern.avgUpliftPercent !== null && pattern.avgUpliftPercent !== undefined">
            · avg uplift +{{ pattern.avgUpliftPercent }}%
          </template>
          &nbsp;
          <a href="#" class="patterns__cases-link" @click.prevent="() => {}">[View supporting cases →]</a>
        </div>
        <div class="patterns__pending-actions">
          <button
            class="patterns__btn-ghost"
            :disabled="pendingLoadingId === pattern.id"
            @click="handlePendingAction(pattern.id, 'defer')"
          >Defer</button>
          <button
            class="patterns__btn-secondary"
            :disabled="pendingLoadingId === pattern.id"
            @click="handlePendingAction(pattern.id, 'reject')"
          >Reject</button>
          <button
            class="patterns__btn-primary"
            :disabled="pendingLoadingId === pattern.id"
            @click="handlePendingAction(pattern.id, 'approve')"
          >{{ pendingLoadingId === pattern.id ? '…' : 'Approve &amp; Activate' }}</button>
        </div>
      </div>

      <!-- Active section -->
      <div class="patterns__section-header patterns__section-header--spaced">── active patterns ({{ activePatterns.length }})</div>

      <div class="patterns__filter-chips">
        <button
          class="patterns__chip"
          :class="{ 'patterns__chip--active': strigoiFilter === 'all' }"
          @click="strigoiFilter = 'all'"
        >
          All Strigoi ({{ activePatterns.length }})
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

      <div class="patterns__active-list">
        <div
          v-for="pattern in filteredActivePatterns"
          :key="pattern.id"
          class="patterns__active-row"
        >
          <div class="patterns__active-header" @click="toggleExpand(pattern.id)">
            <span class="patterns__bat">🦇</span>
            <span class="patterns__active-name">{{ pattern.name ?? pattern.id }}</span>
            <span class="patterns__strigoi-chip">{{ pattern.appliesToStrigoi.replace('strigoi-', '') }}</span>
            <span class="patterns__evidence-count">evidence: {{ pattern.evidenceCount }}</span>
            <span class="patterns__activated">{{ monthsAgo(pattern.proposedAt) }}</span>
            <button class="patterns__expand-btn" @click.stop="toggleExpand(pattern.id)">
              {{ expandedIds.has(pattern.id) ? '▼' : '▶' }}
            </button>
          </div>
          <div v-if="expandedIds.has(pattern.id)" class="patterns__active-body">
            <p class="patterns__active-text">{{ pattern.statement }}</p>
            <button
              class="patterns__btn-ghost"
              :disabled="activeLoadingId === pattern.id"
              @click="handleDeactivate(pattern.id)"
            >{{ activeLoadingId === pattern.id ? '…' : 'Deactivate' }}</button>
          </div>
        </div>

        <div v-if="filteredActivePatterns.length === 0" class="patterns__empty">
          No active patterns for this Strigoi.
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useApi } from '../api'
import type { Pattern } from '../api/types'

const api = useApi()
const allPatterns = ref<Pattern[]>([])
const loading = ref(true)
const strigoiFilter = ref('all')
const expandedIds = ref<Set<string>>(new Set())

const pendingLoadingId = ref<string | null>(null)
const activeLoadingId  = ref<string | null>(null)
const actionError      = ref<string | null>(null)

async function handlePendingAction(id: string, action: 'approve' | 'reject' | 'defer') {
  pendingLoadingId.value = id
  actionError.value = null
  try {
    await api.patchPattern(id, action)
    allPatterns.value = allPatterns.value.filter(p => p.id !== id)
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : 'Action failed'
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
    actionError.value = e instanceof Error ? e.message : 'Deactivate failed'
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

function daysAgo(isoString: string): string {
  const days = Math.floor((Date.now() - new Date(isoString).getTime()) / 86_400_000)
  if (days === 0) return 'today'
  if (days === 1) return 'yesterday'
  return `${days} days ago`
}

function monthsAgo(isoString: string): string {
  const months = Math.floor((Date.now() - new Date(isoString).getTime()) / (30 * 86_400_000))
  if (months === 0) return 'this month'
  if (months === 1) return '1 month ago'
  return `${months} months ago`
}
</script>

<style scoped>
.patterns {
  max-width: 960px;
  margin: 0 auto;
  padding: 28px 32px;
}

.patterns__header { margin-bottom: 28px; }
.patterns__header-icon { color: var(--cathedral-gold); font-size: 20px; margin-bottom: 6px; }
.patterns__title {
  font-size: 36px;
  font-weight: 400;
  color: var(--bone-ivory);
  margin: 0 0 4px 0;
}
.patterns__subtitle { font-size: 14px; color: var(--bone-ivory-dim); margin: 0; }

.patterns__section-header {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ash-gray);
  letter-spacing: 0.05em;
  margin: 20px 0 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.patterns__section-header::after {
  content: '';
  flex: 1;
  height: 1px;
  background: rgba(255, 255, 255, 0.06);
}
.patterns__section-header--spaced { margin-top: 28px; }

.patterns__empty { font-size: 13px; color: var(--ash-gray); font-style: italic; }

.patterns__pending-card {
  background: var(--crypt-black-elevated);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-left: 2px solid var(--cathedral-gold);
  border-radius: 2px;
  padding: 16px 20px;
  margin-bottom: 12px;
}

.patterns__pending-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
.patterns__bat { color: var(--cathedral-gold); margin-right: 6px; }
.patterns__strigoi-name { font-family: var(--font-mono); font-size: 13px; font-weight: 500; }
.patterns__pending-when { font-size: 11px; color: var(--ash-gray); }

.patterns__lesson {
  font-size: 13px;
  color: var(--bone-ivory);
  line-height: 1.6;
  font-style: italic;
  margin: 0 0 10px 0;
}

.patterns__evidence {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ash-gray);
  margin-bottom: 12px;
}

.patterns__cases-link {
  color: var(--blood-crimson);
  text-decoration: none;
}
.patterns__cases-link:hover { color: var(--blood-crimson-bright); }

.patterns__pending-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.patterns__btn-primary {
  padding: 6px 14px;
  background: var(--blood-crimson);
  border: none;
  border-radius: 2px;
  color: var(--bone-ivory);
  font-size: 12px;
  cursor: pointer;
  font-family: var(--font-body);
}
.patterns__btn-primary:hover { background: var(--blood-crimson-bright); }

.patterns__btn-secondary {
  padding: 6px 14px;
  background: none;
  border: 1px solid var(--ash-gray);
  border-radius: 2px;
  color: var(--bone-ivory-dim);
  font-size: 12px;
  cursor: pointer;
  font-family: var(--font-body);
}
.patterns__btn-secondary:hover { border-color: var(--cathedral-gold); color: var(--cathedral-gold); }

.patterns__btn-ghost {
  padding: 6px 14px;
  background: none;
  border: none;
  color: var(--ash-gray);
  font-size: 12px;
  cursor: pointer;
  font-family: var(--font-body);
}
.patterns__btn-ghost:hover { color: var(--bone-ivory-dim); }

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

.patterns__active-list {
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 2px;
  overflow: hidden;
}

.patterns__active-row {
  border-bottom: 1px solid rgba(255, 255, 255, 0.04);
}
.patterns__active-row:last-child { border-bottom: none; }

.patterns__active-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: var(--crypt-black-elevated);
  cursor: pointer;
  transition: background 0.1s;
}
.patterns__active-header:hover { background: rgba(184, 148, 92, 0.04); }

.patterns__active-name {
  font-family: var(--font-mono);
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
  font-family: var(--font-mono);
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

.patterns__action-error {
  color: var(--blood-crimson);
  font-size: var(--text-micro);
  margin-bottom: var(--space-4);
}
</style>
