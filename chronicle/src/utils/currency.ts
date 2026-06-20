export function formatMoney(amount: number | null, currency: string, locale: string): string {
  if (amount == null) return '—'
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency, maximumFractionDigits: 2 }).format(amount)
  } catch {
    return `${amount.toLocaleString(locale, { maximumFractionDigits: 2 })} ${currency}`
  }
}
