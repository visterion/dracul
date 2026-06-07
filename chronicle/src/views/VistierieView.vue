<template>
  <div :class="embedded ? '' : 'content-inner'">
    <PageHead :title="t('vistierie.headTitle')" :sub="t('vistierie.headSub')">
      <template #eyebrow>
        <span class="eb-glyph"><BatGlyph :size="13" /></span>
        {{ t('vistierie.eyebrow') }}{{ embedded ? ' · ' + t('vistierie.adminOnly') : '' }}
      </template>
    </PageHead>

    <template v-if="loading">
      <div class="stat-grid">
        <v-skeleton-loader v-for="i in 2" :key="i" type="card" />
      </div>
    </template>

    <div v-else-if="error" class="vist-error">{{ error }}</div>

    <template v-else-if="data">
      <div class="stat-grid vist-stat-grid">
        <StatTile
          :label="t('vistierie.tiles.thisMonth')"
          icon="ph-moon"
          :value="`$${data.monthlyTotalUsd.toFixed(2)}`"
          :foot="t('vistierie.tiles.ofCap', { cap: data.monthlyBudgetUsd.toFixed(2) })"
        />
        <StatTile
          :label="t('vistierie.tiles.perDay')"
          :value="`$${avgPerDay}`"
          :foot="t('vistierie.tiles.thirtyDayAvg')"
        />
      </div>

      <div class="vist-grid">
        <!-- tier ledger + month total -->
        <div class="stack-6">
          <div>
            <SectionHeader :label="t('vistierie.sections.tierBudgets')" />
            <div class="card">
              <div class="ledger">
                <div
                  v-for="tier in data.tiers"
                  :key="tier.name"
                  class="ledger-row"
                  data-testid="tier-budget-bar"
                >
                  <div class="lr-top">
                    <span class="lr-tier">
                      {{ tier.name }} <span class="lr-models">{{ tier.models }}</span>
                    </span>
                    <span class="lr-num mono">
                      <template v-if="tier.budgetUsd">
                        <span :class="tier.usedUsd > 0 ? 'pos' : ''">${{ tier.usedUsd.toFixed(2) }}</span>
                        / ${{ tier.budgetUsd.toFixed(2) }}
                      </template>
                      <template v-else>${{ tier.usedUsd.toFixed(2) }} / ∞</template>
                    </span>
                  </div>
                  <div class="ledger-track">
                    <span
                      class="ledger-fill"
                      :class="fillClass(tier)"
                      :style="{ width: fillWidth(tier) }"
                    />
                  </div>
                </div>
              </div>

              <div class="month-total">
                <div class="lr-top">
                  <span class="lr-tier">{{ t('vistierie.sections.monthlyTotal') }}</span>
                  <span class="lr-num mono">
                    <span class="pos">${{ data.monthlyTotalUsd.toFixed(2) }}</span>
                    / ${{ data.monthlyBudgetUsd.toFixed(2) }}
                  </span>
                </div>
                <div class="ledger-track">
                  <span
                    class="ledger-fill low"
                    :style="{ width: monthFillWidth }"
                  />
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- spend by agent -->
        <div>
          <SectionHeader :label="t('vistierie.sections.spendingByAgent')" />
          <div class="card">
            <div class="agent-spend">
              <div
                v-for="agent in data.spendingByAgent"
                :key="agent.agent"
                class="as-row"
              >
                <span class="as-name mono">{{ agent.agent }}</span>
                <SpendBar :value="agent.totalUsd" :max="maxAgent" />
                <span class="as-val mono">${{ agent.totalUsd.toFixed(2) }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- daily spend chart -->
        <div>
          <SectionHeader :label="t('vistierie.sections.dailySpend')" />
          <div class="chart-card">
            <LineChart
              :height="200"
              :labels="chartLabels"
              :series="chartSeries"
              area-fill
              :aria-label="t('vistierie.sections.dailySpend')"
            />
            <div class="vist-foot">
              <div>
                <div class="vf-k">{{ t('vistierie.stats.avgPerDay') }}</div>
                <div class="vf-v mono">${{ avgPerDay }}</div>
              </div>
              <div>
                <div class="vf-k">{{ t('vistierie.stats.monthTotal') }}</div>
                <div class="vf-v mono">${{ data.monthlyTotalUsd.toFixed(2) }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import type { VistierieData, TierBudget } from '../api/types'
import { useApi } from '../api'
import PageHead from '../components/common/PageHead.vue'
import BatGlyph from '../components/common/BatGlyph.vue'
import SectionHeader from '../components/common/SectionHeader.vue'
import StatTile from '../components/common/StatTile.vue'
import SpendBar from '../components/common/SpendBar.vue'
import LineChart from '../components/common/LineChart.vue'

defineProps<{ embedded?: boolean }>()

const { t } = useI18n()
const api = useApi()
const data = ref<VistierieData | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    data.value = await api.getVistierieData()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to load'
  } finally {
    loading.value = false
  }
})

const maxAgent = computed(() =>
  Math.max(...(data.value?.spendingByAgent ?? []).map(a => a.totalUsd), 0.0001)
)

const avgPerDay = computed(() => {
  const d = data.value?.dailySpend30d
  if (!d || d.length === 0) return '0.00'
  return (d.reduce((s, e) => s + e.totalUsd, 0) / d.length).toFixed(2)
})

function fillClass(tier: TierBudget): string {
  if (!tier.budgetUsd) return 'low'
  const pct = tier.usedUsd / tier.budgetUsd
  return pct > 0.75 ? 'high' : pct >= 0.5 ? 'mid' : 'low'
}

function fillWidth(tier: TierBudget): string {
  const pct = tier.budgetUsd ? (tier.usedUsd / tier.budgetUsd) * 100 : 0
  return `${Math.max(pct, tier.usedUsd > 0 ? 2 : 0)}%`
}

const monthFillWidth = computed(() => {
  const d = data.value
  if (!d || !d.monthlyBudgetUsd) return '0%'
  return `${Math.max(0, Math.min((d.monthlyTotalUsd / d.monthlyBudgetUsd) * 100, 100))}%`
})

const chartSeries = computed(() => [{
  data: (data.value?.dailySpend30d ?? []).map(e => e.totalUsd),
  color: 'var(--cathedral-gold)',
  fill: 'rgba(184,148,92,0.08)',
}])

// Sample ~5 evenly spaced date labels from the 30-day series.
const chartLabels = computed(() => {
  const d = data.value?.dailySpend30d ?? []
  if (d.length === 0) return []
  const idxs = [0, Math.floor(d.length * 0.27), Math.floor(d.length * 0.5), Math.floor(d.length * 0.73), d.length - 1]
  return [...new Set(idxs)].map(i => ({ i, t: formatLabel(d[i].date) }))
})

function formatLabel(iso: string): string {
  const dt = new Date(iso)
  return dt.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}
</script>

<style scoped>
/* .ledger* / .vist-* / .agent-spend / .as-* / .month-total are NOT global — scoped (styles.css:320-340) */
.vist-error { color: var(--blood-crimson); padding: var(--space-8) 0; }
.vist-stat-grid { grid-template-columns: repeat(2, 1fr); margin-bottom: var(--space-8); }

.vist-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-6);
  align-items: start;
}
.vist-grid > :last-child { grid-column: 1 / -1; }

.ledger { display: flex; flex-direction: column; gap: var(--space-5); }
.ledger-row { display: flex; flex-direction: column; gap: var(--space-2); }
.lr-top { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-3); }
.lr-tier { font-size: var(--text-body-sm); color: var(--bone-ivory); }
.lr-models { color: var(--ash-gray); font-size: var(--text-micro); }
.lr-num { font-size: var(--text-body-sm); color: var(--bone-ivory-dim); }
.ledger-track { height: 8px; background: rgba(245, 241, 232, 0.06); border-radius: 4px; overflow: hidden; }
.ledger-fill { display: block; height: 100%; border-radius: 4px; transition: width var(--transition-slow); }
.ledger-fill.high { background: var(--blood-crimson); }
.ledger-fill.mid { background: var(--cathedral-gold); }
.ledger-fill.low { background: var(--cathedral-gold); }
.month-total {
  margin-top: var(--space-5);
  padding-top: var(--space-5);
  border-top: 1px solid var(--rule);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.agent-spend { display: flex; flex-direction: column; gap: var(--space-3); }
.as-row { display: grid; grid-template-columns: 130px 1fr 54px; align-items: center; gap: var(--space-3); }
.as-name { font-size: var(--text-body-sm); color: var(--bone-ivory-dim); }
.as-val { font-size: var(--text-body-sm); color: var(--bone-ivory); text-align: right; }

.vist-foot {
  display: flex;
  gap: var(--space-8);
  margin-top: var(--space-5);
  padding-top: var(--space-4);
  border-top: 1px solid var(--rule);
}
.vf-k {
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--ash-gray);
  margin-bottom: var(--space-1);
}
.vf-v { font-size: var(--text-h4); color: var(--bone-ivory); }

@media (max-width: 959.98px) {
  .vist-grid { grid-template-columns: 1fr; }
  .vist-stat-grid { grid-template-columns: 1fr 1fr; }
}
</style>
