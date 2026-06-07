import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

export type NavName =
  | 'chronicle' | 'watchlist' | 'pattern-library'
  | 'vistierie' | 'backtest' | 'settings'

export interface NavItem {
  name: NavName
  label: string
  icon: string
}

/** Single source of truth for the 6 primary destinations, shared by the desktop
 *  top bar and the mobile bottom nav. Icons match the app's emoji-glyph convention. */
export function useNavItems() {
  const { t } = useI18n()
  return computed<NavItem[]>(() => [
    { name: 'chronicle',       label: t('app.nav.chronicle'),      icon: '🦇' },
    { name: 'watchlist',       label: t('app.nav.watchlist'),      icon: '📋' },
    { name: 'pattern-library', label: t('app.nav.patternLibrary'), icon: '📜' },
    { name: 'vistierie',       label: t('app.nav.vistierie'),      icon: '🏛' },
    { name: 'backtest',        label: t('app.nav.backtest'),       icon: '📊' },
    { name: 'settings',        label: t('app.nav.settings'),       icon: '⚙' },
  ])
}
