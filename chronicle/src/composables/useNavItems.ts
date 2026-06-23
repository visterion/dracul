import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

/** Must be kept in sync with the route `name`s defined in the router. */
export type NavName =
  | 'chronicle' | 'watchlist' | 'portfolio' | 'morning-report' | 'pattern-library'
  | 'backtest' | 'settings'

export interface NavItem {
  name: NavName
  label: string
  /** Phosphor icon class (e.g. `ph-scroll`), rendered as `<i class="ph ph-…">`. */
  icon: string
}

/** Single source of truth for the 5 primary destinations, shared by the desktop
 *  top bar and the mobile bottom nav. Icons are Phosphor (regular) classes. */
export function useNavItems() {
  const { t } = useI18n()
  return computed<NavItem[]>(() => [
    { name: 'chronicle',       label: t('app.nav.chronicle'),      icon: 'ph-scroll' },
    { name: 'watchlist',       label: t('app.nav.watchlist'),      icon: 'ph-eye' },
    { name: 'portfolio',       label: t('app.nav.portfolio'),      icon: 'ph-vault' },
    { name: 'morning-report',  label: t('app.nav.report'),         icon: 'ph-sun-horizon' },
    { name: 'pattern-library', label: t('app.nav.patternLibrary'), icon: 'ph-book-open' },
    { name: 'backtest',        label: t('app.nav.backtest'),       icon: 'ph-chart-line-up' },
    { name: 'settings',        label: t('app.nav.settings'),       icon: 'ph-gear-six' },
  ])
}
