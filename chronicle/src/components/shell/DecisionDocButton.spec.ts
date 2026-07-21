// @vitest-environment jsdom
// jsdom (not happy-dom) is required: this spec mounts MarkdownView, and
// happy-dom mis-serializes DOMPurify output.
import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount, enableAutoUnmount } from '@vue/test-utils'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import de from '../../i18n/locales/de'

// Each test picks the markdown value the stubbed composable returns.
const markdownRef = ref<string | null>(null)
vi.mock('../../composables/useDecisionDoc', () => ({
  useDecisionDoc: () => ({ markdown: markdownRef, loaded: ref(true) }),
}))

import DecisionDocButton from './DecisionDocButton.vue'

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

// Unmount every wrapper after each test so the module-level scroll-lock
// ref-count (useScrollLock) never leaks between tests.
enableAutoUnmount(afterEach)

function stubMain(overflow = 'auto') {
  document.querySelector('main.app-main')?.remove()
  const m = document.createElement('main')
  m.className = 'app-main'
  m.style.overflow = overflow
  document.body.appendChild(m)
  return m
}

function make() {
  return mount(DecisionDocButton, {
    attachTo: document.body,
    global: { plugins: [i18n] },
  })
}

describe('DecisionDocButton', () => {
  afterEach(() => {
    document.querySelector('main.app-main')?.remove()
  })

  it('opens a wide markdown panel and locks scroll when a doc is present', async () => {
    markdownRef.value = '# Doc-Titel\n\nInhalt'
    stubMain('auto')
    const w = make()

    const triggers = w.findAll('[data-testid="decision-info"]')
    expect(triggers).toHaveLength(1)

    expect(document.querySelector('[data-testid="decision-doc-panel"]')).toBeNull()
    await triggers[0].trigger('click')

    const panel = document.querySelector('[data-testid="decision-doc-panel"]')
    expect(panel).not.toBeNull()
    // MarkdownView renders the H1 from the doc source.
    expect(panel!.querySelector('h1')?.textContent).toContain('Doc-Titel')
    expect(panel!.getAttribute('role')).toBe('dialog')
    expect(panel!.getAttribute('aria-modal')).toBe('true')
    // Scroll is locked on the real container while the panel is open.
    expect(document.querySelector<HTMLElement>('main.app-main')!.style.overflow).toBe('hidden')

    w.unmount()
    // Lock released on unmount.
    expect(document.querySelector<HTMLElement>('main.app-main')!.style.overflow).toBe('auto')
  })

  it('falls back to the static decision.overview explainer when no doc', async () => {
    markdownRef.value = null
    const w = make()

    const triggers = w.findAll('[data-testid="decision-info"]')
    expect(triggers).toHaveLength(1)

    await triggers[0].trigger('click')

    // No wide markdown panel — the static explainer overlay opens instead.
    expect(document.querySelector('[data-testid="decision-doc-panel"]')).toBeNull()
    const overlay = document.querySelector('[data-testid="explainer-overlay"]')
    expect(overlay).not.toBeNull()
    expect(overlay!.textContent).toContain('Wie Dracul entscheidet')

    w.unmount()
  })
})
