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

  it('shows the decision-overview (i) next to the bell', () => {
    const w = mountBar()
    const dot = w.findComponent({ name: 'InfoDot' })
    // InfoDot renders a fragment (button + Teleport), so the data-testid
    // cannot fall through to the DOM; assert the component + its props instead.
    expect(dot.exists()).toBe(true)
    expect(dot.props('topic')).toBe('decision.overview')
    expect(dot.props('variant')).toBe('icon')
    expect(dot.find('i.ph-info').exists()).toBe(true)
  })

  it('positions the (i) immediately after the bell button', () => {
    const w = mountBar()
    const controls = w.find('.top-bar__controls').element
    const bell = w.find('[data-testid="live-toggle"]').element
    // The InfoDot renders its icon button with the .info-dot--icon class.
    const info = w.find('.info-dot--icon').element
    expect(bell.parentElement).toBe(controls)
    expect(info.parentElement).toBe(controls)
    expect(bell.nextElementSibling).toBe(info)
  })
})
