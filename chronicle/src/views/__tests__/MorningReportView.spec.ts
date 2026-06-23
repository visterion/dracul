import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import de from '../../i18n/locales/de'
import en from '../../i18n/locales/en'
import { mockMorningReport } from '../../mocks/morningReport'
import MorningReportView from '../MorningReportView.vue'

// Mock the api module so getMorningReport returns our fixture
vi.mock('../../api', () => ({
  useApi: () => ({
    getMorningReport: vi.fn().mockResolvedValue(mockMorningReport),
  }),
}))

// Stub heavy Vuetify skeleton loader — not relevant for the smoke test
const SkeletonStub = { template: '<div class="v-skeleton-loader-stub" />' }

const i18n = createI18n({
  legacy: false,
  locale: 'de',
  fallbackLocale: 'de',
  messages: { de, en },
})

describe('MorningReportView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders all 3 position symbols after loading', async () => {
    const wrapper = mount(MorningReportView, {
      global: {
        plugins: [i18n],
        stubs: {
          'v-skeleton-loader': SkeletonStub,
          BatGlyph: { template: '<span />' },
        },
      },
    })

    // Wait for onMounted async call + Vue reactivity
    await flushPromises()

    const html = wrapper.html()
    expect(html).toContain('BBB')
    expect(html).toContain('CCC')
    expect(html).toContain('AAA')
  })

  it('renders order-ticket cards for each position', async () => {
    const wrapper = mount(MorningReportView, {
      global: {
        plugins: [i18n],
        stubs: {
          'v-skeleton-loader': SkeletonStub,
          BatGlyph: { template: '<span />' },
        },
      },
    })

    await flushPromises()

    const tickets = wrapper.findAll('[data-testid="order-ticket"]')
    expect(tickets).toHaveLength(3)
  })

  it('renders the read-only note', async () => {
    const wrapper = mount(MorningReportView, {
      global: {
        plugins: [i18n],
        stubs: {
          'v-skeleton-loader': SkeletonStub,
          BatGlyph: { template: '<span />' },
        },
      },
    })

    await flushPromises()

    // The readonlyNote i18n key should be present
    expect(wrapper.html()).toContain('Dracul')
  })

  it('renders the report list container', async () => {
    const wrapper = mount(MorningReportView, {
      global: {
        plugins: [i18n],
        stubs: {
          'v-skeleton-loader': SkeletonStub,
          BatGlyph: { template: '<span />' },
        },
      },
    })

    await flushPromises()

    expect(wrapper.find('[data-testid="report-list"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="morning-report"]').exists()).toBe(true)
  })
})
