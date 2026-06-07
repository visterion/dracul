<template>
  <div class="chronicle">
    <!-- Loading skeleton -->
    <template v-if="store.loading">
      <v-skeleton-loader
        v-for="n in 3"
        :key="n"
        type="card"
        color="surface"
        class="chronicle__skeleton"
      />
    </template>

    <!-- Error state -->
    <div v-else-if="store.error" class="chronicle__error">
      <p>{{ store.error }}</p>
      <button class="chronicle__retry" @click="store.load()">{{ t('chronicle.error.retry') }}</button>
    </div>

    <!-- Content -->
    <template v-else>
      <!-- Dusk strip — the night's tally -->
      <DuskStrip
        :prey="store.prey.length"
        :verdicts="store.verdicts.length"
        :alerts="store.alerts.length"
        :lessons="store.pendingPatterns.length"
      />

      <div class="chronicle-grid">
        <!-- Feed -->
        <div class="feed">
          <!-- Verdicts -->
          <template v-if="store.verdicts.length > 0">
            <div class="section-head">
              <span class="sh-rule" />{{ t('chronicle.sections.verdicts') }}<span class="sh-sub">{{ t('chronicle.sections.verdictsSub') }}</span>
            </div>
            <VerdictCard
              v-for="verdict in store.verdicts"
              :key="verdict.id"
              :verdict="verdict"
              @open="openVerdict"
            />
          </template>

          <!-- Individual prey -->
          <div class="section-head">
            <span class="sh-rule" />{{ t('chronicle.sections.individualPrey') }}<span class="sh-sub">{{ t('chronicle.sections.individualPreySub') }}</span>
          </div>

          <template v-for="group in preyGroups" :key="group.key">
            <div class="feed-daymark">{{ group.label }}</div>
            <PreyCard
              v-for="prey in group.items"
              :key="prey.id"
              :prey="prey"
              @open="openPrey"
            />
          </template>

          <div v-if="filteredPrey.length === 0" class="empty">
            <div class="em-icon"><BatGlyph :size="30" /></div>
            <div class="em-text">{{ t('chronicle.emptyState.noPreyMatchFilter') }}</div>
          </div>
        </div>

        <!-- Filters / Brood aside -->
        <aside class="filters">
          <div class="filter-group">
            <div class="fg-head">{{ t('chronicle.filters.title') }}</div>
            <button
              class="filter-chip"
              :class="{ active: filter === 'all' }"
              @click="filter = 'all'"
            >
              {{ t('chronicle.filters.allPrey') }} <span class="fc-count font-mono">{{ countFor('all') }}</span>
            </button>
            <button
              class="filter-chip"
              :class="{ active: filter === 'high' }"
              @click="filter = 'high'"
            >
              <span class="fc-dot" :style="{ background: 'var(--blood-crimson)' }" />
              {{ t('chronicle.filters.highConfidence') }} <span class="fc-count font-mono">{{ countFor('high') }}</span>
            </button>
          </div>

          <div v-if="anomalyTypes.length > 0" class="filter-group">
            <div class="fg-head">{{ t('chronicle.filters.anomalyClass') }}</div>
            <button
              v-for="a in anomalyTypes"
              :key="a"
              class="filter-chip"
              :class="{ active: filter === a }"
              @click="filter = a"
            >
              {{ a }} <span class="fc-count font-mono">{{ countFor(a) }}</span>
            </button>
          </div>

          <div class="filter-group">
            <div class="fg-head">{{ t('chronicle.filters.brood') }}</div>
            <BroodMini
              :strigoi="statusStore.status?.strigoi ?? []"
              :counts="broodCounts"
              @open="openStrigoi"
            />
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useChronicleStore } from '../stores/chronicle'
import { useStatusStore } from '../stores/status'
import type { Prey, Verdict, StrigoiStatus } from '../api/types'
import DuskStrip from '../components/common/DuskStrip.vue'
import VerdictCard from '../components/common/VerdictCard.vue'
import PreyCard from '../components/common/PreyCard.vue'
import BroodMini from '../components/common/BroodMini.vue'
import BatGlyph from '../components/common/BatGlyph.vue'

const { t } = useI18n()
const router = useRouter()
const store = useChronicleStore()
const statusStore = useStatusStore()

onMounted(() => {
  store.load()
  if (!statusStore.status) statusStore.load()
})

// ── filter state ────────────────────────────────────────────────
// 'all' | 'high' | <AnomalyType derived from data>
const filter = ref<string>('all')

// distinct anomaly types present in the prey list (derived, not hardcoded)
const anomalyTypes = computed(() => {
  const seen = new Set<string>()
  for (const p of store.prey) seen.add(p.anomalyType)
  return [...seen]
})

function matches(p: Prey, key: string): boolean {
  if (key === 'all') return true
  if (key === 'high') return p.confidence > 0.75
  return p.anomalyType === key
}

const filteredPrey = computed(() => store.prey.filter(p => matches(p, filter.value)))

// chip counts always reflect the FULL prey list
function countFor(key: string): number {
  return store.prey.filter(p => matches(p, key)).length
}

// ── day grouping (by distinct calendar day of discoveredAt, newest first) ──
function dayKey(iso: string): string {
  return new Date(iso).toISOString().slice(0, 10)
}

function dayLabel(iso: string): string {
  const key = dayKey(iso)
  const todayKey = new Date().toISOString().slice(0, 10)
  const yesterdayKey = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10)
  if (key === todayKey) return t('chronicle.daymark.today')
  if (key === yesterdayKey) return t('chronicle.daymark.yesterday')
  return new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'long' }).format(new Date(iso))
}

const preyGroups = computed(() => {
  const sorted = [...filteredPrey.value].sort(
    (a, b) => new Date(b.discoveredAt).getTime() - new Date(a.discoveredAt).getTime(),
  )
  const groups: { key: string; label: string; items: Prey[] }[] = []
  for (const p of sorted) {
    const key = dayKey(p.discoveredAt)
    let g = groups.find(x => x.key === key)
    if (!g) {
      g = { key, label: dayLabel(p.discoveredAt), items: [] }
      groups.push(g)
    }
    g.items.push(p)
  }
  return groups
})

// ── brood counts: real current prey per strigoi (by discoveredBy) ──
const broodCounts = computed<Record<string, number>>(() => {
  const map: Record<string, number> = {}
  for (const p of store.prey) {
    map[p.discoveredBy] = (map[p.discoveredBy] ?? 0) + 1
  }
  return map
})

// ── navigation ──────────────────────────────────────────────────
function openPrey(prey: Prey) {
  router.push({ name: 'prey-detail', params: { id: prey.id } })
}
function openVerdict(verdict: Verdict) {
  router.push({ name: 'verdict-detail', params: { id: verdict.id } })
}
function openStrigoi(strigoi: StrigoiStatus) {
  router.push({ name: 'strigoi-detail', params: { name: strigoi.name } })
}
</script>

<style scoped>
.chronicle {
  max-width: 1280px;
  margin: 0 auto;
  padding: var(--space-6);
}

.chronicle__skeleton {
  margin-bottom: var(--space-4);
}

.chronicle__error {
  padding: var(--space-8) 0;
  color: var(--ash-gray);
  text-align: center;
}

.chronicle__retry {
  margin-top: var(--space-3);
  background: none;
  border: 1px solid var(--ash-gray);
  color: var(--bone-ivory);
  padding: var(--space-2) var(--space-4);
  border-radius: 4px;
  cursor: pointer;
  font-size: var(--text-body-sm);
  transition: border-color var(--transition-fast);
}

.chronicle__retry:hover {
  border-color: var(--cathedral-gold);
}

/* chronicle-grid + feed + filters mirror styles.css:156-225;
   .section-head / .sh-* / .feed-daymark / .empty are global (global.css) */
.chronicle-grid {
  display: grid;
  grid-template-columns: 1fr 264px;
  gap: var(--space-8);
  align-items: start;
}

.feed {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.feed .section-head {
  margin-top: var(--space-2);
}

.feed-daymark {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.16em;
  color: var(--ash-gray);
}

.feed-daymark::after {
  content: '';
  flex: 1;
  height: 1px;
  background: rgba(184, 148, 92, 0.1);
}

.filters {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.filter-group .fg-head {
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: var(--ash-gray);
  margin-bottom: var(--space-3);
}

.filter-chip {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  text-align: left;
  background: transparent;
  border: none;
  color: var(--bone-ivory-dim);
  font-family: var(--font-body);
  font-size: var(--text-body-sm);
  padding: var(--space-2);
  border-radius: 4px;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.filter-chip:hover {
  background: rgba(184, 148, 92, 0.05);
  color: var(--bone-ivory);
}

.filter-chip.active {
  color: var(--bone-ivory);
  background: rgba(161, 29, 44, 0.1);
}

.filter-chip .fc-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: 0 0 7px;
}

.filter-chip .fc-count {
  margin-left: auto;
  font-size: var(--text-micro);
  color: var(--ash-gray);
}

@media (max-width: 959.98px) {
  .chronicle { padding-inline: var(--space-4); }
  .chronicle-grid {
    grid-template-columns: 1fr;
  }
  .filters {
    position: static;
  }
}
</style>
