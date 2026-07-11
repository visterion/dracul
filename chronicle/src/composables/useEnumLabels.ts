import { useI18n } from 'vue-i18n'
import { formatHorizon } from '../utils/format'

/**
 * Localizes backend domain codes (enums and open-ended upstream strings) for display.
 * Each function builds an i18n key and returns the translation if it exists, otherwise
 * the raw value verbatim. The raw fallback is the crash-safety mechanism for codes typed
 * as `string` (trigger type, agent role/tier/state), whose value set is not closed.
 */
export function useEnumLabels() {
  const { t, te } = useI18n()

  const label = (key: string, raw: unknown): string =>
    te(key) ? t(key) : String(raw ?? '')

  return {
    triggerTypeLabel: (v: string) => label(`enums.triggerType.${v}`, v),
    severityLabel: (v: string) => label(`enums.severity.${v}`, v),
    anomalyTypeLabel: (v: string) => label(`enums.anomalyType.${v}`, v),
    horizonLabel: (v: string) => (te(`enums.horizon.${v}`) ? t(`enums.horizon.${v}`) : formatHorizon(v)),
    connectionStatusLabel: (v: string) => label(`enums.connectionStatus.${v}`, v),
    agentRoleLabel: (v: string) => label(`enums.agentRole.${v}`, v),
    agentTierLabel: (v: string | null | undefined) => label(`enums.agentTier.${v}`, v),
    agentStateLabel: (v: string) => label(`strigoi.state.${v}`, v),
  }
}
