<template>
  <div v-if="loading" class="content-inner prose-width vd-loading">
    <v-skeleton-loader v-for="n in 3" :key="n" type="card" color="surface" class="vd-loading__item" />
  </div>

  <div v-else-if="fetchError" class="content-inner prose-width vd-notfound" role="alert">
    <p>{{ t('verdict.loadError') }}</p>
    <router-link to="/" class="vd-notfound__link">{{ t('verdict.notFound.backLink') }}</router-link>
  </div>

  <div v-else-if="!verdict" class="content-inner prose-width vd-notfound">
    <p>{{ t('verdict.notFound.message') }}</p>
    <router-link to="/" class="vd-notfound__link">{{ t('verdict.notFound.backLink') }}</router-link>
  </div>

  <article v-else class="content-inner prose-width vd">
    <BackLink @click="onBack">{{ t('verdict.breadcrumb.chronicle') }}</BackLink>

    <PageHead>
      <template #eyebrow>
        <span class="eb-glyph"><BatGlyph :size="13" /></span>
        {{ t('verdict.eyebrow', { count: verdict.contributingStrigoi.length }) }}
      </template>
      <template #title>
        <span class="mono title-ticker">{{ verdict.symbol }}</span>
        <span class="title-name">{{ verdict.companyName }}</span>
      </template>
    </PageHead>

    <div class="verdict-tags">
      <TagPill tone="gold">{{ t('verdict.tags.consensus', { value: verdict.consensusScore.toFixed(2) }) }}</TagPill>
      <TagPill tone="ash">{{ t('verdict.tags.horizon', { value: verdict.horizon }) }}</TagPill>
      <TagPill v-for="type in verdict.anomalyTypes" :key="type" tone="crimson">{{ type }}</TagPill>
    </div>

    <div class="verdict-grid">
      <!-- Left column: prose + signals/risks + notes -->
      <div class="verdict-main">
        <div class="verdict-prose" data-testid="vd-prose">
          <p v-for="(para, i) in paragraphs" :key="i" :class="i === 0 ? 'lead drop' : ''">{{ para }}</p>
        </div>

        <div class="section-head"><span class="sh-rule" />{{ t('verdict.sections.signals') }}</div>
        <ul class="vd-list vd-list--signals">
          <li v-for="(s, i) in verdict.signals" :key="i">{{ s }}</li>
        </ul>

        <div class="section-head"><span class="sh-rule" />{{ t('verdict.sections.risks') }}</div>
        <ul class="vd-list vd-list--risks">
          <li v-for="(r, i) in verdict.risks" :key="i">{{ r }}</li>
        </ul>

        <div class="section-head"><span class="sh-rule" />{{ t('verdict.sidebar.notesTitle') }}</div>
        <ul v-if="notes.length" class="vd-notes-list" data-testid="vd-notes-list">
          <li v-for="n in notes" :key="n.id" class="vd-notes-item">
            <div class="vd-notes-body">{{ n.body }}</div>
            <div class="vd-notes-meta">{{ relativeTime(n.createdAt) }}</div>
          </li>
        </ul>
        <p v-else class="vd-notes-empty">{{ t('verdict.sidebar.notesEmpty') }}</p>
        <textarea
          v-model="noteDraft"
          class="vd-notes-input"
          :placeholder="t('verdict.sidebar.notesPlaceholder')"
          :aria-label="t('verdict.sidebar.notesTitle')"
          rows="3"
          maxlength="4000"
          data-testid="vd-note-input"
        />
        <button
          class="btn btn-secondary"
          :disabled="!noteDraft.trim() || noteSubmitting"
          data-testid="vd-note-submit"
          @click="onAddNote"
        >{{ t('verdict.sidebar.addNote') }}</button>
        <p v-if="noteError" class="vd-error" role="alert">{{ noteError }}</p>
      </div>

      <!-- Right column: aside -->
      <aside class="verdict-aside">
        <div class="card">
          <div class="aside-ring">
            <ConsensusRing :value="verdict.consensusScore" :size="84" />
            <div class="aside-ring-label">{{ t('verdict.aside.consensusLabel') }}</div>
          </div>
        </div>

        <div class="card">
          <div class="section-head"><span class="sh-rule" />{{ t('verdict.aside.glanceTitle') }}</div>
          <div class="kv-list">
            <div class="kv-row">
              <span class="kv-k">{{ t('verdict.facts.convergence') }}</span>
              <span class="kv-v mono">{{ verdict.contributingStrigoi.length }}</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('verdict.facts.consensus') }}</span>
              <span class="kv-v mono">{{ verdict.consensusScore.toFixed(2) }}</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('verdict.facts.avgConfidence') }}</span>
              <span class="kv-v mono">{{ verdict.avgConfidence.toFixed(2) }}</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('verdict.facts.horizon') }}</span>
              <span class="kv-v mono">{{ verdict.horizon }}</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('verdict.facts.anomalyClass') }}</span>
              <span class="kv-v mono">{{ verdict.anomalyTypes.join(', ') }}</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('verdict.facts.currentPrice') }}</span>
              <span class="kv-v mono">${{ verdict.currentPrice.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}</span>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="section-head"><span class="sh-rule" />{{ t('verdict.aside.contributorsTitle') }}</div>
          <div class="contributors">
            <button
              v-for="c in verdict.contributingDetails"
              :key="c.name"
              class="contributor"
              data-testid="vd-contributor"
              @click="onOpenStrigoi(c.name)"
            >
              <BatGlyph :size="13" />
              <span class="cb-name mono">{{ c.name }}</span>
              <span class="cb-conf mono">{{ c.confidence.toFixed(2) }}</span>
            </button>
          </div>
        </div>

        <div class="card">
          <div class="section-head"><span class="sh-rule" />{{ t('verdict.sidebar.decisionTitle') }}</div>
          <div v-if="currentDecision" class="vd-decision-badge" data-testid="vd-decision-badge">
            {{ currentDecision.decision }} · {{ relativeTime(currentDecision.decidedAt) }}
          </div>
          <div class="vd-decision-buttons">
            <button
              v-for="opt in decisionOptions"
              :key="opt.value"
              class="btn btn-block"
              :class="opt.cssClass"
              :disabled="decisionSubmitting"
              :data-testid="`vd-decide-${opt.value.toLowerCase()}`"
              @click="onDecision(opt.value)"
            >{{ opt.label }}</button>
          </div>
          <p v-if="decisionError" class="vd-error" role="alert">{{ decisionError }}</p>
        </div>

        <button
          class="btn btn-primary btn-block"
          :disabled="watchlistSubmitting"
          data-testid="vd-add-watchlist"
          @click="onAddToWatchlist"
        >{{ t('verdict.aside.addToWatchlist') }}</button>
        <p v-if="watchlistError" class="vd-error" role="alert">{{ watchlistError }}</p>
        <p v-else-if="watchlistAdded" class="vd-success" role="status" data-testid="vd-watchlist-added">
          {{ t('verdict.aside.watchlistAdded') }}
        </p>
      </aside>
    </div>
  </article>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { VerdictDetail, VerdictDecision, VerdictNote, DecisionResponse } from '../api/types'
import { useApi } from '../api'
import { useRelativeTime } from '../composables/useRelativeTime'
import BackLink from '../components/common/BackLink.vue'
import PageHead from '../components/common/PageHead.vue'
import TagPill from '../components/common/TagPill.vue'
import BatGlyph from '../components/common/BatGlyph.vue'
import ConsensusRing from '../components/common/ConsensusRing.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const api = useApi()
const { relativeTime } = useRelativeTime()

const verdict = ref<VerdictDetail | null>(null)
const loading = ref(true)
const fetchError = ref<string | null>(null)

const notes = ref<VerdictNote[]>([])
const noteDraft = ref('')
const noteSubmitting = ref(false)
const noteError = ref<string | null>(null)

const currentDecision = ref<DecisionResponse | null>(null)
const decisionSubmitting = ref(false)
const decisionError = ref<string | null>(null)

const watchlistSubmitting = ref(false)
const watchlistError = ref<string | null>(null)
const watchlistAdded = ref(false)

const paragraphs = computed(() =>
  (verdict.value?.summary ?? '')
    .split(/\n{2,}/)
    .map(p => p.trim())
    .filter(Boolean),
)

const decisionOptions = computed<{ value: VerdictDecision; label: string; cssClass: string }[]>(() => [
  { value: 'TRACK',       label: t('verdict.decisions.track'),       cssClass: 'btn-primary'       },
  { value: 'INTERESTING', label: t('verdict.decisions.interesting'), cssClass: 'btn-secondary'     },
  { value: 'ACTED',       label: t('verdict.decisions.acted'),       cssClass: 'btn-secondary'     },
  { value: 'DISMISS',     label: t('verdict.decisions.dismiss'),     cssClass: 'btn-crimson-ghost' },
])

onMounted(async () => {
  const id = route.params.id as string
  try {
    verdict.value = await api.getVerdictDetail(id)
    if (verdict.value) {
      notes.value = await api.getVerdictNotes(id)
    }
  } catch (e) {
    fetchError.value = (e as Error).message
  } finally {
    loading.value = false
  }
})

function onBack() {
  router.push('/')
}

function onOpenStrigoi(name: string) {
  router.push({ name: 'strigoi-detail', params: { name } })
}

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
      watchlistAdded.value = true
    }
  } catch (e) {
    decisionError.value = (e as Error).message
  } finally {
    decisionSubmitting.value = false
  }
}

async function onAddToWatchlist() {
  if (!verdict.value) return
  watchlistSubmitting.value = true
  watchlistError.value = null
  try {
    await api.createWatchlistItem({
      symbol: verdict.value.symbol,
      tag: 'TRACKING',
      sourceVerdictId: verdict.value.id,
    })
    watchlistAdded.value = true
  } catch (e) {
    watchlistError.value = (e as Error).message
  } finally {
    watchlistSubmitting.value = false
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
.vd-loading__item { margin-bottom: var(--space-4); }

.vd-notfound { color: var(--ash-gray); }
.vd-notfound__link {
  color: var(--blood-crimson); text-decoration: none; font-size: var(--text-body-sm);
}
.vd-notfound__link:hover { color: var(--blood-crimson-bright); }

/* ── Verdict layout (ported from styles.css:227-244, scoped) ── */
.verdict-tags { display: flex; gap: var(--space-3); margin: 0 0 var(--space-6); flex-wrap: wrap; }
.verdict-grid { display: grid; grid-template-columns: 1fr 320px; gap: var(--space-8); align-items: start; }
.verdict-main { min-width: 0; }
.verdict-prose { max-width: 70ch; }
.verdict-prose p {
  color: var(--bone-ivory); font-size: var(--text-body-lg); line-height: 1.68;
  margin: 0 0 var(--space-5); text-wrap: pretty;
}
.verdict-prose .drop::first-letter {
  font-family: var(--font-display); font-size: 4.4em; float: left; line-height: 0.78;
  padding: 6px 10px 0 0; color: var(--blood-crimson); font-weight: 600;
}
.verdict-aside { position: sticky; top: var(--space-6); display: flex; flex-direction: column; gap: var(--space-5); }
.aside-ring { display: flex; flex-direction: column; align-items: center; gap: var(--space-3); }
.aside-ring-label { font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.12em; color: var(--ash-gray); }
.kv-list { display: flex; flex-direction: column; gap: var(--space-3); }
.kv-row { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-4); }
.kv-k { font-size: var(--text-body-sm); color: var(--ash-gray); }
.kv-v { font-size: var(--text-body-sm); color: var(--bone-ivory); text-align: right; }
.contributors { display: flex; flex-direction: column; gap: var(--space-1); }
.contributor {
  display: flex; align-items: center; gap: var(--space-3); font-size: var(--text-body-sm);
  padding: var(--space-2); background: none; border: none; cursor: pointer; text-align: left;
  border-radius: 4px; transition: background var(--transition-fast); width: 100%;
}
.contributor:hover { background: rgba(184,148,92,0.05); }
.contributor:focus-visible { outline: 2px solid var(--blood-crimson); outline-offset: 2px; }
.cb-name { color: var(--blood-crimson); flex: 1; }
.contributor:hover .cb-name { color: var(--blood-crimson-bright); }
.cb-conf { color: var(--bone-ivory); }

/* Signals / risks lists */
.vd-list {
  list-style: none; margin: 0 0 var(--space-4) 0; padding: 0;
  display: flex; flex-direction: column; gap: var(--space-2); max-width: 70ch;
}
.vd-list li {
  font-size: var(--text-body); line-height: 1.6;
  padding-left: var(--space-4); position: relative;
}
.vd-list li::before { content: '•'; position: absolute; left: 0; }
.vd-list--signals li { color: var(--bone-ivory); }
.vd-list--signals li::before { color: var(--cathedral-gold); }
.vd-list--risks li { color: var(--bone-ivory-dim); }
.vd-list--risks li::before { color: var(--ash-gray); }

/* Decision buttons */
.vd-decision-buttons { display: flex; flex-direction: column; gap: var(--space-2); }
.vd-decision-badge {
  font-family: var(--font-mono); font-size: var(--text-micro); color: var(--cathedral-gold);
  border: 1px solid rgba(184, 148, 92, 0.4); border-radius: 2px; padding: 2px 6px;
  margin-bottom: var(--space-3); letter-spacing: 0.05em; text-transform: uppercase;
  display: inline-block;
}
.btn[disabled] { opacity: 0.5; cursor: not-allowed; }

.vd-error { color: var(--blood-crimson); font-size: var(--text-micro); margin: var(--space-2) 0 0 0; }
.vd-success { color: var(--cathedral-gold); font-size: var(--text-micro); margin: var(--space-2) 0 0 0; }

/* Notes */
.vd-notes-list {
  list-style: none; padding: 0; margin: 0 0 var(--space-3) 0;
  display: flex; flex-direction: column; gap: var(--space-2);
}
.vd-notes-item {
  background-color: var(--crypt-black-deep);
  border-left: 2px solid rgba(184, 148, 92, 0.4);
  padding: var(--space-2) var(--space-3);
}
.vd-notes-body { font-size: var(--text-body-sm); color: var(--bone-ivory); line-height: 1.5; }
.vd-notes-meta { font-size: var(--text-micro); color: var(--ash-gray); margin-top: var(--space-1); }
.vd-notes-empty { font-size: var(--text-body-sm); color: var(--ash-gray); margin: 0 0 var(--space-3) 0; font-style: italic; }
.vd-notes-input {
  width: 100%; background-color: var(--crypt-black-deep);
  border: 1px solid rgba(255, 255, 255, 0.1); border-radius: 4px;
  color: var(--bone-ivory); font-family: var(--font-body);
  font-size: var(--text-body-sm); padding: var(--space-3);
  resize: vertical; box-sizing: border-box; margin-bottom: var(--space-2);
}
.vd-notes-input::placeholder { color: var(--ash-gray); }
.vd-notes-input:focus { outline: none; border-color: var(--cathedral-gold); }

@media (max-width: 959.98px) {
  .verdict-grid { grid-template-columns: 1fr; gap: var(--space-6); }
  .verdict-aside { position: static; }
}
</style>
