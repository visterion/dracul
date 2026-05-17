<template>
  <div class="settings">
    <nav class="settings__nav">
      <div
        v-for="item in navItems"
        :key="item.id"
        class="settings__nav-item"
        :class="{
          'settings__nav-item--active': navSection === item.id,
          'settings__nav-item--disabled': item.disabled,
        }"
        @click="!item.disabled && (navSection = item.id)"
      >
        <span class="settings__nav-icon">{{ item.icon }}</span>
        <span>{{ item.label }}</span>
        <span v-if="item.badge" class="settings__phase-badge">{{ item.badge }}</span>
      </div>
    </nav>

    <div class="settings__content">
      <!-- LLM Providers -->
      <template v-if="navSection === 'llm-providers'">
        <div class="settings__page-header">
          <h1 class="settings__page-title font-display">LLM Providers</h1>
          <p class="settings__page-subtitle">Configure which models the agents may call</p>
        </div>

        <div class="settings__section-header">── connected providers</div>

        <template v-if="loading">
          <v-skeleton-loader v-for="i in 3" :key="i" type="card" class="mb-3" />
        </template>

        <div
          v-for="provider in providers"
          :key="provider.id"
          class="settings__provider-card"
        >
          <div class="settings__provider-header">
            <span class="settings__provider-name">{{ provider.name }}</span>
            <span
              class="settings__provider-status"
              :class="`settings__provider-status--${provider.status}`"
            >
              ●
              {{
                provider.status === 'connected' ? 'connected' :
                provider.status === 'fallback' ? 'connected (fallback only)' :
                'running'
              }}
            </span>
          </div>

          <div class="settings__provider-row">
            <template v-if="provider.status === 'local'">
              Endpoint: <span>{{ provider.endpoint }}</span>
            </template>
            <template v-else>
              API key: <span>configured {{ provider.apiKeyMasked }}</span>
              &nbsp;<a href="#" class="settings__reveal-link" @click.prevent="() => {}">[reveal]</a>
            </template>
          </div>

          <div class="settings__provider-row">
            Models: <span>{{ provider.models.join(', ') }}</span>
          </div>

          <div class="settings__provider-row">
            <template v-if="provider.status === 'local'">
              Today: <span>{{ provider.callsToday ?? 0 }} calls · $0.00 (local compute)</span>
            </template>
            <template v-else-if="provider.todayInputTokens === 0">
              Today: <span>0 tokens · $0.00 — used only when Anthropic unavailable</span>
            </template>
            <template v-else>
              Today: <span>{{ provider.todayInputTokens.toLocaleString() }} input · {{ provider.todayOutputTokens.toLocaleString() }} output tokens · ${{ provider.todayCostUsd.toFixed(2) }}</span>
            </template>
          </div>

          <div class="settings__provider-actions">
            <button class="settings__btn-edit" @click="() => {}">edit</button>
            <button class="settings__btn-edit" @click="() => {}">test connection</button>
          </div>
        </div>

        <div class="settings__section-header">── add provider</div>
        <div class="settings__add-provider" @click="() => {}">
          <div>+ Add provider plugin</div>
          <small>Vistierie supports any provider implementing the ProviderPlugin interface</small>
        </div>
      </template>

      <!-- Budgets section -->
      <template v-else-if="navSection === 'budgets'">
        <div class="settings__page-header">
          <h1 class="settings__page-title font-display">Budgets &amp; Cost Control</h1>
          <p class="settings__page-subtitle">Daily and monthly spend limits per tenant and agent</p>
        </div>

        <div v-if="budgetError" class="settings__budget-error">{{ budgetError }}</div>

        <template v-if="budgetLoading">
          <v-skeleton-loader v-for="i in 4" :key="i" type="text" class="mb-2" />
        </template>

        <template v-else-if="budgetData">
          <div class="settings__section-header">── tenant budget (dracul)</div>
          <div class="settings__budget-grid">
            <div class="settings__budget-field">
              <label class="settings__budget-label">Daily cap (USD)</label>
              <input class="settings__budget-input" v-model="tenantEdit.dailyCapUsd" placeholder="∞" />
              <div class="settings__budget-usage">
                used: ${{ (budgetData.tenant.dailyUsageMicros / 1_000_000).toFixed(4) }}
              </div>
            </div>
            <div class="settings__budget-field">
              <label class="settings__budget-label">Monthly cap (USD)</label>
              <input class="settings__budget-input" v-model="tenantEdit.monthlyCapUsd" placeholder="∞" />
              <div class="settings__budget-usage">
                used: ${{ (budgetData.tenant.monthlyUsageMicros / 1_000_000).toFixed(2) }}
              </div>
            </div>
            <div class="settings__budget-field">
              <label class="settings__budget-label">Daily warn %</label>
              <input class="settings__budget-input" v-model="tenantEdit.dailyWarnPct" type="number" min="1" max="100" />
            </div>
            <div class="settings__budget-field">
              <label class="settings__budget-label">Monthly warn %</label>
              <input class="settings__budget-input" v-model="tenantEdit.monthlyWarnPct" type="number" min="1" max="100" />
            </div>
          </div>
          <div v-if="budgetData.tenant.dailyWarned || budgetData.tenant.dailyBlocked" class="settings__budget-flags">
            <span v-if="budgetData.tenant.dailyBlocked" class="settings__budget-flag--blocked">● daily blocked</span>
            <span v-else-if="budgetData.tenant.dailyWarned" class="settings__budget-flag--warned">● daily warn</span>
          </div>
          <div class="settings__budget-actions">
            <button
              class="settings__btn-save"
              :disabled="budgetSaving === 'tenant'"
              @click="saveTenantBudget"
            >{{ budgetSaving === 'tenant' ? 'Saving…' : 'Save tenant budget' }}</button>
          </div>

          <div class="settings__section-header settings__section-header--spaced">── per-agent budgets</div>
          <table class="settings__budget-table">
            <thead>
              <tr>
                <th>Agent</th>
                <th>Daily cap (USD)</th>
                <th>Monthly cap (USD)</th>
                <th>Daily used</th>
                <th>Monthly used</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="agent in budgetData.agents" :key="agent.name">
                <td class="settings__budget-agent">{{ agent.name }}</td>
                <td>
                  <input
                    class="settings__budget-input settings__budget-input--sm"
                    v-model="agentEdits[agent.name].dailyCapUsd"
                    placeholder="∞"
                  />
                </td>
                <td>
                  <input
                    class="settings__budget-input settings__budget-input--sm"
                    v-model="agentEdits[agent.name].monthlyCapUsd"
                    placeholder="∞"
                  />
                </td>
                <td class="settings__budget-num">${{ (agent.budget.dailyUsageMicros / 1_000_000).toFixed(4) }}</td>
                <td class="settings__budget-num">${{ (agent.budget.monthlyUsageMicros / 1_000_000).toFixed(2) }}</td>
                <td>
                  <button
                    class="settings__btn-edit"
                    :disabled="budgetSaving === agent.name"
                    @click="saveAgentBudget(agent.name)"
                  >{{ budgetSaving === agent.name ? '…' : 'save' }}</button>
                </td>
              </tr>
            </tbody>
          </table>
        </template>
      </template>

      <!-- Other stub sections -->
      <template v-else>
        <div class="settings__page-header">
          <h1 class="settings__page-title font-display">{{ currentNavItem?.label }}</h1>
        </div>
        <div class="settings__stub">
          <p>Configuration for this section is coming in a future etappe.</p>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useApi } from '../api'
import type { LlmProvider, BudgetPatch, SettingsBudgetData } from '../api/types'

const api = useApi()
const providers = ref<LlmProvider[]>([])
const loading = ref(true)
const navSection = ref('llm-providers')

const navItems = [
  { id: 'llm-providers', icon: '⚙', label: 'LLM Providers',              disabled: false, badge: null },
  { id: 'agent-config',  icon: '🦇', label: 'Agent Configuration',       disabled: false, badge: null },
  { id: 'budgets',       icon: '🪙', label: 'Budgets & Cost Control',    disabled: false, badge: null },
  { id: 'data-sources',  icon: '📊', label: 'Data Sources',              disabled: false, badge: null },
  { id: 'messenger',     icon: '💬', label: 'Messenger & Notifications', disabled: false, badge: null },
  { id: 'multi-user',    icon: '👥', label: 'Multi-User Settings',       disabled: true,  badge: 'Phase 2' },
  { id: 'backup',        icon: '💾', label: 'Backup & Export',           disabled: false, badge: null },
  { id: 'about',         icon: 'ℹ',  label: 'About Dracul',             disabled: false, badge: null },
]

const currentNavItem = computed(() => navItems.find(i => i.id === navSection.value))

// ── Budget state ───────────────────────────────────────────────
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
    const t = budgetData.value.tenant
    tenantEdit.value = {
      dailyCapUsd:    microsToUsd(t.dailyCapMicros),
      monthlyCapUsd:  microsToUsd(t.monthlyCapMicros),
      dailyWarnPct:   String(t.dailyWarnPercent ?? 80),
      monthlyWarnPct: String(t.monthlyWarnPercent ?? 80),
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

watch(navSection, (section) => {
  if (section === 'budgets' && !budgetData.value) loadBudgets()
})

onMounted(async () => {
  try {
    providers.value = await api.getProviders()
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.settings {
  display: grid;
  grid-template-columns: 220px 1fr;
  height: calc(100vh - 96px);
  overflow: hidden;
}

.settings__nav {
  background: var(--crypt-black-elevated);
  border-right: 1px solid rgba(255, 255, 255, 0.05);
  overflow-y: auto;
  padding: 16px 0;
}

.settings__nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 20px;
  font-size: 13px;
  color: var(--bone-ivory-dim);
  cursor: pointer;
  border-left: 2px solid transparent;
  transition: background 0.1s;
}
.settings__nav-item:hover:not(.settings__nav-item--disabled) {
  background: rgba(184, 148, 92, 0.04);
  color: var(--bone-ivory);
}
.settings__nav-item--active {
  color: var(--bone-ivory);
  background: rgba(161, 29, 44, 0.06);
  border-left-color: var(--blood-crimson);
}
.settings__nav-item--disabled {
  opacity: 0.4;
  cursor: default;
  pointer-events: none;
}

.settings__nav-icon { font-size: 14px; width: 18px; text-align: center; flex-shrink: 0; }

.settings__phase-badge {
  margin-left: auto;
  padding: 1px 5px;
  border: 1px solid var(--cathedral-gold);
  border-radius: 2px;
  font-size: 9px;
  color: var(--cathedral-gold);
  font-family: var(--font-mono);
  letter-spacing: 0.05em;
}

.settings__content {
  overflow-y: auto;
  padding: 28px 36px;
}

.settings__page-header { margin-bottom: 24px; }
.settings__page-title {
  font-size: 28px;
  font-weight: 400;
  color: var(--bone-ivory);
  margin: 0 0 4px 0;
}
.settings__page-subtitle { font-size: 14px; color: var(--bone-ivory-dim); margin: 0; }

.settings__section-header {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ash-gray);
  letter-spacing: 0.05em;
  margin: 20px 0 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.settings__section-header::after {
  content: '';
  flex: 1;
  height: 1px;
  background: rgba(255, 255, 255, 0.06);
}

.settings__provider-card {
  background: var(--crypt-black-elevated);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 2px;
  padding: 16px 20px;
  margin-bottom: 12px;
}
.settings__provider-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.settings__provider-name { font-size: 14px; font-weight: 500; }
.settings__provider-status { font-size: 12px; }
.settings__provider-status--connected { color: var(--signal-positive); }
.settings__provider-status--fallback { color: var(--cathedral-gold); }
.settings__provider-status--local { color: var(--signal-positive); }

.settings__provider-row {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ash-gray);
  margin-bottom: 4px;
}
.settings__provider-row span { color: var(--bone-ivory-dim); }

.settings__reveal-link {
  color: var(--cathedral-gold);
  text-decoration: none;
  font-size: 11px;
}
.settings__reveal-link:hover { color: var(--cathedral-gold-bright); }

.settings__provider-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.settings__btn-edit {
  padding: 4px 10px;
  background: none;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 2px;
  color: var(--bone-ivory-dim);
  font-size: 11px;
  cursor: pointer;
  font-family: var(--font-body);
}
.settings__btn-edit:hover { border-color: var(--cathedral-gold); color: var(--cathedral-gold); }

.settings__add-provider {
  border: 1px dashed rgba(255, 255, 255, 0.1);
  border-radius: 2px;
  padding: 20px;
  text-align: center;
  color: var(--ash-gray);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s;
}
.settings__add-provider:hover { border-color: var(--cathedral-gold); color: var(--bone-ivory-dim); }
.settings__add-provider small {
  display: block;
  font-size: 11px;
  margin-top: 4px;
  font-family: var(--font-mono);
}

.settings__stub {
  min-height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: var(--ash-gray);
  font-style: italic;
}

/* ── Budgets section ─────────────────────────────────────────── */
.settings__budget-error {
  color: var(--blood-crimson);
  font-size: var(--text-micro);
  margin-bottom: var(--space-4);
}
.settings__budget-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}
.settings__budget-field { display: flex; flex-direction: column; gap: var(--space-1); }
.settings__budget-label { font-size: var(--text-micro); color: var(--ash-gray); }
.settings__budget-input {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.10);
  border-radius: 4px;
  color: var(--bone-ivory);
  padding: 4px 8px;
  font-size: var(--text-body);
  font-family: var(--font-mono);
  width: 100%;
}
.settings__budget-input--sm { width: 80px; }
.settings__budget-usage { font-size: var(--text-micro); color: var(--ash-gray); }
.settings__budget-flags { margin-bottom: var(--space-3); }
.settings__budget-flag--blocked { font-size: var(--text-micro); color: var(--blood-crimson); }
.settings__budget-flag--warned  { font-size: var(--text-micro); color: var(--cathedral-gold); }
.settings__budget-actions { margin-bottom: var(--space-6); }
.settings__btn-save {
  padding: 6px 16px;
  background: var(--blood-crimson);
  border: none;
  border-radius: 4px;
  color: var(--bone-ivory);
  font-size: var(--text-body);
  cursor: pointer;
  opacity: 0.85;
}
.settings__btn-save:hover:not(:disabled) { opacity: 1; }
.settings__btn-save:disabled { opacity: 0.4; cursor: not-allowed; }
.settings__budget-table { width: 100%; border-collapse: collapse; font-size: var(--text-body); }
.settings__budget-table th {
  text-align: left;
  color: var(--ash-gray);
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  padding: var(--space-2) var(--space-3) var(--space-2) 0;
  font-weight: 400;
}
.settings__budget-table td {
  color: var(--bone-ivory-dim);
  padding: var(--space-2) var(--space-3) var(--space-2) 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.03);
  vertical-align: middle;
}
.settings__budget-agent { font-family: var(--font-mono); color: var(--bone-ivory); }
.settings__budget-num { font-family: var(--font-mono); }
.settings__section-header--spaced { margin-top: var(--space-8); }
</style>
