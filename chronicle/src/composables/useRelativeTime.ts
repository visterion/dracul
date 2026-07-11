import { useI18n } from 'vue-i18n'

/**
 * Localized relative-time + clock formatting.
 *
 * MUST be called from component setup scope (it calls useI18n()).
 * Returns strings localized via the `time.*` message keys, so callers
 * never embed English/German wording themselves.
 */
/**
 * Pure bucket logic for `relativeTime()`, extracted for unit testing.
 * Returns the i18n key (and interpolation count, if any) for a given
 * millisecond delta between "now" and a past instant.
 */
export function relativeTimeKey(diffMs: number): { key: string; n?: number } {
  const minutes = Math.floor(diffMs / 60_000)
  const hours = Math.floor(diffMs / 3_600_000)
  const days = Math.floor(diffMs / 86_400_000)
  const weeks = Math.floor(days / 7)
  const months = Math.floor(days / 30)
  if (minutes < 1) return { key: 'time.justNow' }
  if (minutes < 60) return { key: 'time.minutesAgo', n: minutes }
  if (hours < 24) return { key: 'time.hoursAgo', n: hours }
  if (days === 1) return { key: 'time.yesterday' }
  if (days < 7) return { key: 'time.daysAgo', n: days }
  if (weeks < 5) return { key: 'time.weeksAgo', n: weeks }
  if (months === 1) return { key: 'time.oneMonthAgo' } // singular fix (de.ts:16)
  return { key: 'time.monthsAgo', n: months }
}

export function useRelativeTime() {
  const { t } = useI18n()

  function relativeTime(isoString: string): string {
    const r = relativeTimeKey(Date.now() - new Date(isoString).getTime())
    return r.n === undefined ? t(r.key) : t(r.key, { n: r.n })
  }

  /**
   * Coarse "months ago" phrasing used by the pattern library
   * ("active since …" / "proposed …, X months ago").
   * Buckets: this month, 1 month ago, N months ago.
   */
  function monthsAgo(isoString: string): string {
    const months = Math.floor((Date.now() - new Date(isoString).getTime()) / (30 * 86_400_000))
    if (months === 0) return t('time.thisMonth')
    if (months === 1) return t('time.oneMonthAgo')
    return t('time.monthsAgo', { n: months })
  }

  /**
   * Coarse "days ago" phrasing for pending patterns
   * ("proposed today / yesterday / N days ago").
   */
  function daysAgo(isoString: string): string {
    const days = Math.floor((Date.now() - new Date(isoString).getTime()) / 86_400_000)
    if (days === 0) return t('time.today')
    if (days === 1) return t('time.yesterday')
    return t('time.daysAgo', { n: days })
  }

  function formatTime(isoString: string): string {
    const d = new Date(isoString)
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  }

  return { relativeTime, monthsAgo, daysAgo, formatTime }
}
