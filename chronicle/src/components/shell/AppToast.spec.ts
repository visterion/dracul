import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import AppToast from './AppToast.vue'
import { useToast } from '../../composables/useToast'
import de from '../../i18n/locales/de'

const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })

describe('AppToast', () => {
  beforeEach(() => {
    // clear the module-level singleton between tests
    const { toasts } = useToast()
    toasts.value = []
  })

  it('is a keyboard-operable button', () => {
    const { show } = useToast()
    show('done')
    const w = mount(AppToast, { global: { plugins: [i18n] } })
    const toast = w.find('[data-testid="app-toast"]')
    expect(toast.attributes('role')).toBe('button')
    expect(toast.attributes('tabindex')).toBe('0')
  })

  it('dismisses on Enter', async () => {
    const { show, toasts } = useToast()
    show('done')
    const w = mount(AppToast, { global: { plugins: [i18n] } })
    await nextTick()
    await w.find('[data-testid="app-toast"]').trigger('keydown.enter')
    expect(toasts.value).toHaveLength(0)
  })

  it('dismisses on Space', async () => {
    const { show, toasts } = useToast()
    show('done')
    const w = mount(AppToast, { global: { plugins: [i18n] } })
    await nextTick()
    await w.find('[data-testid="app-toast"]').trigger('keydown.space')
    expect(toasts.value).toHaveLength(0)
  })
})
