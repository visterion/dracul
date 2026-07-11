<template>
  <div v-if="loading" class="content-inner sd-loading">
    <v-skeleton-loader v-for="n in 4" :key="n" type="card" color="surface" class="sd-loading__item" />
  </div>

  <div v-else-if="fetchError" class="content-inner sd-notfound" role="alert">
    <p>{{ t('strigoi.loadError') }}</p>
    <router-link to="/" class="sd-notfound__link">{{ t('strigoi.notFound.backLink') }}</router-link>
  </div>

  <div v-else-if="!strigoi" class="content-inner sd-notfound">
    <p>{{ t('strigoi.notFound.message') }}</p>
    <router-link to="/" class="sd-notfound__link">{{ t('strigoi.notFound.backLink') }}</router-link>
  </div>

  <article v-else class="content-inner sd">
    <BackLink @click="onBack">{{ t('strigoi.back') }}</BackLink>

    <PageHead>
      <template #eyebrow>
        <span class="eb-glyph"><BatGlyph :size="13" /></span>
        {{ t('strigoi.eyebrow', { focus: strigoi.description || anomalyTypeLabel(strigoi.anomalyType) }) }}
      </template>
      <template #title>
        <span class="mono sd-title">{{ strigoi.name }}</span>
      </template>
      <template #sub>
        <span class="strigoi-state">
          <StateDot :state="strigoi.state" />
          {{ stateLabel }} · {{ scheduleSummary }}
        </span>
      </template>
    </PageHead>

    <div class="section-head" data-testid="sd-stats-head"><span class="sh-rule" />{{ t('strigoi.sections.statsMonth') }}</div>
    <div class="stat-grid sd-stats">
      <StatTile
        :label="t('strigoi.stats.preyPerHunt')"
        :value="formatNumber(strigoi.avgPreyPerHunt, 1)"
        :foot="t('strigoi.stats.preyPerHuntFoot', { n: strigoi.huntsThisMonth })"
      />
      <StatTile
        :label="t('strigoi.stats.hitRate')"
        :value="`${Math.round(strigoi.hitRate90d * 100)}%`"
        :foot="strigoi.hitRateDenominator === 0
          ? t('strigoi.stats.hitRateFootEmpty')
          : t('strigoi.stats.hitRateFoot', { num: strigoi.hitRateNumerator, den: strigoi.hitRateDenominator })"
      />
      <StatTile
        :label="t('strigoi.stats.hunts')"
        :value="strigoi.huntsThisMonth"
        :foot="t('strigoi.stats.huntsFoot', { n: strigoi.scheduledHuntsThisMonth })"
      />
      <StatTile
        :label="t('strigoi.stats.tier')"
        :value="agentTierLabel(strigoi.configuration.tier)"
        :foot="humanSchedule"
      />
    </div>

    <div class="verdict-grid">
      <div class="stack-6">
        <div>
          <div class="section-head"><span class="sh-rule" />{{ t('strigoi.sections.lastRun') }}</div>
          <div class="card">
            <div v-if="lastRun" class="sd-run-meta">
              <span class="mono">{{ formatRunDate(lastRun.ranAt) }}</span>
              <span class="sd-dot">·</span>
              <span class="mono">{{ lastRun.preyCount }} {{ t('strigoi.run.preyUnit') }}</span>
              <span class="sd-dot">·</span>
              <span class="mono">${{ formatNumber(lastRun.costUsd, 3) }}</span>
              <span class="sd-dot">·</span>
              <span class="mono sd-run-model">{{ lastRun.model }}</span>
            </div>
            <RunTrace v-if="lastRun" :trace="lastRun.trace" />
            <div v-else class="empty small">
              <div class="em-text">{{ t('strigoi.run.none') }}</div>
            </div>
          </div>
        </div>

        <div>
          <div class="section-head"><span class="sh-rule" />{{ t('strigoi.sections.recentPrey') }}</div>
          <div class="feed">
            <template v-if="strigoi.recentPrey.length">
              <PreyCard
                v-for="prey in strigoi.recentPrey"
                :key="prey.id"
                :prey="prey"
                @open="onOpenPrey"
              />
            </template>
            <div v-else class="empty">
              <div class="em-text">{{ t('strigoi.prey.empty') }}</div>
            </div>
          </div>
        </div>
      </div>

      <aside class="verdict-aside">
        <div class="card">
          <div class="section-head"><span class="sh-rule" />{{ t('strigoi.sections.configuration') }}</div>
          <div class="kv-list">
            <div class="kv-row">
              <span class="kv-k">{{ t('strigoi.config.schedule') }}</span>
              <span class="kv-v mono" :title="strigoi.configuration.cron">{{ humanSchedule }}</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('strigoi.config.nextRun') }}</span>
              <span class="kv-v mono">{{ formatAbsoluteDate(strigoi.configuration.nextRunAt) }}</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('strigoi.config.tier') }}</span>
              <span class="kv-v mono">{{ agentTierLabel(strigoi.configuration.tier) }}</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('strigoi.config.allowedModels') }}</span>
              <span class="kv-v mono">{{ strigoi.configuration.allowedModels.join(', ') || '—' }}</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('strigoi.config.dailyBudget') }}</span>
              <span class="kv-v mono">
                {{ formatMoney(strigoi.configuration.dailyUsedUsd, 'USD') }} / {{ formatMoney(strigoi.configuration.dailyBudgetUsd, 'USD') }}
              </span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('strigoi.config.monthlyBudget') }}</span>
              <span class="kv-v mono">
                {{ formatMoney(strigoi.configuration.monthlyUsedUsd, 'USD') }} / {{ formatMoney(strigoi.configuration.monthlyBudgetUsd, 'USD') }}
              </span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('strigoi.config.primary') }}</span>
              <span class="kv-v mono">{{ strigoi.configuration.primaryProvider }}</span>
            </div>
            <div v-if="strigoi.configuration.fallbackProvider" class="kv-row">
              <span class="kv-k">{{ t('strigoi.config.fallback') }}</span>
              <span class="kv-v mono">{{ strigoi.configuration.fallbackProvider }}</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">{{ t('strigoi.config.disabled') }}</span>
              <span class="kv-v mono">
                {{ strigoi.configuration.disabled ? t('strigoi.config.disabledYes') : t('strigoi.config.disabledNo') }}
              </span>
            </div>
          </div>
        </div>
      </aside>
    </div>
  </article>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { StrigoiDetail, Prey } from '../api/types'
import { useApi } from '../api'
import BackLink from '../components/common/BackLink.vue'
import PageHead from '../components/common/PageHead.vue'
import StatTile from '../components/common/StatTile.vue'
import StateDot from '../components/common/StateDot.vue'
import BatGlyph from '../components/common/BatGlyph.vue'
import RunTrace from '../components/common/RunTrace.vue'
import PreyCard from '../components/common/PreyCard.vue'
import { humanScheduleText } from '../utils/schedule'
import { useEnumLabels } from '../composables/useEnumLabels'
import { formatMoney, formatNumber } from '../utils/format'

const { t, locale } = useI18n()
const { anomalyTypeLabel, agentTierLabel } = useEnumLabels()
const route = useRoute()
const router = useRouter()
const api = useApi()

const strigoi = ref<StrigoiDetail | null>(null)
const loading = ref(true)
const fetchError = ref<string | null>(null)

const lastRun = computed(() => strigoi.value?.recentRuns[0] ?? null)

const stateLabel = computed(() => {
  if (!strigoi.value) return ''
  return t(`strigoi.state.${strigoi.value.state}`)
})

const humanSchedule = computed(() =>
  humanScheduleText(
    strigoi.value?.configuration.cron,
    strigoi.value?.configuration.nextRunAt,
    locale.value,
    t,
  ))

const scheduleSummary = computed(() => {
  if (!strigoi.value) return ''
  if (strigoi.value.state === 'paused' || strigoi.value.state === 'budget-hit') return ''
  return `${t('strigoi.schedule.next')} ${formatNextRun(strigoi.value.nextRunAt)}`
})

let requestId = 0

watch(() => route.params.name as string, async (name) => {
  const current = ++requestId
  loading.value = true
  fetchError.value = null
  strigoi.value = null
  try {
    const s = await api.getStrigoiDetail(name)
    if (current !== requestId) return
    strigoi.value = s
  } catch (e) {
    if (current === requestId) fetchError.value = (e as Error).message
  } finally {
    if (current === requestId) loading.value = false
  }
}, { immediate: true })

function onBack() {
  if (window.history.state?.back) router.back()
  else router.push({ name: 'chronicle' })
}

function onOpenPrey(prey: Prey) {
  router.push({ name: 'prey-detail', params: { id: prey.id } })
}

function formatRunDate(iso: string): string {
  const d = new Date(iso)
  const diffDays = Math.floor((Date.now() - d.getTime()) / 86_400_000)
  const time = d.toLocaleTimeString(locale.value, { hour: '2-digit', minute: '2-digit', hour12: false })
  if (diffDays === 0) return `${t('strigoi.run.today')} ${time}`
  if (diffDays === 1) return `${t('strigoi.run.yesterday')} ${time}`
  return `${diffDays}${t('strigoi.run.daysAgo')} ${time}`
}

function formatNextRun(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleDateString(locale.value, { weekday: 'short', month: 'short', day: 'numeric' }) +
    ', ' + d.toLocaleTimeString(locale.value, { hour: '2-digit', minute: '2-digit', hour12: false })
}

function formatAbsoluteDate(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleDateString(locale.value, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }) +
    ', ' + d.toLocaleTimeString(locale.value, { hour: '2-digit', minute: '2-digit', hour12: false })
}
</script>

<style scoped>
.sd-loading__item { margin-bottom: var(--space-4); }

.sd-notfound { color: var(--ash-gray); }
.sd-notfound__link { color: var(--blood-crimson); text-decoration: none; font-size: var(--text-body-sm); }
.sd-notfound__link:hover { color: var(--blood-crimson-bright); }

.sd-title { font-size: var(--text-h2); }
.sd-stats { margin-bottom: var(--space-8); }

/* Run meta line above the trace */
.sd-run-meta {
  display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap;
  font-size: var(--text-body-sm); color: var(--bone-ivory);
  margin-bottom: var(--space-4); padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--rule);
}
.sd-run-meta .sd-dot { color: var(--ash-gray); }
.sd-run-meta .sd-run-model { color: var(--ash-gray); }

/* verdict-grid layout (ported from styles.css:229,233, scoped) */
.verdict-grid { display: grid; grid-template-columns: 1fr 320px; gap: var(--space-8); align-items: start; }
.verdict-aside { position: sticky; top: var(--space-6); display: flex; flex-direction: column; gap: var(--space-5); }

/* kv list (ported from styles.css:236-239, scoped) */
.kv-list { display: flex; flex-direction: column; gap: var(--space-3); }
.kv-row { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-4); }
.kv-k { font-size: var(--text-body-sm); color: var(--ash-gray); }
.kv-v { font-size: var(--text-body-sm); color: var(--bone-ivory); text-align: right; }

/* recent-prey feed */
.feed { display: flex; flex-direction: column; gap: var(--space-4); }

@media (max-width: 959.98px) {
  .verdict-grid { grid-template-columns: 1fr; gap: var(--space-6); }
  .verdict-aside { position: static; }
}
</style>
