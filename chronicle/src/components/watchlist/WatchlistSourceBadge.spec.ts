import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import WatchlistSourceBadge from './WatchlistSourceBadge.vue'
import de from '../../i18n/locales/de'
import en from '../../i18n/locales/en'

const i18nDe = createI18n({ legacy: false, locale: 'de', messages: { de } })
const i18nEn = createI18n({ legacy: false, locale: 'en', messages: { en } })

function make(source: string, i18n: typeof i18nDe | typeof i18nEn = i18nDe) {
  return mount(WatchlistSourceBadge, { props: { source }, global: { plugins: [i18n] } })
}

describe('WatchlistSourceBadge', () => {
  it('renders the German label for "seed"', () => {
    expect(make('seed').text()).toBe(de.watchlist.source.seed)
  })

  it('renders the German label for "manual"', () => {
    expect(make('manual').text()).toBe(de.watchlist.source.manual)
  })

  it('renders the German label for "verdict"', () => {
    expect(make('verdict').text()).toBe(de.watchlist.source.verdict)
  })

  it('renders the agent name for "agent:<name>"', () => {
    const w = make('agent:nosferatu')
    expect(w.text()).toContain('nosferatu')
    expect(w.text()).not.toBe('nosferatu') // wrapped in the agent-label template, not bare
  })

  it('falls back to the raw value for an unknown source', () => {
    expect(make('mystery-source').text()).toBe('mystery-source')
  })

  it('renders the English label for "seed"', () => {
    expect(make('seed', i18nEn).text()).toBe(en.watchlist.source.seed)
  })
})
