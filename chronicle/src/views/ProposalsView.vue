<template>
  <div class="content-inner proposals" data-testid="proposals">
    <PageHead :sub="t('proposals.subtitle')">
      <template #eyebrow>
        <span class="eb-glyph"><BatGlyph :size="13" /></span>
        {{ t('proposals.eyebrow') }}
      </template>
      <template #title>{{ t('proposals.title') }}</template>
    </PageHead>

    <p class="proposals-note">{{ t('proposals.readonlyNote') }}</p>

    <template v-if="loading">
      <v-skeleton-loader v-for="i in 3" :key="i" type="list-item-two-line" />
    </template>

    <div v-else-if="runs.length === 0" class="empty small" data-testid="proposals-empty">
      <div class="em-text">{{ t('proposals.empty') }}</div>
    </div>

    <div v-else class="proposal-runs">
      <section
        v-for="run in runs"
        :key="run.runId"
        class="proposal-run"
        :data-testid="`proposal-run-${run.runId}`"
      >
        <div class="run-head">
          <span class="run-date mono">{{ formatDate(run.createdAt) }}</span>
          <p v-if="run.marketNote" class="run-note">{{ run.marketNote }}</p>
        </div>

        <template v-if="actionItems(run).length > 0">
          <SectionHeader :label="t('proposals.actionItems')" />
          <ul class="proposal-list" data-testid="proposal-action-items">
            <li v-for="p in actionItems(run)" :key="p.id">
              <ProposalRow :proposal="p" />
            </li>
          </ul>
        </template>

        <template v-if="warnHolds(run).length > 0">
          <SectionHeader :label="t('proposals.warnHolds')" />
          <ul class="proposal-list proposal-list--holds" data-testid="proposal-warn-holds">
            <li v-for="p in warnHolds(run)" :key="p.id">
              <ProposalRow :proposal="p" />
            </li>
          </ul>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import PageHead from '../components/common/PageHead.vue'
import BatGlyph from '../components/common/BatGlyph.vue'
import SectionHeader from '../components/common/SectionHeader.vue'
import ProposalRow from '../components/renfield/ProposalRow.vue'
import { useApi } from '../api'
import type { Proposal, ProposalRun } from '../api/types'

// Days window sent to the backend; the endpoint itself defaults to and caps
// this the same way (GET /api/renfield/proposals?days=7, clamped 1..90) —
// kept in sync deliberately rather than letting the UI silently diverge.
const DAYS = 7

const { t, locale } = useI18n()
const api = useApi()

const runs = ref<ProposalRun[]>([])
const loading = ref(true)
let source: EventSource | null = null

async function load() {
  runs.value = await api.getProposals(DAYS)
}

onMounted(async () => {
  try {
    await load()
  } finally {
    loading.value = false
  }
  connect()
})

onUnmounted(() => {
  source?.close()
  source = null
})

// Pattern from stores/liveAlerts.ts: subscribe to the shared /api/events SSE
// stream. The `proposal.new` payload only carries {count, run_id, ts} (see
// RenfieldWebhookController#complete) — not the proposals themselves — so a
// refetch of the window is the simplest way to pick up the new run's content.
function connect() {
  if (import.meta.env.VITE_MOCK === 'true') return
  const base = import.meta.env.VITE_API_BASE ?? ''
  source = new EventSource(`${base}/api/events`)
  source.addEventListener('proposal.new', () => {
    void load()
  })
}

function actionItems(run: ProposalRun): Proposal[] {
  return run.proposals.filter(p => p.action !== 'hold')
}

function warnHolds(run: ProposalRun): Proposal[] {
  return run.proposals.filter(p => p.action === 'hold')
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString(locale.value, {
    day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit',
  })
}
</script>

<style scoped>
.proposals-note {
  font-size: var(--text-body-sm);
  font-style: italic;
  color: var(--ash-gray-light);
  margin: 0 0 var(--space-6);
}

.proposal-runs { display: flex; flex-direction: column; gap: var(--space-8); }

.run-head { margin-bottom: var(--space-3); }
.run-date { font-size: var(--text-body-sm); color: var(--cathedral-gold); }
.run-note { font-size: var(--text-body-sm); color: var(--bone-ivory-dim); margin: var(--space-1) 0 0; }

.proposal-list {
  list-style: none;
  padding: 0;
  margin: 0 0 var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.proposal-list--holds { opacity: 0.85; }
</style>
