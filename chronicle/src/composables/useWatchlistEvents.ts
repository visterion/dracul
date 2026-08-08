import { ref } from 'vue'
import type { Ref } from 'vue'
import type { WatchlistItem } from '../api/types'

// Module-level singleton, same shape as useToast: the one place that carries
// a "watchlist item was just added" signal between components that don't
// otherwise share state — specifically InstrumentOverlay (mounted once at
// the App shell, reachable from anywhere) and WatchlistView (owns the local
// `items` list, loaded once on mount). Without this, an add from the overlay
// mutates nothing WatchlistView can see.
const lastAdded: Ref<WatchlistItem | null> = ref(null)

export function useWatchlistEvents(): {
  lastAdded: Ref<WatchlistItem | null>
  notifyAdded(item: WatchlistItem): void
} {
  function notifyAdded(item: WatchlistItem): void {
    lastAdded.value = item
  }
  return { lastAdded, notifyAdded }
}
