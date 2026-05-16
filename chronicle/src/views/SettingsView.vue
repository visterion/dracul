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

      <!-- Stub sections -->
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
import { ref, computed, onMounted } from 'vue'
import { useApi } from '../api'
import type { LlmProvider } from '../api/types'

const api = useApi()
const providers = ref<LlmProvider[]>([])
const loading = ref(true)
const navSection = ref('llm-providers')

const navItems = [
  { id: 'llm-providers', icon: '⚙', label: 'LLM Providers', disabled: false, badge: null },
  { id: 'agent-config', icon: '🦇', label: 'Agent Configuration', disabled: false, badge: null },
  { id: 'budgets', icon: '🪙', label: 'Budgets & Cost Control', disabled: false, badge: null },
  { id: 'data-sources', icon: '📊', label: 'Data Sources', disabled: false, badge: null },
  { id: 'messenger', icon: '💬', label: 'Messenger & Notifications', disabled: false, badge: null },
  { id: 'multi-user', icon: '👥', label: 'Multi-User Settings', disabled: true, badge: 'Phase 2' },
  { id: 'backup', icon: '💾', label: 'Backup & Export', disabled: false, badge: null },
  { id: 'about', icon: 'ℹ', label: 'About Dracul', disabled: false, badge: null },
]

const currentNavItem = computed(() =>
  navItems.find(i => i.id === navSection.value)
)

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
</style>
