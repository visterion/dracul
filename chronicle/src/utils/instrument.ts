/**
 * Company name for display, or '' when it adds nothing beyond the symbol.
 * Agora's get_quote returns no company name, so the backend stores the ticker
 * as companyName (NOTE 7b in WatchlistController) — rendering both would show
 * "PYPL PYPL". Callers render only the symbol when this returns ''.
 */
export function displayName(symbol: string, name?: string | null): string {
  const trimmed = (name ?? '').trim()
  if (!trimmed) return ''
  if (trimmed.toUpperCase() === symbol.trim().toUpperCase()) return ''
  return trimmed
}
