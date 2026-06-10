type TFn = (key: string, named?: Record<string, unknown>) => string

/**
 * Humanize a cron's recurrence and append the next fire's LOCAL time.
 * Extracted verbatim from StrigoiDetailView so the agent-config list can reuse it.
 * Recurrence comes from the cron's day-of-week field (last whitespace token →
 * works for 5- and 6-field crons); time comes from nextRunAt (an absolute instant)
 * formatted in the browser locale/zone.
 */
export function humanScheduleText(
  cron: string | null | undefined,
  nextRunAt: string | null | undefined,
  locale: string,
  t: TFn,
): string {
  const c = cron?.trim()
  if (!c) return '—'
  const dow = c.split(/\s+/).pop() ?? '*'
  let rec: string
  if (dow === '1-5') rec = t('strigoi.schedule.weekdays')
  else if (dow === '*' || dow === '?') rec = t('strigoi.schedule.daily')
  else return c
  if (!nextRunAt) return rec
  const d = new Date(nextRunAt)
  if (isNaN(d.getTime())) return rec
  const time = d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit', hour12: false })
  return t('strigoi.schedule.everyAt', { rec, time })
}
