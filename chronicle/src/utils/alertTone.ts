export type AlertTone = 'critical' | 'warning' | 'info' | 'neutral'

/**
 * Visual tone for an alert. The precise severity (INFO/WARNING/CRITICAL) wins;
 * legacy rows that only carry the coarse level degrade gracefully
 * (elevated -> warning, info -> info, else neutral).
 */
export function alertTone(severity: string | null | undefined, level: string): AlertTone {
  switch ((severity ?? '').toUpperCase()) {
    case 'CRITICAL': return 'critical'
    case 'WARNING':
    case 'WARN': return 'warning'
    case 'INFO': return 'info'
  }
  if (level === 'elevated') return 'warning'
  if (level === 'info') return 'info'
  return 'neutral'
}
