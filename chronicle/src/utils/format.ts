import { formatMoney as formatMoneyIntl } from './currency'

const LOCALE = 'de-DE'

/** de-DE money with symbol, 2 fraction digits. Reuses utils/currency.ts. */
export function formatMoney(value: number, currency = 'USD'): string {
  return formatMoneyIntl(value, currency, LOCALE)
}

/** "434,80 $ (398,76 €)" — original currency first, display currency second. */
export function formatMoneyPair(
  value: number,
  currency: string,
  converted?: { value: number; currency: string },
): string {
  const primary = formatMoney(value, currency)
  if (!converted || converted.currency === currency) return primary
  return `${primary} (${formatMoney(converted.value, converted.currency)})`
}

export function formatNumber(value: number, digits = 0): string {
  return new Intl.NumberFormat(LOCALE, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(value)
}

/** value in percent points (4.36 -> "+4,4 %"). */
export function formatPercent(value: number): string {
  return new Intl.NumberFormat(LOCALE, {
    style: 'percent',
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
    signDisplay: 'exceptZero',
  }).format(value / 100)
}

/** Normalizes horizon codes: '3m'/'90d'/'1m'/'90 Tage' -> "3 Monate"/"90 Tage"/"1 Monat". */
export function formatHorizon(raw: string): string {
  const s = raw.trim().toLowerCase()
  const months = /^(\d+)\s*(m|mo|monat|monate|month|months)$/.exec(s)
  if (months) {
    const count = Number(months[1])
    return count === 1 ? '1 Monat' : `${count} Monate`
  }
  const days = /^(\d+)\s*(d|t|tag|tage|day|days)$/.exec(s)
  if (days) {
    const count = Number(days[1])
    return count === 1 ? '1 Tag' : `${count} Tage`
  }
  return raw
}

/** Dot-decimal string for form inputs (parseFloat-safe) — NOT for display. */
export function microsToUsdInput(micros: number | null, digits = 2): string {
  if (micros === null) return '∞'
  return (micros / 1_000_000).toFixed(digits)
}
