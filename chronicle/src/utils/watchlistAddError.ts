import { ApiError } from '../api/errors'

/**
 * Maps a `createWatchlistItem` failure to a readable message. Shared by
 * `InstrumentOverlay` and `WatchlistView`'s add dialog — both call
 * `api.createWatchlistItem` and both used to fall through to the raw
 * `Error.message` for anything the 404/422 branch didn't cover, which is how
 * a German user ended up reading `createWatchlistItem failed: HTTP 502`.
 *
 * 404/422 -> not-found (the backend answers 422 for an unknown symbol, kept
 * alongside 404 for safety). 400 -> validation. 5xx -> a translated
 * "temporarily unavailable" message — this is where an Agora outage surfaces.
 * Anything else falls back to the raw message as a genuine last resort.
 */
export function mapWatchlistAddError(
  e: unknown,
  symbol: string,
  t: (key: string, params?: Record<string, unknown>) => string,
): string {
  if (e instanceof ApiError) {
    if (e.status === 404 || e.status === 422) return t('watchlist.dialog.notFound', { symbol })
    if (e.status === 400) return t('watchlist.dialog.invalid')
    if (e.status >= 500) return t('watchlist.dialog.unavailable')
  }
  return e instanceof Error ? e.message : String(e)
}
