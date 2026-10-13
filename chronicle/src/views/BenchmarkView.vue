<template>
  <div class="content-inner benchmark">
    <PageHead :sub="t('benchmark.subtitle', { benchmark: summary?.benchmark ?? 'EUNL.DE' })">
      <template #eyebrow>
        <span class="eb-glyph"><BatGlyph :size="13" /></span>
        {{ t('benchmark.eyebrow') }}
      </template>
      <template #title>{{ t('benchmark.pageTitle') }}</template>
    </PageHead>

    <div v-if="loading" class="empty small" data-testid="benchmark-loading">
      <div class="em-text">{{ t('benchmark.loading') }}</div>
    </div>
    <div v-else-if="failed" class="empty small" data-testid="benchmark-error">
      <div class="em-text">{{ t('benchmark.loadError') }}</div>
    </div>
    <div v-else-if="!summary" class="empty small" data-testid="benchmark-empty">
      <div class="em-text">{{ t('benchmark.empty') }}</div>
    </div>

    <template v-else>
      <div class="card bm-decision" data-testid="benchmark-decision">
        <div class="bm-countdown mono" data-testid="benchmark-countdown">{{ countdownText }}</div>
        <ul class="bm-rules">
          <li><TagPill tone="green">{{ t('benchmark.status.GREEN') }}</TagPill> {{ t('benchmark.rules.green') }}</li>
          <li><TagPill tone="crimson">{{ t('benchmark.status.RED') }}</TagPill> {{ t('benchmark.rules.red') }}</li>
          <li><TagPill tone="ash">{{ t('benchmark.status.GREY') }}</TagPill> {{ t('benchmark.rules.grey') }}</li>
          <li><TagPill tone="gold">{{ t('benchmark.status.INSUFFICIENT') }}</TagPill> {{ t('benchmark.rules.insufficient') }}</li>
        </ul>
        <p class="bm-note">{{ t('benchmark.rules.basis', { minEvents: summary.minEvents, minWeeks: summary.minWeeks }) }}</p>
        <p class="bm-note">{{ t('benchmark.rules.owner') }}</p>
      </div>

      <SectionHeader :label="t('benchmark.sections.strigoi')" />
      <div class="table-scroll">
        <table class="dt" data-testid="benchmark-table">
          <thead>
            <tr>
              <th>{{ t('benchmark.cols.strigoi') }}</th>
              <th>{{ t('benchmark.cols.status') }}</th>
              <th class="num">{{ t('benchmark.cols.signal20') }}</th>
              <th class="num">{{ t('benchmark.cols.signal60') }}</th>
              <th class="num">{{ t('benchmark.cols.paired20') }}</th>
              <th class="num">{{ t('benchmark.cols.paired60') }}</th>
              <th>{{ t('benchmark.cols.coverage') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in summary.agents" :key="a.agent" data-testid="benchmark-row" :data-agent="a.agent">
              <td class="tkr">
                {{ a.agent }}
                <div
                  v-if="a.excluded?.total"
                  class="bm-sub bm-excluded"
                  :title="excludedTitle(a.excluded)"
                  :data-testid="`benchmark-excluded-${a.agent}`"
                >{{ t('benchmark.cell.excluded', { n: a.excluded.total }) }}</div>
              </td>
              <td>
                <TagPill :tone="statusTone(a.status)" :data-testid="`benchmark-status-${a.agent}`">
                  {{ t(`benchmark.status.${a.status}`) }}
                </TagPill>
              </td>
              <td class="num" data-col="signal20">
                <template v-if="a.signal20.n > 0">
                  <div :class="pctCls(a.signal20.clusteredMean)">{{ pct(a.signal20.clusteredMean) }}</div>
                  <div class="bm-sub">{{ signalSub(a.signal20) }}</div>
                </template>
                <template v-else>—</template>
              </td>
              <td class="num" data-col="signal60">
                <template v-if="a.signal60.n > 0">
                  <div :class="pctCls(a.signal60.clusteredMean)">{{ pct(a.signal60.clusteredMean) }}</div>
                  <div class="bm-sub">{{ signalSub(a.signal60) }}</div>
                </template>
                <template v-else>—</template>
              </td>
              <td class="num" data-col="paired20">
                <template v-if="a.paired20">
                  <div :class="pctCls(a.paired20.difference)">{{ pct(a.paired20.difference) }}</div>
                  <div class="bm-sub">{{ pairedSub(a.paired20) }}</div>
                </template>
                <template v-else>—</template>
              </td>
              <td class="num" data-col="paired60">
                <template v-if="a.paired60">
                  <div :class="pctCls(a.paired60.difference)">{{ pct(a.paired60.difference) }}</div>
                  <div class="bm-sub">{{ pairedSub(a.paired60) }}</div>
                </template>
                <template v-else>—</template>
              </td>
              <td class="mono" data-col="coverage">{{ a.coverageStart ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <SectionHeader :label="t('benchmark.sections.depot')" />
      <div v-if="!summary.depot" class="empty small" data-testid="benchmark-depot-empty">
        <div class="em-text">{{ t('benchmark.depot.empty') }}</div>
      </div>
      <div v-else class="card bm-depot" data-testid="benchmark-depot">
        <div class="bm-depot-head mono">
          {{ summary.depot.connection }}<template v-if="summary.depot.from"> · {{ summary.depot.from }} → {{ summary.depot.to }}</template>
        </div>
        <div class="bm-depot-figures">
          <div>
            <div class="bm-k">{{ t('benchmark.depot.twr') }}</div>
            <div class="mono" :class="pctCls(summary.depot.twr)" data-testid="benchmark-depot-twr">{{ pct(summary.depot.twr) }}</div>
          </div>
          <div>
            <div class="bm-k">{{ t('benchmark.depot.benchmark') }}</div>
            <div class="mono">{{ pct(summary.depot.benchmarkReturn) }}</div>
          </div>
          <div>
            <div class="bm-k">{{ t('benchmark.depot.excess') }}</div>
            <div class="mono" :class="pctCls(summary.depot.excess)">{{ pct(summary.depot.excess) }}</div>
          </div>
        </div>
        <div v-if="summary.depot.byStrigoi.length" class="table-scroll">
          <table class="dt" data-testid="benchmark-depot-table">
            <thead>
              <tr>
                <th>{{ t('benchmark.depot.cols.strigoi') }}</th>
                <th class="num">{{ t('benchmark.depot.cols.positions') }}</th>
                <th class="num">{{ t('benchmark.depot.cols.capital') }}</th>
                <th class="num">{{ t('benchmark.depot.cols.ret') }}</th>
                <th class="num">{{ t('benchmark.depot.cols.benchmark') }}</th>
                <th class="num">{{ t('benchmark.depot.cols.excess') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in summary.depot.byStrigoi" :key="r.strigoi" data-testid="benchmark-depot-row">
                <td class="tkr">{{ r.strigoi }}</td>
                <td class="num">
                  {{ r.positions }}<span v-if="r.openPositions" class="bm-sub"> ({{ t('benchmark.depot.open', { n: r.openPositions }) }})</span>
                </td>
                <td class="num">{{ formatNumber(r.capitalEur, 0) }}</td>
                <td class="num" :class="pctCls(r.returnEur)">{{ pct(r.returnEur) }}</td>
                <td class="num">{{ pct(r.benchmarkReturn) }}</td>
                <td class="num" :class="pctCls(r.excess)">{{ pct(r.excess) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <SectionHeader :label="t('benchmark.sections.notes')" />
      <ul class="bm-notes" data-testid="benchmark-notes">
        <li>{{ t('benchmark.notes.costs') }}</li>
        <li>{{ t('benchmark.notes.coverage') }}</li>
        <li>{{ t('benchmark.notes.echoPaused') }}</li>
        <li>{{ t('benchmark.notes.ci') }}</li>
        <li>{{ t('benchmark.notes.timing') }}</li>
        <li>{{ t('benchmark.notes.excluded') }}</li>
      </ul>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import PageHead from '../components/common/PageHead.vue'
import BatGlyph from '../components/common/BatGlyph.vue'
import SectionHeader from '../components/common/SectionHeader.vue'
import TagPill from '../components/common/TagPill.vue'
import { useApi } from '../api'
import type {
  BenchmarkExcludedCounts,
  BenchmarkExclusions,
  BenchmarkPairedStats,
  BenchmarkSignalStats,
  BenchmarkStatus,
  BenchmarkSummary,
} from '../api/types'
import { formatNumber, formatPercent, pctClass } from '../utils/format'

const { t } = useI18n()
const api = useApi()

const summary = ref<BenchmarkSummary | null>(null)
const loading = ref(true)
const failed = ref(false)

onMounted(async () => {
  try {
    summary.value = await api.getBenchmarkSummary()
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
})

const DAY_MS = 86_400_000

function deDate(iso: string): string {
  const [y, m, d] = iso.split('-')
  return `${d}.${m}.${y}`
}

const countdownText = computed(() => {
  if (!summary.value) return ''
  const target = Date.parse(`${summary.value.decisionDate}T00:00:00Z`)
  const now = new Date()
  const today = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate())
  const days = Math.round((target - today) / DAY_MS)
  const date = deDate(summary.value.decisionDate)
  if (days === 1) return t('benchmark.countdown.futureOne', { date })
  if (days > 0) return t('benchmark.countdown.future', { days, date })
  if (days === 0) return t('benchmark.countdown.today', { date })
  return t('benchmark.countdown.past', { date })
})

/** Fraction → "+1,2 %"; null → "—". */
function pct(v: number | null): string {
  return v == null ? '—' : formatPercent(v * 100)
}

function pctCls(v: number | null): string {
  return v == null ? '' : pctClass(v * 100)
}

function statusTone(s: BenchmarkStatus): 'green' | 'crimson' | 'ash' | 'gold' {
  if (s === 'GREEN') return 'green'
  if (s === 'RED') return 'crimson'
  if (s === 'GREY') return 'ash'
  return 'gold'
}

function ciText(lo: number | null, hi: number | null): string {
  return lo == null || hi == null
    ? t('benchmark.cell.noCi')
    : t('benchmark.cell.ci', { low: pct(lo), high: pct(hi) })
}

function signalSub(s: BenchmarkSignalStats): string {
  return [
    ciText(s.ciLow, s.ciHigh),
    t('benchmark.cell.n', { n: s.n }),
    t('benchmark.cell.weeks', { n: s.weeks }),
    s.hitRate == null ? '' : t('benchmark.cell.hit', { n: formatNumber(s.hitRate * 100, 0) }),
  ].filter(Boolean).join(' · ')
}

function excludedGroup(c: BenchmarkExcludedCounts): string {
  return t('benchmark.cell.excludedGroup', {
    total: c.total, noEntry: c.noEntry, noData: c.noData, unknownCurrency: c.unknownCurrency,
  })
}

/** Tooltip: the excluded events split by group and reason (final review I3). */
function excludedTitle(x: BenchmarkExclusions): string {
  return t('benchmark.cell.excludedTitle', {
    signal: excludedGroup(x.signal), picked: excludedGroup(x.picked), rejected: excludedGroup(x.rejected),
  })
}

function pairedSub(p: BenchmarkPairedStats): string {
  return [
    ciText(p.ciLow, p.ciHigh),
    t('benchmark.cell.picked', { n: p.pickedN }),
    t('benchmark.cell.rejected', { n: p.rejectedN }),
  ].join(' · ')
}
</script>

<style scoped>
.bm-decision { margin-bottom: var(--space-6); }
.bm-countdown { font-size: var(--text-h4); color: var(--bone-ivory); margin-bottom: var(--space-4); }
.bm-rules { list-style: none; padding: 0; margin: 0 0 var(--space-4); display: grid; gap: var(--space-2); }
.bm-rules li { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; color: var(--bone-ivory-dim); }
.bm-note { margin: var(--space-2) 0 0; font-size: var(--text-body-sm); color: var(--ash-gray); }
.bm-excluded { cursor: help; text-decoration: underline dotted; }
.bm-sub { font-size: var(--text-micro); color: var(--ash-gray); font-family: var(--font-mono); }
.bm-depot { margin-bottom: var(--space-6); }
.bm-depot-head { color: var(--ash-gray); margin-bottom: var(--space-4); }
.bm-depot-figures { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-4); margin-bottom: var(--space-4); }
.bm-k { font-size: var(--text-micro); text-transform: uppercase; letter-spacing: 0.1em; color: var(--ash-gray); }
.bm-notes { padding-left: var(--space-5); color: var(--ash-gray); font-size: var(--text-body-sm); }
@media (max-width: 959.98px) {
  .bm-depot-figures { grid-template-columns: 1fr; }
}
</style>
