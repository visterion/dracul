<template>
  <div class="content-inner full-bleed">
    <div class="settings-grid" :class="{ 'settings-grid--mobile': smAndDown }">
      <aside class="settings-nav" :class="{ 'settings-nav--chips': smAndDown }">
        <button
          v-for="item in navItems"
          :key="item.id"
          type="button"
          class="set-nav-item"
          :class="{
            active: active === item.id,
            disabled: item.disabled,
            'admin-only': item.admin,
          }"
          :aria-current="active === item.id ? 'page' : undefined"
          :disabled="item.disabled"
          @click="active = item.id"
        >
          <i v-if="item.icon" :class="['ph', item.icon]" />
          <span>{{ item.label }}</span>
          <span v-if="item.admin" class="set-badge admin">{{ t('settings.adminBadge') }}</span>
          <span v-else-if="item.badge" class="set-badge">{{ item.badge }}</span>
        </button>
      </aside>

      <div class="settings-body">
        <!-- Schatzkammer · Vistierie (admin only) -->
        <VistierieView v-if="active === 'schatzkammer' && isAdmin" embedded />

        <!-- LLM Providers -->
        <template v-else-if="active === 'providers'">
          <PageHead :title="t('settings.providers.title')" :sub="t('settings.providers.subtitle')" />

          <SectionHeader :label="t('settings.providers.sectionConnected')" />
          <div v-if="providerError" role="alert" class="set-budget-error">{{ providerError }}</div>
          <template v-if="providersLoading">
            <div class="stack-5">
              <v-skeleton-loader v-for="i in 3" :key="i" type="card" />
            </div>
          </template>
          <div v-else class="stack-5">
            <ProviderCard
              v-for="provider in providers"
              :key="provider.id"
              :provider="provider"
            />
          </div>

          <div class="set-gap" />

          <SectionHeader :label="t('settings.providers.sectionAddProvider')" />
          <button class="add-provider" type="button">
            <i class="ph ph-plus" />
            <span>{{ t('settings.providers.addPlugin') }}</span>
            <span class="ap-note mono">{{ t('settings.providers.addPluginHint') }}</span>
          </button>
        </template>

        <!-- Budgets -->
        <template v-else-if="active === 'budgets'">
          <PageHead :title="t('settings.budgets.title')" :sub="t('settings.budgets.subtitle')" />

          <div v-if="budgetError" class="set-budget-error">{{ budgetError }}</div>

          <template v-if="budgetLoading">
            <v-skeleton-loader v-for="i in 4" :key="i" type="text" class="mb-2" />
          </template>

          <template v-else-if="budgetData">
            <SectionHeader :label="t('settings.budgets.sectionTenant')" />
            <div class="set-budget-grid">
              <div class="set-budget-field">
                <label class="set-budget-label">{{ t('settings.budgets.dailyCap') }}</label>
                <input class="set-budget-input" v-model="tenantEdit.dailyCapUsd" placeholder="∞" />
                <div class="set-budget-usage">
                  {{ t('settings.budgets.used', { amount: (budgetData.tenant.dailyUsageMicros / 1_000_000).toFixed(4) }) }}
                </div>
              </div>
              <div class="set-budget-field">
                <label class="set-budget-label">{{ t('settings.budgets.monthlyCap') }}</label>
                <input class="set-budget-input" v-model="tenantEdit.monthlyCapUsd" placeholder="∞" />
                <div class="set-budget-usage">
                  {{ t('settings.budgets.used', { amount: (budgetData.tenant.monthlyUsageMicros / 1_000_000).toFixed(2) }) }}
                </div>
              </div>
              <div class="set-budget-field">
                <label class="set-budget-label">{{ t('settings.budgets.dailyWarn') }}</label>
                <input class="set-budget-input" v-model="tenantEdit.dailyWarnPct" type="number" min="1" max="100" />
              </div>
              <div class="set-budget-field">
                <label class="set-budget-label">{{ t('settings.budgets.monthlyWarn') }}</label>
                <input class="set-budget-input" v-model="tenantEdit.monthlyWarnPct" type="number" min="1" max="100" />
              </div>
            </div>
            <div v-if="budgetData.tenant.dailyWarned || budgetData.tenant.dailyBlocked" class="set-budget-flags">
              <span v-if="budgetData.tenant.dailyBlocked" class="set-budget-flag--blocked">{{ t('settings.budgets.flagBlocked') }}</span>
              <span v-else-if="budgetData.tenant.dailyWarned" class="set-budget-flag--warned">{{ t('settings.budgets.flagWarned') }}</span>
            </div>
            <div class="set-budget-actions">
              <button
                class="btn"
                :disabled="budgetSaving === 'tenant'"
                @click="saveTenantBudget"
              >{{ budgetSaving === 'tenant' ? t('settings.budgets.saving') : t('settings.budgets.saveTenant') }}</button>
            </div>

            <SectionHeader :label="t('settings.budgets.sectionAgents')" />
            <table class="set-budget-table">
              <thead>
                <tr>
                  <th>{{ t('settings.budgets.tableAgent') }}</th>
                  <th>{{ t('settings.budgets.tableDailyCap') }}</th>
                  <th>{{ t('settings.budgets.tableMonthlyCap') }}</th>
                  <th>{{ t('settings.budgets.tableDailyUsed') }}</th>
                  <th>{{ t('settings.budgets.tableMonthlyUsed') }}</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="agent in budgetData.agents" :key="agent.name">
                  <td class="set-budget-agent">{{ agent.name }}</td>
                  <td><input class="set-budget-input set-budget-input--sm" v-model="agentEdits[agent.name].dailyCapUsd" placeholder="∞" /></td>
                  <td><input class="set-budget-input set-budget-input--sm" v-model="agentEdits[agent.name].monthlyCapUsd" placeholder="∞" /></td>
                  <td class="set-budget-num">${{ (agent.budget.dailyUsageMicros / 1_000_000).toFixed(4) }}</td>
                  <td class="set-budget-num">${{ (agent.budget.monthlyUsageMicros / 1_000_000).toFixed(2) }}</td>
                  <td>
                    <button
                      class="btn btn-secondary"
                      :disabled="budgetSaving === agent.name"
                      @click="saveAgentBudget(agent.name)"
                    >{{ budgetSaving === agent.name ? t('settings.budgets.savingAgent') : t('settings.budgets.saveAgent') }}</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </template>
        </template>

        <!-- Language -->
        <template v-else-if="active === 'language'">
          <PageHead :title="t('settings.language.title')" :sub="t('settings.language.subtitle')" />
          <div class="set-language" data-testid="language-section">
            <label class="set-budget-label" for="language-select">{{ t('common.language') }}</label>
            <select
              id="language-select"
              class="set-language-select"
              data-testid="language-select"
              :value="language"
              :disabled="languageSaving"
              @change="changeLanguage(($event.target as HTMLSelectElement).value)"
            >
              <option value="de">{{ t('settings.language.german') }}</option>
              <option value="en">{{ t('settings.language.english') }}</option>
            </select>
          </div>
        </template>

        <!-- Agent config -->
        <template v-else-if="active === 'agent-config'">
          <PageHead :title="t('settings.agentConfig.title')" :sub="t('settings.agentConfig.subtitle')" />

          <div v-if="agentError" role="alert" class="set-budget-error">{{ agentError }}</div>

          <template v-if="agentsLoading">
            <v-skeleton-loader v-for="i in 4" :key="i" type="list-item" />
          </template>

          <div v-else-if="agentData && agentData.length" class="stack-5" data-testid="agent-config-list">
            <div v-for="row in agentData" :key="row.name" class="agent-row" :data-agent="row.name">
              <div class="agent-row__main">
                <div class="agent-row__name">{{ row.name }}</div>
                <span class="agent-row__role mono">{{ agentRoleLabel(row.role) }}</span>
              </div>
              <div class="agent-row__meta mono">
                <span class="agent-row__state" :data-state="row.state">{{ agentStateLabel(row.state) }}</span>
                <span>{{ agentSchedule(row) }}</span>
                <span v-if="row.tier">{{ agentTierLabel(row.tier) }}</span>
                <span v-if="row.primaryProvider">{{ row.primaryProvider }}</span>
                <span>${{ row.dailyUsedUsd.toFixed(2) }} / ${{ row.dailyBudgetUsd.toFixed(2) }}</span>
              </div>
              <button
                class="btn btn-secondary agent-row__toggle"
                :disabled="pausing === row.name"
                :data-testid="`agent-pause-${row.name}`"
                @click="togglePause(row)"
              >{{ row.paused ? t('settings.agentConfig.resume') : t('settings.agentConfig.pause') }}</button>
            </div>
          </div>

          <div v-else class="empty">
            <div class="em-icon"><BatGlyph :size="28" :dim="false" /></div>
            <div class="em-text">{{ t('settings.agentConfig.empty') }}</div>
          </div>
        </template>

        <!-- Data sources -->
        <template v-else-if="active === 'data-sources'">
          <PageHead :title="t('settings.dataSources.title')" :sub="t('settings.dataSources.subtitle')" />

          <div class="ds-actions">
            <button class="btn btn-secondary" :disabled="rechecking || dataSourcesLoading"
                    data-testid="ds-recheck" @click="loadDataSources(true)">
              {{ rechecking ? t('settings.dataSources.checking') : t('settings.dataSources.recheck') }}
            </button>
          </div>

          <div v-if="dataSourceError" role="alert" class="set-budget-error">{{ dataSourceError }}</div>

          <template v-if="dataSourcesLoading">
            <v-skeleton-loader v-for="i in 4" :key="i" type="list-item" />
          </template>

          <div v-else-if="dataSourceData && dataSourceData.length" class="stack-5" data-testid="data-sources-list">
            <div v-for="ds in dataSourceData" :key="ds.id" class="ds-row" :data-source="ds.id">
              <div class="ds-row__main">
                <div class="ds-row__label">{{ ds.label }}</div>
                <span class="ds-row__used mono">{{ t('settings.dataSources.usedBy') }}: {{ ds.usedBy.join(', ') }}</span>
              </div>
              <div class="ds-row__meta mono">
                <span class="ds-row__status" :data-status="ds.status">{{ t('settings.dataSources.status.' + ds.status) }}</span>
                <span v-if="ds.httpStatus">HTTP {{ ds.httpStatus }}</span>
                <span v-if="ds.latencyMs != null">{{ ds.latencyMs }} ms</span>
                <span>{{ ds.rateLimitNote }}</span>
                <span v-if="ds.detail" class="ds-row__detail">{{ ds.detail }}</span>
                <span>{{ t('settings.dataSources.lastChecked') }}: {{ dsCheckedAt(ds.checkedAt) }}</span>
              </div>
            </div>
          </div>

          <div v-else class="empty">
            <div class="em-icon"><BatGlyph :size="28" :dim="false" /></div>
            <div class="em-text">{{ t('settings.dataSources.empty') }}</div>
          </div>
        </template>

        <!-- Stub for the remaining sections -->
        <template v-else>
          <PageHead :title="currentNavItem?.label ?? t('settings.title')" :sub="t('settings.stubSub')" />
          <div class="empty">
            <div class="em-icon"><BatGlyph :size="28" :dim="false" /></div>
            <div class="em-text">{{ t('settings.stub') }}</div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useDisplay } from 'vuetify'
import { useApi } from '../api'
import { setLocale } from '../i18n'
import type { LlmProvider, BudgetPatch, SettingsBudgetData, AgentConfigRow, DataSourceHealth } from '../api/types'
import VistierieView from './VistierieView.vue'
import PageHead from '../components/common/PageHead.vue'
import BatGlyph from '../components/common/BatGlyph.vue'
import SectionHeader from '../components/common/SectionHeader.vue'
import ProviderCard from '../components/common/ProviderCard.vue'
import { humanScheduleText } from '../utils/schedule'
import { useEnumLabels } from '../composables/useEnumLabels'

const { t, locale } = useI18n()
const { agentRoleLabel, agentTierLabel, agentStateLabel } = useEnumLabels()
const api = useApi()
const { smAndDown } = useDisplay()

// No user/admin store exists in Chronicle — Dracul is single-operator, so the
// operator is always the admin (matches the prototype's isAdmin default).
const isAdmin = true

const navItems = computed(() => [
  ...(isAdmin
    ? [{ id: 'schatzkammer', icon: 'ph-coins', label: t('settings.nav.schatzkammer'), admin: true, disabled: false, badge: null as string | null }]
    : []),
  { id: 'providers',    icon: 'ph-gear',          label: t('settings.nav.llmProviders'), admin: false, disabled: false, badge: null as string | null },
  { id: 'agent-config', icon: 'ph-bug-beetle',    label: t('settings.nav.agentConfig'),  admin: false, disabled: false, badge: null as string | null },
  { id: 'budgets',      icon: 'ph-coin',          label: t('settings.nav.budgets'),      admin: false, disabled: false, badge: null as string | null },
  { id: 'data-sources', icon: 'ph-chart-bar',     label: t('settings.nav.dataSources'),  admin: false, disabled: false, badge: null as string | null },
  { id: 'messenger',    icon: 'ph-chat-circle',   label: t('settings.nav.messenger'),    admin: false, disabled: false, badge: null as string | null },
  { id: 'multi-user',   icon: 'ph-users',         label: t('settings.nav.multiUser'),    admin: false, disabled: true,  badge: 'Phase 2' },
  { id: 'backup',       icon: 'ph-floppy-disk',   label: t('settings.nav.backup'),       admin: false, disabled: false, badge: null as string | null },
  { id: 'language',     icon: 'ph-globe',         label: t('settings.nav.language'),     admin: false, disabled: false, badge: null as string | null },
  { id: 'about',        icon: 'ph-info',          label: t('settings.nav.about'),        admin: false, disabled: false, badge: null as string | null },
])

const active = ref(isAdmin ? 'schatzkammer' : 'providers')
const currentNavItem = computed(() => navItems.value.find(i => i.id === active.value))

// ── Providers ──────────────────────────────────────────────────
const providers = ref<LlmProvider[]>([])
const providersLoading = ref(true)
const providerError = ref<string | null>(null)

// ── Language ───────────────────────────────────────────────────
const language = ref('de')
const languageSaving = ref(false)

async function changeLanguage(lang: string) {
  languageSaving.value = true
  try {
    const res = await api.setLanguage(lang)
    language.value = res.language
    setLocale(res.language)
  } finally {
    languageSaving.value = false
  }
}

// ── Budgets ────────────────────────────────────────────────────
const budgetData    = ref<SettingsBudgetData | null>(null)
const budgetLoading = ref(false)
const budgetSaving  = ref<string | null>(null)
const budgetError   = ref<string | null>(null)

const tenantEdit = ref({ dailyCapUsd: '', monthlyCapUsd: '', dailyWarnPct: '80', monthlyWarnPct: '80' })
const agentEdits = ref<Record<string, { dailyCapUsd: string; monthlyCapUsd: string }>>({})

function microsToUsd(micros: number | null): string {
  if (micros === null) return '∞'
  return (micros / 1_000_000).toFixed(2)
}
function usdToMicros(usd: string): number | null {
  if (usd === '∞' || usd === '') return null
  const n = parseFloat(usd)
  return isNaN(n) ? null : Math.round(n * 1_000_000)
}

async function loadBudgets() {
  budgetLoading.value = true
  budgetError.value = null
  try {
    budgetData.value = await api.getSettingsBudgets()
    const tb = budgetData.value.tenant
    tenantEdit.value = {
      dailyCapUsd:    microsToUsd(tb.dailyCapMicros),
      monthlyCapUsd:  microsToUsd(tb.monthlyCapMicros),
      dailyWarnPct:   String(tb.dailyWarnPercent ?? 80),
      monthlyWarnPct: String(tb.monthlyWarnPercent ?? 80),
    }
    for (const a of budgetData.value.agents) {
      agentEdits.value[a.name] = {
        dailyCapUsd:   microsToUsd(a.budget.dailyCapMicros),
        monthlyCapUsd: microsToUsd(a.budget.monthlyCapMicros),
      }
    }
  } catch (e) {
    budgetError.value = e instanceof Error ? e.message : 'Failed to load budgets'
  } finally {
    budgetLoading.value = false
  }
}

async function saveTenantBudget() {
  budgetSaving.value = 'tenant'
  budgetError.value = null
  try {
    const patch: BudgetPatch = {
      dailyCapMicros:     usdToMicros(tenantEdit.value.dailyCapUsd),
      monthlyCapMicros:   usdToMicros(tenantEdit.value.monthlyCapUsd),
      dailyWarnPercent:   parseInt(tenantEdit.value.dailyWarnPct) || null,
      monthlyWarnPercent: parseInt(tenantEdit.value.monthlyWarnPct) || null,
    }
    budgetData.value!.tenant = await api.patchSettingsBudget(patch)
  } catch (e) {
    budgetError.value = e instanceof Error ? e.message : 'Save failed'
  } finally {
    budgetSaving.value = null
  }
}

async function saveAgentBudget(agentName: string) {
  budgetSaving.value = agentName
  budgetError.value = null
  try {
    const edit = agentEdits.value[agentName]
    const patch: BudgetPatch = {
      dailyCapMicros:   usdToMicros(edit.dailyCapUsd),
      monthlyCapMicros: usdToMicros(edit.monthlyCapUsd),
    }
    const updated = await api.patchAgentBudget(agentName, patch)
    const agent = budgetData.value?.agents.find(a => a.name === agentName)
    if (agent) agent.budget = updated
  } catch (e) {
    budgetError.value = e instanceof Error ? e.message : 'Save failed'
  } finally {
    budgetSaving.value = null
  }
}

// ── Agent Config ───────────────────────────────────────────────
const agentData = ref<AgentConfigRow[] | null>(null)
const agentsLoading = ref(false)
const agentError = ref<string | null>(null)
const pausing = ref<string | null>(null)

async function loadAgents() {
  agentsLoading.value = true
  agentError.value = null
  try {
    agentData.value = await api.getAgents()
  } catch (e) {
    agentError.value = e instanceof Error ? e.message : t('settings.agentConfig.loadError')
  } finally {
    agentsLoading.value = false
  }
}

async function togglePause(row: AgentConfigRow) {
  pausing.value = row.name
  const previous = { paused: row.paused, state: row.state }
  // optimistic
  row.paused = !row.paused
  row.state = row.paused ? 'paused' : 'resting'
  try {
    const updated = await api.setAgentPaused(row.name, row.paused)
    row.paused = updated.paused
    row.state = updated.state
  } catch {
    row.paused = previous.paused
    row.state = previous.state
    agentError.value = t('settings.agentConfig.pauseError')
  } finally {
    pausing.value = null
  }
}

function agentSchedule(row: AgentConfigRow): string {
  return humanScheduleText(row.schedule, row.nextRunAt, locale.value, t)
}

// ── Data Sources ───────────────────────────────────────────────
const dataSourceData = ref<DataSourceHealth[] | null>(null)
const dataSourcesLoading = ref(false)
const dataSourceError = ref<string | null>(null)
const rechecking = ref(false)

async function loadDataSources(refresh = false) {
  if (refresh) rechecking.value = true; else dataSourcesLoading.value = true
  dataSourceError.value = null
  try {
    dataSourceData.value = await api.getDataSources(refresh)
  } catch (e) {
    dataSourceError.value = e instanceof Error ? e.message : t('settings.dataSources.loadError')
  } finally {
    dataSourcesLoading.value = false
    rechecking.value = false
  }
}

function dsCheckedAt(iso: string): string {
  const d = new Date(iso)
  return isNaN(d.getTime()) ? '—' : d.toLocaleTimeString(locale.value, { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

watch(active, (section) => {
  if (section === 'budgets' && !budgetData.value) loadBudgets()
  if (section === 'agent-config' && !agentData.value) loadAgents()
  if (section === 'data-sources' && !dataSourceData.value) loadDataSources()
})

onMounted(async () => {
  try {
    providers.value = await api.getProviders()
  } catch (e) {
    providerError.value = e instanceof Error ? e.message : t('settings.providers.loadError')
  } finally {
    providersLoading.value = false
  }
  try {
    const { language: lang } = await api.getLanguage()
    language.value = lang
    setLocale(lang)
  } catch { /* keep current locale */ }
})
</script>

<style scoped>
/* .settings-grid / .settings-nav / .set-* are NOT global — scoped (styles.css:455-481, 537-543) */
.settings-grid { display: grid; grid-template-columns: 264px 1fr; height: 100%; }
.settings-nav {
  border-right: var(--hairline);
  padding: var(--space-5) var(--space-3);
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
}
.set-nav-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  text-align: left;
  background: none;
  border: none;
  color: var(--bone-ivory-dim);
  font-size: var(--text-body-sm);
  padding: var(--space-3);
  border-radius: 4px;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.set-nav-item i { font-size: 18px; color: var(--ash-gray); }
.set-nav-item:hover:not(.disabled) { background: rgba(184, 148, 92, 0.05); color: var(--bone-ivory); }
.set-nav-item.active { background: rgba(161, 29, 44, 0.1); color: var(--bone-ivory); }
.set-nav-item.active i { color: var(--cathedral-gold); }
.set-nav-item.disabled { opacity: 0.5; cursor: not-allowed; }
.set-nav-item.admin-only i { color: var(--cathedral-gold); }
.set-badge {
  margin-left: auto;
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--cathedral-gold);
  border: 1px solid rgba(184, 148, 92, 0.35);
  border-radius: 3px;
  padding: 2px 6px;
}
.set-badge.admin { color: var(--blood-crimson-bright); border-color: rgba(196, 36, 58, 0.4); }

.settings-body { padding: var(--space-8); overflow-y: auto; }
.set-gap { height: var(--space-6); }

.add-provider {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  background: none;
  border: 1px dashed rgba(184, 148, 92, 0.3);
  border-radius: 4px;
  padding: var(--space-5);
  cursor: pointer;
  color: var(--bone-ivory-dim);
  transition: border-color var(--transition-fast);
  flex-wrap: wrap;
}
.add-provider:hover { border-color: var(--cathedral-gold); }
.add-provider i { font-size: 20px; color: var(--cathedral-gold); }
.ap-note { font-size: var(--text-micro); color: var(--ash-gray); margin-left: auto; }

/* ── Budgets ──────────────────────────────────────────────────── */
.set-budget-error { color: var(--blood-crimson); font-size: var(--text-micro); margin-bottom: var(--space-4); }
.set-budget-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-4); margin-bottom: var(--space-4); }
.set-budget-field { display: flex; flex-direction: column; gap: var(--space-1); }
.set-budget-label { font-size: var(--text-micro); color: var(--ash-gray); }
.set-budget-input {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.10);
  border-radius: 4px;
  color: var(--bone-ivory);
  padding: 4px 8px;
  font-size: var(--text-body);
  font-family: var(--font-mono);
  width: 100%;
}
.set-budget-input--sm { width: 80px; }
.set-budget-usage { font-size: var(--text-micro); color: var(--ash-gray); }
.set-budget-flags { margin-bottom: var(--space-3); }
.set-budget-flag--blocked { font-size: var(--text-micro); color: var(--blood-crimson); }
.set-budget-flag--warned  { font-size: var(--text-micro); color: var(--cathedral-gold); }
.set-budget-actions { margin-bottom: var(--space-6); }
.set-budget-table { width: 100%; border-collapse: collapse; font-size: var(--text-body); }
.set-budget-table th {
  text-align: left;
  color: var(--ash-gray);
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  padding: var(--space-2) var(--space-3) var(--space-2) 0;
  font-weight: 400;
}
.set-budget-table td {
  color: var(--bone-ivory-dim);
  padding: var(--space-2) var(--space-3) var(--space-2) 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.03);
  vertical-align: middle;
}
.set-budget-agent { font-family: var(--font-mono); color: var(--bone-ivory); }
.set-budget-num { font-family: var(--font-mono); }

/* ── Language ─────────────────────────────────────────────────── */
.set-language { display: flex; flex-direction: column; gap: var(--space-2); max-width: 280px; }
.set-language-select {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.10);
  border-radius: 4px;
  color: var(--bone-ivory);
  padding: 6px 10px;
  font-size: var(--text-body);
  font-family: var(--font-body);
  cursor: pointer;
  appearance: auto;
}
.set-language-select:focus { outline: 1px solid var(--blood-crimson); border-color: var(--blood-crimson); }

/* ── Mobile: nav becomes a horizontal chip row (styles.css:537-543) ── */
.settings-grid--mobile { grid-template-columns: 1fr; height: auto; }
.settings-nav--chips {
  flex-direction: row;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
  border-right: none;
  border-bottom: var(--hairline);
  padding: var(--space-3);
  gap: var(--space-2);
}
.settings-nav--chips::-webkit-scrollbar { display: none; }
.settings-nav--chips .set-nav-item { flex: 0 0 auto; white-space: nowrap; width: auto; }
.settings-grid--mobile .set-budget-grid { grid-template-columns: 1fr; }

/* ── Agent Config ─────────────────────────────────────────────── */
.agent-row { display: flex; align-items: center; gap: 16px; padding: 12px 14px;
  border: 1px solid rgba(184, 148, 92, 0.12); border-radius: 6px; }
.agent-row__main { display: flex; flex-direction: column; min-width: 160px; }
.agent-row__name { color: var(--bone-ivory); font-weight: 600; }
.agent-row__role { font-size: 11px; color: var(--ash-gray); }
.agent-row__meta { display: flex; flex-wrap: wrap; gap: 12px; flex: 1;
  font-size: 12px; color: var(--ash-gray); }
.agent-row__state[data-state="paused"] { color: var(--cathedral-gold); }
.agent-row__state[data-state="budget-hit"] { color: var(--blood-red); }
.agent-row__toggle { flex: 0 0 auto; }

/* ── Data Sources ─────────────────────────────────────────────── */
.ds-actions { display: flex; justify-content: flex-end; margin-bottom: 12px; }
.ds-row { display: flex; align-items: center; gap: 16px; padding: 12px 14px;
  border: 1px solid rgba(184, 148, 92, 0.12); border-radius: 6px; }
.ds-row__main { display: flex; flex-direction: column; min-width: 180px; }
.ds-row__label { color: var(--bone-ivory); font-weight: 600; }
.ds-row__used { font-size: 11px; color: var(--ash-gray); }
.ds-row__meta { display: flex; flex-wrap: wrap; gap: 12px; flex: 1; font-size: 12px; color: var(--ash-gray); }
.ds-row__status[data-status="ok"] { color: #8a9a5b; }
.ds-row__status[data-status="rate_limited"] { color: var(--cathedral-gold); }
.ds-row__status[data-status="error"], .ds-row__status[data-status="timeout"] { color: var(--blood-red); }
</style>
