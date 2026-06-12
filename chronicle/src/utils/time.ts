// chronicle/src/utils/time.ts
// Format an ISO-8601 instant as a localized relative string ("vor 2 Std", "gestern"),
// falling back to a short absolute date for anything older than a week, and to the
// raw input when it is not a parseable date (e.g. mock labels like "14:23 today").
export function formatRelativeTime(value: string, locale: string, now: number = Date.now()): string {
  const then = new Date(value).getTime()
  if (Number.isNaN(then)) return value
  const diffSec = Math.round((then - now) / 1000) // negative = past
  const abs = Math.abs(diffSec)
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' })
  if (abs < 60) return rtf.format(diffSec, 'second')
  if (abs < 3600) return rtf.format(Math.round(diffSec / 60), 'minute')
  if (abs < 86400) return rtf.format(Math.round(diffSec / 3600), 'hour')
  if (abs < 604800) return rtf.format(Math.round(diffSec / 86400), 'day')
  return new Date(then).toLocaleDateString(locale, { day: 'numeric', month: 'short', year: 'numeric' })
}
