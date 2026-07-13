import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

/** Must be kept in sync with the route `name`s defined in the router. */
export type NavName =
  | 'chronicle' | 'watchlist' | 'depots' | 'morning-report' | 'pattern-library'
  | 'backtest' | 'settings'

export interface NavItem {
  name: NavName
  label: string
  /** Phosphor icon class (e.g. `ph-scroll`), rendered as `<i class="ph ph-…">`. */
  icon: string
  /** Path prefixes to match for active state. */
  matchPrefixes: string[]
}

/** startsWith matching; '/' only matches the root path exactly. */
export function isNavActive(matchPrefixes: string[], path: string): boolean {
  return matchPrefixes.some(p =>
    p === '/' ? path === '/' : path === p || path.startsWith(p + '/'),
  )
}

/** Single source of truth for the 5 primary destinations, shared by the desktop
 *  top bar and the mobile bottom nav. Icons are Phosphor (regular) classes. */
export function useNavItems() {
  const { t } = useI18n()
  return computed<NavItem[]>(() => [
    { name: 'chronicle',       label: t('app.nav.chronicle'),      icon: 'ph-scroll',        matchPrefixes: ['/', '/prey', '/verdict', '/strigoi', '/exit-signal'] },
    { name: 'watchlist',       label: t('app.nav.watchlist'),      icon: 'ph-eye',           matchPrefixes: ['/watchlist'] },
    { name: 'depots',          label: t('app.nav.depots'),         icon: 'ph-vault',         matchPrefixes: ['/depots', '/portfolio'] },
    { name: 'morning-report',  label: t('app.nav.report'),         icon: 'ph-sun-horizon',   matchPrefixes: ['/report'] },
    { name: 'pattern-library', label: t('app.nav.patternLibrary'), icon: 'ph-book-open',     matchPrefixes: ['/patterns'] },
    { name: 'backtest',        label: t('app.nav.backtest'),       icon: 'ph-chart-line-up', matchPrefixes: ['/backtest'] },
    { name: 'settings',        label: t('app.nav.settings'),       icon: 'ph-gear-six',      matchPrefixes: ['/settings'] },
  ])
}
