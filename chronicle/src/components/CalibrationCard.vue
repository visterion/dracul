<template>
  <div class="card cal-card" data-testid="calibration-card">
    <SectionHeader :label="t('calibration.title')" />

    <template v-if="loading">
      <v-skeleton-loader v-for="i in 3" :key="i" type="list-item-two-line" />
    </template>

    <div v-else-if="error" class="empty small"><div class="em-text">{{ error }}</div></div>

    <template v-else-if="calibration && behavior">
      <!-- Brier calibration -->
      <section class="cal-block">
        <h3 class="cal-block-title">{{ t('calibration.brierTitle') }}</h3>
        <div class="cal-executor-row">
          <span class="cal-executor-label">{{ t('calibration.executor') }}</span>
          <TagPill v-if="calibration.executor.insufficient" tone="ash">
            {{ t('calibration.insufficient', { n: calibration.executor.n }) }}
          </TagPill>
          <span v-else class="mono cal-executor-value">{{ fmtBrier(calibration.executor.brier) }}</span>
          <span class="cal-executor-n">n={{ calibration.executor.n }}</span>
        </div>

        <div
          v-if="!calibration.executor.insufficient && calibration.executor.buckets.length > 0"
          class="table-scroll cal-buckets"
          data-testid="calibration-buckets"
        >
          <table class="dt">
            <thead>
              <tr>
                <th>{{ t('calibration.cols.range') }}</th>
                <th class="num">{{ t('calibration.cols.n') }}</th>
                <th class="num">{{ t('calibration.cols.predicted') }}</th>
                <th class="num">{{ t('calibration.cols.observed') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in calibration.executor.buckets" :key="b.range">
                <td class="tkr">{{ b.range }}</td>
                <td class="num">{{ b.n }}</td>
                <td class="num">{{ fmtR(b.predicted) }}</td>
                <td class="num">{{ fmtR(b.observed) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="table-scroll">
          <table class="dt">
            <thead>
              <tr>
                <th>{{ t('calibration.cols.hunter') }}</th>
                <th class="num">{{ t('calibration.cols.n') }}</th>
                <th class="num">{{ t('calibration.cols.brier') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="h in calibration.hunters" :key="h.agent"
                data-testid="calibration-hunter-row" :data-agent="h.agent"
              >
                <td class="tkr">{{ h.agent }}</td>
                <td class="num">{{ h.n }}</td>
                <td class="num">
                  <TagPill v-if="h.insufficient" tone="ash">
                    {{ t('calibration.insufficient', { n: h.n }) }}
                  </TagPill>
                  <span v-else class="mono">{{ fmtBrier(h.brier) }}</span>
                </td>
              </tr>
              <tr v-if="calibration.hunters.length === 0" data-testid="calibration-hunters-empty">
                <td class="cal-empty" colspan="3">{{ t('calibration.emptyHunters') }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Veto precision -->
      <section class="cal-block">
        <h3 class="cal-block-title">{{ t('calibration.vetoPrecision') }}</h3>
        <div class="table-scroll">
          <table class="dt">
            <thead>
              <tr>
                <th>{{ t('calibration.cols.reason') }}</th>
                <th class="num">{{ t('calibration.cols.n') }}</th>
                <th class="num">{{ t('calibration.cols.skipped') }}</th>
                <th class="num">{{ t('calibration.cols.r20d') }}</th>
                <th class="num">{{ t('calibration.cols.r60d') }}</th>
                <th class="num">{{ t('calibration.cols.stoppedOut') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in behavior.veto_precision" :key="row.reason_code"
                data-testid="veto-precision-row" :data-reason="row.reason_code"
              >
                <td class="tkr">{{ row.reason_code }}</td>
                <td class="num">{{ row.n }}</td>
                <td class="num">{{ row.skipped }}</td>
                <td class="num">{{ fmtR(row.mean_hypothetical_r_20d) }}</td>
                <td class="num">{{ fmtR(row.mean_hypothetical_r_60d) }}</td>
                <td class="num">{{ fmtPct(row.stopped_out_pct) }}</td>
              </tr>
              <tr v-if="behavior.veto_precision.length === 0" data-testid="calibration-veto-empty">
                <td class="cal-empty" colspan="6">{{ t('calibration.emptyVeto') }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <ul class="cal-caveats" data-testid="calibration-caveats">
          <li v-for="(c, i) in behavior.caveats" :key="i">{{ c }}</li>
        </ul>
      </section>

      <!-- Behavior counters -->
      <section class="cal-block">
        <h3 class="cal-block-title">{{ t('calibration.behaviorTitle') }}</h3>
        <div class="stat-grid cal-stat-grid">
          <StatTile :label="t('calibration.tiles.latencyMax')" :value="fmtSeconds(behavior.hard_exit_latency.max_seconds)" />
          <StatTile :label="t('calibration.tiles.latencyP95')" :value="fmtSeconds(behavior.hard_exit_latency.p95_seconds)" />
          <StatTile :label="t('calibration.tiles.whipsawReentry')" :value="behavior.whipsaw.reentry_within_10d" />
          <StatTile :label="t('calibration.tiles.whipsawRoundtrip')" :value="behavior.whipsaw.roundtrip_under_5d" />
          <StatTile :label="t('calibration.tiles.slippageMean')" :value="fmtR(behavior.slippage.mean)" />
          <StatTile :label="t('calibration.tiles.slippageWorst')" :value="fmtR(behavior.slippage.worst)" />
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api'
import type { ExecutorCalibration, ExecutorBehavior } from '../api/types'
import SectionHeader from './common/SectionHeader.vue'
import StatTile from './common/StatTile.vue'
import TagPill from './common/TagPill.vue'

const { t } = useI18n()
const api = useApi()

const calibration = ref<ExecutorCalibration | null>(null)
const behavior = ref<ExecutorBehavior | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    const [cal, beh] = await Promise.all([api.getExecutorCalibration(), api.getExecutorBehavior()])
    calibration.value = cal
    behavior.value = beh
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('calibration.loadError')
  } finally {
    loading.value = false
  }
})

// Wire values are primitives (never null) — see CalibrationService records.
const fmtBrier = (v: number) => v.toFixed(3)
const fmtR = (v: number) => v.toFixed(2)
const fmtPct = (v: number) => `${v.toFixed(1)}%`
const fmtSeconds = (v: number) => `${v}s`
</script>

<style scoped>
.cal-card { display: flex; flex-direction: column; gap: var(--space-6); }
.cal-block-title {
  font-family: var(--font-display);
  font-size: var(--text-body);
  font-weight: 500;
  color: var(--bone-ivory);
  margin: 0 0 var(--space-3);
}
.cal-block + .cal-block { padding-top: var(--space-6); border-top: 1px solid var(--rule); }
.cal-executor-row {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}
.cal-executor-label {
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--ash-gray);
}
.cal-executor-value { font-size: var(--text-h4); color: var(--bone-ivory); }
.cal-executor-n { font-size: var(--text-body-sm); color: var(--ash-gray); }
.cal-buckets { margin-bottom: var(--space-5); }
.cal-empty { color: var(--ash-gray); font-style: italic; }
.cal-caveats {
  margin: var(--space-4) 0 0;
  padding-left: var(--space-5);
  color: var(--ash-gray);
  font-size: var(--text-body-sm);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.cal-stat-grid { grid-template-columns: repeat(3, 1fr); }

@media (max-width: 959.98px) {
  .cal-stat-grid { grid-template-columns: 1fr 1fr; }
}
</style>
