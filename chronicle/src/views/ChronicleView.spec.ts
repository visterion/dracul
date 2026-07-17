import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import ChronicleView from './ChronicleView.vue'
import DuskStrip from '../components/common/DuskStrip.vue'
import { useChronicleStore } from '../stores/chronicle'
import { useStatusStore } from '../stores/status'
import de from '../i18n/locales/de'

vi.mock('vuetify', () => ({
  useDisplay: () => ({ smAndDown: false }),
}))

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })
const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/', name: 'chronicle', component: { template: '<div/>' } }],
})

// historical dates: strictly before local midnight, so the OLD "today" logic would count 0
const longAgo = new Date(Date.now() - 30 * 86_400_000).toISOString()

function preyItem(id: string) {
  return {
    id, symbol: 'AAPL', companyName: 'Apple', anomalyType: 'SPIN',
    confidence: 0.4, discoveredAt: longAgo, discoveredBy: 'nosferatu',
  }
}
function verdictItem(id: string) {
  return { id, symbol: 'AAPL', companyName: 'Apple', createdAt: longAgo }
}

describe('ChronicleView dusk tally', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('shows the total prey/verdict count (not "today" = 0) for historical data', async () => {
    const store = useChronicleStore()
    const statusStore = useStatusStore()
    store.load = vi.fn().mockResolvedValue(undefined)
    statusStore.load = vi.fn().mockResolvedValue(undefined)
    store.prey = [preyItem('p1'), preyItem('p2'), preyItem('p3')] as never
    store.verdicts = [verdictItem('v1'), verdictItem('v2')] as never

    const w = shallowMount(ChronicleView, {
      global: { plugins: [i18n, router], stubs: { DuskStrip: true } },
    })
    await router.isReady()
    await w.vm.$nextTick()

    const dusk = w.findComponent(DuskStrip)
    expect(dusk.exists()).toBe(true)
    expect(dusk.props('prey')).toBe(3)
    expect(dusk.props('verdicts')).toBe(2)
  })
})

describe('ChronicleView archive toggle', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('loads active prey only by default (includeArchived=false)', async () => {
    const store = useChronicleStore()
    const statusStore = useStatusStore()
    store.load = vi.fn().mockResolvedValue(undefined)
    statusStore.load = vi.fn().mockResolvedValue(undefined)
    store.prey = [preyItem('p1')] as never

    shallowMount(ChronicleView, {
      global: { plugins: [i18n, router], stubs: { DuskStrip: true } },
    })
    await router.isReady()

    expect(store.load).toHaveBeenCalledWith()
    expect(store.includeArchived).toBe(false)
  })

  it('checking the toggle re-loads the chronicle with includeArchived=true', async () => {
    const store = useChronicleStore()
    const statusStore = useStatusStore()
    store.load = vi.fn().mockResolvedValue(undefined)
    statusStore.load = vi.fn().mockResolvedValue(undefined)
    store.prey = [preyItem('p1')] as never

    const w = shallowMount(ChronicleView, {
      global: { plugins: [i18n, router], stubs: { DuskStrip: true } },
    })
    await router.isReady()
    await w.vm.$nextTick()

    const toggle = w.find('[data-testid="chronicle-archive-toggle"]')
    expect(toggle.exists()).toBe(true)
    await toggle.setValue(true)

    expect(store.load).toHaveBeenCalledWith(true)
  })
})
