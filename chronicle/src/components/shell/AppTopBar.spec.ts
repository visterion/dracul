import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import AppTopBar from './AppTopBar.vue'
import de from '../../i18n/locales/de'

// useMe() calls useApi().getMe() at module scope; stub the API so the singleton
// resolves to an empty email instead of hitting HttpApiClient.
vi.mock('../../api', () => ({
  useApi: () => ({ getMe: async () => ({ email: '' }) }),
}))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })
const stub = { template: '<div/>' }
const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', name: 'chronicle', component: stub },
    { path: '/watchlist', name: 'watchlist', component: stub },
    { path: '/depots', name: 'depots', component: stub },
    { path: '/report', name: 'morning-report', component: stub },
    { path: '/patterns', name: 'pattern-library', component: stub },
    { path: '/backtest', name: 'backtest', component: stub },
    { path: '/settings', name: 'settings', component: stub },
    { path: '/inspector', name: 'inspector', component: stub },
  ],
})

function mountBar() {
  return mount(AppTopBar, {
    global: { plugins: [i18n, createPinia(), router] },
  })
}

describe('AppTopBar', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('shows the decision (i) next to the bell', async () => {
    const w = mountBar()
    const info = w.find('[data-testid="decision-info"]')
    expect(info.exists()).toBe(true)
    // The API stub has no getDecisionDoc, so the doc load fails -> null and the
    // button falls back to the static decision.overview explainer overlay.
    expect(document.querySelector('[data-testid="explainer-overlay"]')).toBeNull()
    await info.trigger('click')
    expect(document.querySelector('[data-testid="explainer-overlay"]')).not.toBeNull()
    w.unmount()
  })

  it('positions the (i) immediately after the bell button', () => {
    const w = mountBar()
    const controls = w.find('.top-bar__controls').element
    const bell = w.find('[data-testid="live-toggle"]').element
    // DecisionDocButton renders the (i) trigger with this testid.
    const info = w.find('[data-testid="decision-info"]').element
    expect(bell.parentElement).toBe(controls)
    expect(info.parentElement).toBe(controls)
    expect(bell.nextElementSibling).toBe(info)
  })
})
