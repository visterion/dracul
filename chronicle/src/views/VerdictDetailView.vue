<template>
  <div v-if="loading" class="vd-loading">
    <v-skeleton-loader v-for="n in 3" :key="n" type="card" color="surface" class="vd-loading__item" />
  </div>

  <div v-else-if="!verdict" class="vd-notfound">
    <p>{{ t('verdict.notFound.message') }}</p>
    <router-link to="/" class="vd-notfound__link">{{ t('verdict.notFound.backLink') }}</router-link>
  </div>

  <article v-else class="vd">
    <!-- Breadcrumb -->
    <nav class="vd__breadcrumb" aria-label="Breadcrumb">
      <router-link to="/" class="vd__bc-link">{{ t('verdict.breadcrumb.chronicle') }}</router-link>
      <span class="vd__bc-sep">/</span>
      <span class="vd__bc-link">{{ t('verdict.breadcrumb.verdict') }}</span>
      <span class="vd__bc-sep">/</span>
      <span class="vd__bc-current">{{ verdict.symbol }}</span>
    </nav>

    <div class="vd__layout">
      <!-- Main pane -->
      <main class="vd__main">
        <header class="vd__header">
          <h1 class="vd__symbol font-display">{{ verdict.symbol }}</h1>
          <p class="vd__company">{{ verdict.companyName }}</p>
          <div class="vd__meta">
            <span v-for="type in verdict.anomalyTypes" :key="type" class="vd__badge">{{ type }}</span>
            <span class="vd__meta-sep">·</span>
            <span class="vd__meta-text">{{ t('verdict.meta.discovered') }} {{ relativeTime(verdict.createdAt) }}</span>
            <span class="vd__meta-sep">·</span>
            <span class="vd__meta-text">{{ verdict.contributingStrigoi.length }} {{ t('verdict.meta.strigoi') }}</span>
          </div>
        </header>

        <SectionHeader :label="t('verdict.sections.thesis')" />
        <p class="vd__prose">{{ verdict.summary }}</p>

        <SectionHeader :label="t('verdict.sections.signals')" />
        <ul class="vd__list vd__list--signals">
          <li v-for="(s, i) in verdict.signals" :key="i">{{ s }}</li>
        </ul>

        <SectionHeader :label="t('verdict.sections.risks')" />
        <ul class="vd__list vd__list--risks">
          <li v-for="(r, i) in verdict.risks" :key="i">{{ r }}</li>
        </ul>

        <SectionHeader :label="t('verdict.sections.contributingStrigoi')" />
        <div class="vd__contributors">
          <div v-for="c in verdict.contributingDetails" :key="c.name" class="vd__contributor">
            <div class="vd__contributor-header">
              <span class="vd__contributor-bat" aria-hidden="true">🦇</span>
              <router-link
                :to="{ name: 'strigoi-detail', params: { name: c.name } }"
                class="vd__contributor-name font-mono"
              >{{ c.name }}</router-link>
              <span class="vd__contributor-score font-mono tabular">{{ c.confidence.toFixed(2) }}</span>
            </div>
            <p class="vd__contributor-thesis">{{ c.thesis }}</p>
          </div>
        </div>
      </main>

      <!-- Sidebar -->
      <aside class="vd__sidebar" :aria-label="t('verdict.sidebar.decisionTitle')">
        <div class="vd__panel">
          <div class="vd__panel-title">{{ t('verdict.sidebar.decisionTitle') }}</div>
          <div v-if="currentDecision" class="vd__decision-badge" data-testid="vd-decision-badge">
            {{ currentDecision.decision }} · {{ relativeTime(currentDecision.decidedAt) }}
          </div>
          <div class="vd__buttons">
            <button
              v-for="opt in decisionOptions"
              :key="opt.value"
              class="vd__btn"
              :class="opt.cssClass"
              :disabled="decisionSubmitting"
              :data-testid="`vd-decide-${opt.value.toLowerCase()}`"
              @click="onDecision(opt.value)"
            >{{ opt.label }}</button>
          </div>
          <p v-if="decisionError" class="vd__error" role="alert">{{ decisionError }}</p>
        </div>

        <div class="vd__panel">
          <div class="vd__panel-title">{{ t('verdict.sidebar.notesTitle') }}</div>
          <ul v-if="notes.length" class="vd__notes-list" data-testid="vd-notes-list">
            <li v-for="n in notes" :key="n.id" class="vd__notes-item">
              <div class="vd__notes-body">{{ n.body }}</div>
              <div class="vd__notes-meta">{{ relativeTime(n.createdAt) }}</div>
            </li>
          </ul>
          <p v-else class="vd__notes-empty">{{ t('verdict.sidebar.notesEmpty') }}</p>
          <textarea
            v-model="noteDraft"
            class="vd__notes-input"
            :placeholder="t('verdict.sidebar.notesPlaceholder')"
            :aria-label="t('verdict.sidebar.notesTitle')"
            rows="3"
            maxlength="4000"
            data-testid="vd-note-input"
          />
          <button
            class="vd__btn vd__btn--secondary"
            :disabled="!noteDraft.trim() || noteSubmitting"
            data-testid="vd-note-submit"
            @click="onAddNote"
          >{{ t('verdict.sidebar.addNote') }}</button>
          <p v-if="noteError" class="vd__error" role="alert">{{ noteError }}</p>
        </div>

        <div class="vd__panel">
          <div class="vd__panel-title">{{ t('verdict.sidebar.statsTitle') }}</div>
          <table class="vd__stats">
            <tbody>
              <tr>
                <td class="vd__stats-label">{{ t('verdict.stats.currentPrice') }}</td>
                <td class="vd__stats-value font-mono tabular">
                  ${{ verdict.currentPrice.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}
                </td>
              </tr>
              <tr>
                <td class="vd__stats-label">{{ t('verdict.stats.consensus') }}</td>
                <td class="vd__stats-value font-mono tabular">{{ verdict.consensusScore.toFixed(2) }}</td>
              </tr>
              <tr>
                <td class="vd__stats-label">{{ t('verdict.stats.avgConfidence') }}</td>
                <td class="vd__stats-value font-mono tabular">{{ verdict.avgConfidence.toFixed(2) }}</td>
              </tr>
              <tr>
                <td class="vd__stats-label">{{ t('verdict.stats.timeHorizon') }}</td>
                <td class="vd__stats-value font-mono tabular">{{ verdict.horizon }}</td>
              </tr>
              <tr>
                <td class="vd__stats-label">{{ t('verdict.stats.discovered') }}</td>
                <td class="vd__stats-value font-mono tabular">{{ relativeTime(verdict.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="vd__panel">
          <div class="vd__panel-title">{{ t('verdict.sidebar.daywalkerTitle') }}</div>
          <p class="vd__daywalker-hint">{{ t('verdict.sidebar.daywalkerHint') }}</p>
        </div>
      </aside>
    </div>
  </article>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { VerdictDetail, VerdictDecision, VerdictNote, DecisionResponse } from '../api/types'
import { useApi } from '../api'
import { useRelativeTime } from '../composables/useRelativeTime'
import SectionHeader from '../components/common/SectionHeader.vue'

const { t } = useI18n()
const route = useRoute()
const api = useApi()
const { relativeTime } = useRelativeTime()

const verdict = ref<VerdictDetail | null>(null)
const loading = ref(true)

const notes = ref<VerdictNote[]>([])
const noteDraft = ref('')
const noteSubmitting = ref(false)
const noteError = ref<string | null>(null)

const currentDecision = ref<DecisionResponse | null>(null)
const decisionSubmitting = ref(false)
const decisionError = ref<string | null>(null)

const decisionOptions = computed<{ value: VerdictDecision; label: string; cssClass: string }[]>(() => [
  { value: 'TRACK',       label: t('verdict.decisions.track'),       cssClass: 'vd__btn--primary'   },
  { value: 'INTERESTING', label: t('verdict.decisions.interesting'), cssClass: 'vd__btn--secondary' },
  { value: 'ACTED',       label: t('verdict.decisions.acted'),       cssClass: 'vd__btn--secondary' },
  { value: 'DISMISS',     label: t('verdict.decisions.dismiss'),     cssClass: 'vd__btn--ghost'     },
])

onMounted(async () => {
  const id = route.params.id as string
  try {
    verdict.value = await api.getVerdictDetail(id)
    if (verdict.value) {
      notes.value = await api.getVerdictNotes(id)
    }
  } finally {
    loading.value = false
  }
})

async function onDecision(decision: VerdictDecision) {
  if (!verdict.value) return
  decisionSubmitting.value = true
  decisionError.value = null
  try {
    currentDecision.value = await api.putVerdictDecision(verdict.value.id, decision)
    if (decision === 'TRACK') {
      await api.createWatchlistItem({
        symbol: verdict.value.symbol,
        tag: 'TRACKING',
        sourceVerdictId: verdict.value.id,
      })
    }
  } catch (e) {
    decisionError.value = (e as Error).message
  } finally {
    decisionSubmitting.value = false
  }
}

async function onAddNote() {
  if (!verdict.value || !noteDraft.value.trim()) return
  noteSubmitting.value = true
  noteError.value = null
  try {
    const created = await api.addVerdictNote(verdict.value.id, noteDraft.value)
    notes.value = [created, ...notes.value]
    noteDraft.value = ''
  } catch (e) {
    noteError.value = (e as Error).message
  } finally {
    noteSubmitting.value = false
  }
}
</script>

<style scoped>
.vd-loading { max-width: 1280px; margin: 0 auto; padding: var(--space-8) var(--space-6); }
.vd-loading__item { margin-bottom: var(--space-4); }

.vd-notfound {
  max-width: 1280px; margin: 0 auto;
  padding: var(--space-8) var(--space-6); color: var(--ash-gray);
}
.vd-notfound__link {
  color: var(--blood-crimson); text-decoration: none; font-size: var(--text-body-sm);
}
.vd-notfound__link:hover { color: var(--blood-crimson-bright); }

.vd {
  max-width: 1280px; margin: 0 auto;
  padding: var(--space-6) var(--space-6) var(--space-12);
}

/* Breadcrumb */
.vd__breadcrumb {
  display: flex; align-items: center; gap: var(--space-2);
  margin-bottom: var(--space-6);
  font-size: var(--text-micro); letter-spacing: 0.02em;
}
.vd__bc-link { color: var(--ash-gray); text-decoration: none; }
.vd__bc-link:hover { color: var(--bone-ivory-dim); }
.vd__bc-sep { color: var(--ash-gray); }
.vd__bc-current { color: var(--blood-crimson); }

/* Two-pane layout */
.vd__layout {
  display: grid; grid-template-columns: 3fr 1fr;
  gap: var(--space-10); align-items: start;
}

/* Header */
.vd__symbol {
  font-size: var(--text-h1); line-height: 1.1; letter-spacing: -0.01em;
  color: var(--bone-ivory); margin: 0 0 var(--space-2) 0;
}
.vd__company { font-size: var(--text-body); color: var(--bone-ivory-dim); margin: 0 0 var(--space-4) 0; }
.vd__meta { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-2); }
.vd__badge {
  font-size: var(--text-micro); color: var(--cathedral-gold);
  border: 1px solid rgba(184, 148, 92, 0.4); border-radius: 2px;
  padding: 1px 6px; letter-spacing: 0.05em; text-transform: uppercase;
}
.vd__meta-sep { color: var(--ash-gray); font-size: var(--text-body-sm); }
.vd__meta-text { color: var(--ash-gray); font-size: var(--text-body-sm); }

/* Prose */
.vd__prose {
  font-size: var(--text-body); color: var(--bone-ivory);
  line-height: 1.7; max-width: 70ch; margin: 0 0 var(--space-4) 0;
}

/* Lists */
.vd__list {
  list-style: none; margin: 0 0 var(--space-4) 0; padding: 0;
  display: flex; flex-direction: column; gap: var(--space-2); max-width: 70ch;
}
.vd__list li {
  font-size: var(--text-body); line-height: 1.6;
  padding-left: var(--space-4); position: relative;
}
.vd__list li::before { content: '•'; position: absolute; left: 0; }
.vd__list--signals li { color: var(--bone-ivory); }
.vd__list--signals li::before { color: var(--cathedral-gold); }
.vd__list--risks li { color: var(--bone-ivory-dim); }
.vd__list--risks li::before { color: var(--ash-gray); }

/* Contributors */
.vd__contributors {
  display: flex; flex-direction: column; gap: var(--space-4); max-width: 70ch;
}
.vd__contributor {
  background-color: #1A1A22;
  border: 1px solid rgba(184, 148, 92, 0.1);
  border-radius: 4px; padding: var(--space-4) var(--space-5);
}
.vd__contributor-header {
  display: flex; align-items: baseline; gap: var(--space-2); margin-bottom: var(--space-3);
}
.vd__contributor-bat { font-size: 14px; line-height: 1; flex-shrink: 0; }
.vd__contributor-name {
  font-size: var(--text-body-sm); color: var(--blood-crimson);
  text-decoration: none; font-weight: 500;
}
.vd__contributor-name:hover { color: var(--blood-crimson-bright); }
.vd__contributor-score { font-size: var(--text-micro); color: var(--ash-gray); margin-left: auto; }
.vd__contributor-thesis {
  font-size: var(--text-body-sm); color: var(--bone-ivory-dim); line-height: 1.6; margin: 0;
}

/* Sidebar */
.vd__sidebar {
  position: sticky; top: var(--space-6);
  display: flex; flex-direction: column; gap: var(--space-4);
}
.vd__panel {
  background-color: var(--crypt-black-elevated);
  border: 1px solid rgba(184, 148, 92, 0.1);
  border-radius: 4px; padding: var(--space-4) var(--space-5);
}
.vd__panel-title {
  font-size: var(--text-micro); color: var(--ash-gray);
  letter-spacing: 0.05em; text-transform: uppercase; margin-bottom: var(--space-4);
}

/* Buttons */
.vd__buttons { display: flex; flex-direction: column; gap: var(--space-2); margin-bottom: var(--space-3); }
.vd__btn {
  width: 100%; padding: var(--space-3) var(--space-4); border-radius: 4px;
  font-size: var(--text-body-sm); font-family: var(--font-body); cursor: pointer;
  transition: background-color var(--transition-fast), color var(--transition-fast), border-color var(--transition-fast);
}
.vd__btn--primary {
  background-color: var(--blood-crimson); color: var(--bone-ivory); border: 1px solid var(--blood-crimson);
}
.vd__btn--primary:hover { background-color: var(--blood-crimson-bright); border-color: var(--blood-crimson-bright); }
.vd__btn--secondary {
  background-color: transparent; color: var(--bone-ivory); border: 1px solid var(--ash-gray);
}
.vd__btn--secondary:hover { border-color: var(--cathedral-gold); }
.vd__btn--ghost { background-color: transparent; color: var(--bone-ivory-dim); border: none; }
.vd__btn--ghost:hover { color: var(--bone-ivory); }

/* Stats table */
.vd__stats { width: 100%; border-collapse: collapse; }
.vd__stats tr + tr td { padding-top: var(--space-2); }
.vd__stats-label { font-size: var(--text-body-sm); color: var(--ash-gray); padding-right: var(--space-4); white-space: nowrap; }
.vd__stats-value { font-size: var(--text-body-sm); color: var(--bone-ivory); text-align: right; }

/* Daywalker */
.vd__daywalker-hint { font-size: var(--text-body-sm); color: var(--ash-gray); margin: 0; font-style: italic; }

.vd__decision-badge {
  font-family: var(--font-mono);
  font-size: var(--text-micro);
  color: var(--cathedral-gold);
  border: 1px solid rgba(184, 148, 92, 0.4);
  border-radius: 2px;
  padding: 2px 6px;
  margin-bottom: var(--space-3);
  letter-spacing: 0.05em;
  text-transform: uppercase;
  display: inline-block;
}
.vd__btn[disabled] { opacity: 0.5; cursor: not-allowed; }
.vd__error {
  color: var(--blood-crimson);
  font-size: var(--text-micro);
  margin: var(--space-2) 0 0 0;
}
.vd__notes-list {
  list-style: none; padding: 0; margin: 0 0 var(--space-3) 0;
  display: flex; flex-direction: column; gap: var(--space-2);
}
.vd__notes-item {
  background-color: var(--crypt-black-deep);
  border-left: 2px solid rgba(184, 148, 92, 0.4);
  padding: var(--space-2) var(--space-3);
}
.vd__notes-body { font-size: var(--text-body-sm); color: var(--bone-ivory); line-height: 1.5; }
.vd__notes-meta { font-size: var(--text-micro); color: var(--ash-gray); margin-top: var(--space-1); }
.vd__notes-empty { font-size: var(--text-body-sm); color: var(--ash-gray); margin: 0 0 var(--space-3) 0; font-style: italic; }
.vd__notes-input {
  width: 100%; background-color: var(--crypt-black-deep);
  border: 1px solid rgba(255, 255, 255, 0.1); border-radius: 4px;
  color: var(--bone-ivory); font-family: var(--font-body);
  font-size: var(--text-body-sm); padding: var(--space-3);
  resize: vertical; box-sizing: border-box;
  margin-bottom: var(--space-2);
}
.vd__notes-input::placeholder { color: var(--ash-gray); }
.vd__notes-input:focus { outline: none; border-color: var(--cathedral-gold); }
</style>
