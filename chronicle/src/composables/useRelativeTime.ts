import { useI18n } from 'vue-i18n'

/**
 * Localized relative-time + clock formatting.
 *
 * MUST be called from component setup scope (it calls useI18n()).
 * Returns strings localized via the `time.*` message keys, so callers
 * never embed English/German wording themselves.
 */
export function useRelativeTime() {
  const { t } = useI18n()

  function relativeTime(isoString: string): string {
    const diff = Date.now() - new Date(isoString).getTime()
    const minutes = Math.floor(diff / 60_000)
    const hours = Math.floor(diff / 3_600_000)
    const days = Math.floor(diff / 86_400_000)
    const weeks = Math.floor(days / 7)
    const months = Math.floor(days / 30)

    if (minutes < 1) return t('time.justNow')
    if (minutes < 60) return t('time.minutesAgo', { n: minutes })
    if (hours < 24) return t('time.hoursAgo', { n: hours })
    if (days === 1) return t('time.yesterday')
    if (days < 7) return t('time.daysAgo', { n: days })
    if (weeks < 5) return t('time.weeksAgo', { n: weeks })
    return t('time.monthsAgo', { n: months })
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
