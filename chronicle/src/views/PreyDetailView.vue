<template>
  <!-- Loading state -->
  <div v-if="loading" class="content-inner prose-width pd-loading">
    <v-skeleton-loader v-for="n in 3" :key="n" type="card" color="surface" class="pd-loading__item" />
  </div>

  <!-- Not-found state -->
  <div v-else-if="!prey" class="content-inner prose-width pd-notfound">
    <BatGlyph :size="28" class="pd-notfound__glyph" />
    <p>{{ t('prey.notFound') }}</p>
    <BackLink @click="onBack">{{ t('verdict.notFound.backLink') }}</BackLink>
  </div>

  <!-- Main content -->
  <article v-else class="content-inner pd">
    <BackLink @click="onBack">{{ t('verdict.breadcrumb.chronicle') }}</BackLink>

    <PageHead>
      <template #eyebrow>
        {{ t('prey.eyebrow', { anomalyType: prey.anomalyType }) }}
      </template>
      <template #title>
        <span class="mono title-ticker">{{ prey.symbol }}</span>
        <span class="title-name">{{ prey.companyName }}</span>
      </template>
    </PageHead>

    <div class="two-col">
      <!-- Left column -->
      <div class="stack-5">
        <!-- Die These -->
        <div class="card">
          <div class="section-head"><span class="sh-rule" />{{ t('prey.thesisTitle') }}</div>
          <p class="lead-prose">{{ prey.thesis }}</p>
        </div>

        <!-- Signals + Risks grid -->
        <div class="card">
          <div class="sr-grid">
            <div class="sr-col">
              <div class="sr-head">{{ t('prey.signals') }}</div>
              <ul class="sr-list sr-list--signals">
                <li v-for="(s, i) in prey.signals" :key="i">{{ s }}</li>
              </ul>
            </div>
            <div class="sr-col">
              <div class="sr-head">{{ t('prey.risks') }}</div>
              <ul class="sr-list sr-list--risks">
                <li v-for="(r, i) in prey.risks" :key="i">{{ r }}</li>
              </ul>
            </div>
          </div>
        </div>
      </div>

      <!-- Right column -->
      <div class="stack-5">
        <!-- Confidence card -->
        <div class="card">
          <div class="section-head"><span class="sh-rule" />{{ t('prey.confidenceTitle') }}</div>
          <ConfidenceBar :score="prey.confidence" class="pd-conf" />
          <div class="kv-list pd-kv">
            <div class="kv-row">
              <span class="kv-k">{{ t('prey.facts.anomaly') }}</span>
              <span class="kv-v mono">{{ prey.anomalyType }}</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('prey.facts.horizon') }}</span>
              <span class="kv-v mono">{{ prey.horizon }}</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('prey.facts.firstSeen') }}</span>
              <span class="kv-v mono">{{ relativeTime(prey.discoveredAt) }}</span>
            </div>
          </div>
        </div>

        <!-- Found by -->
        <div class="card">
          <div class="section-head"><span class="sh-rule" />{{ t('prey.foundBy') }}</div>
          <button
            class="brood-row big"
            data-testid="pd-found-by"
            @click="onOpenStrigoi(prey.discoveredBy)"
          >
            <BatGlyph :size="13" />
            <span class="brood-name mono">{{ prey.discoveredBy }}</span>
            <i class="ph ph-caret-right brood-caret" />
          </button>
        </div>

        <!-- Add to watchlist -->
        <button
          class="btn btn-primary btn-block"
          :disabled="watchlistSubmitting"
          data-testid="pd-add-watchlist"
          @click="onAddToWatchlist"
        >{{ t('prey.addToWatchlist') }}</button>
        <p v-if="watchlistError" class="pd-error" role="alert">{{ watchlistError }}</p>
        <p v-else-if="watchlistAdded" class="pd-success" role="status" data-testid="pd-watchlist-added">
          {{ t('verdict.aside.watchlistAdded') }}
        </p>
      </div>
    </div>
  </article>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { Prey } from '../api/types'
import { useApi } from '../api'
import { useChronicleStore } from '../stores/chronicle'
import { useRelativeTime } from '../composables/useRelativeTime'
import BackLink from '../components/common/BackLink.vue'
import PageHead from '../components/common/PageHead.vue'
import BatGlyph from '../components/common/BatGlyph.vue'
import ConfidenceBar from '../components/common/ConfidenceBar.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const api = useApi()
const store = useChronicleStore()
const { relativeTime } = useRelativeTime()

const prey = ref<Prey | null>(null)
const loading = ref(true)

const watchlistSubmitting = ref(false)
const watchlistError = ref<string | null>(null)
const watchlistAdded = ref(false)

onMounted(async () => {
  const id = route.params.id as string

  // Deep-link / refresh: store may be empty.
  // Always load when prey list is empty — store.load() is idempotent
  // (sets loading=true, awaits the API call, then sets loading=false).
  if (store.prey.length === 0) {
    await store.load()
  }

  prey.value = store.prey.find(p => p.id === id) ?? null
  loading.value = false
})

function onBack() {
  router.back()
}

function onOpenStrigoi(name: string) {
  router.push({ name: 'strigoi-detail', params: { name } })
}

async function onAddToWatchlist() {
  if (!prey.value) return
  watchlistSubmitting.value = true
  watchlistError.value = null
  try {
    await api.createWatchlistItem({
      symbol: prey.value.symbol,
      tag: 'TRACKING',
    })
    watchlistAdded.value = true
  } catch (e) {
    watchlistError.value = (e as Error).message
  } finally {
    watchlistSubmitting.value = false
  }
}
</script>

<style scoped>
.pd-loading__item { margin-bottom: var(--space-4); }

.pd-notfound {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-4);
  color: var(--ash-gray);
}
.pd-notfound__glyph { opacity: 0.4; }

/* ── Two-column layout (port styles.css two-col) ── */
.two-col {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: var(--space-8);
  align-items: start;
}

/* ── Vertical stack with gap-5 ── */
.stack-5 {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  min-width: 0;
}

/* ── Lead prose (thesis paragraph) ── */
.lead-prose {
  color: var(--bone-ivory);
  font-size: var(--text-body-lg);
  line-height: 1.68;
  margin: 0;
  max-width: 70ch;
  text-wrap: pretty;
}

/* ── Signals / Risks two-col grid ── */
.sr-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-6);
}

.sr-head {
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--ash-gray);
  margin-bottom: var(--space-3);
}

.sr-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.sr-list li {
  font-size: var(--text-body-sm);
  line-height: 1.6;
  padding-left: var(--space-4);
  position: relative;
  color: var(--bone-ivory);
}

.sr-list li::before {
  content: '•';
  position: absolute;
  left: 0;
}

.sr-list--signals li::before { color: var(--cathedral-gold); }
.sr-list--risks li { color: var(--bone-ivory-dim); }
.sr-list--risks li::before { color: var(--ash-gray); }

/* ── Right column: confidence spacing ── */
.pd-conf { margin-bottom: var(--space-5); }

/* ── KV list (scoped; mirrors VerdictDetailView) ── */
.kv-list { display: flex; flex-direction: column; gap: var(--space-3); }
.kv-row { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-4); }
.kv-k { font-size: var(--text-body-sm); color: var(--ash-gray); }
.kv-v { font-size: var(--text-body-sm); color: var(--bone-ivory); text-align: right; }

/* ── Brood-row (port styles.css:221-225 + big modifier) ── */
.brood-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2);
  background: none;
  border: none;
  cursor: pointer;
  text-align: left;
  border-radius: 4px;
  transition: background var(--transition-fast);
  width: 100%;
}

.brood-row.big {
  padding: var(--space-3) var(--space-4);
  font-size: var(--text-body);
}

.brood-row:hover { background: rgba(184, 148, 92, 0.05); }

.brood-row:focus-visible {
  outline: 2px solid var(--cathedral-gold);
  outline-offset: 2px;
}

.brood-name {
  color: var(--blood-crimson);
  flex: 1;
}

.brood-row:hover .brood-name { color: var(--blood-crimson-bright); }

.brood-caret {
  color: var(--ash-gray);
  font-size: var(--text-body-sm);
}

/* ── Feedback ── */
.pd-error { color: var(--blood-crimson); font-size: var(--text-micro); margin: var(--space-2) 0 0 0; }
.pd-success { color: var(--cathedral-gold); font-size: var(--text-micro); margin: var(--space-2) 0 0 0; }

/* ── Responsive ── */
@media (max-width: 959.98px) {
  .two-col {
    grid-template-columns: 1fr;
    gap: var(--space-6);
  }

  .sr-grid {
    grid-template-columns: 1fr;
    gap: var(--space-5);
  }
}
</style>
