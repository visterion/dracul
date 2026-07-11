import { ref } from 'vue'

export type DisplayMode = 'abs' | 'pct'

const STORAGE_KEY = 'dracul.depots.displayMode'

function loadInitial(): DisplayMode {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'pct' ? 'pct' : 'abs'
  } catch {
    return 'abs'
  }
}

// Module-level singleton (same pattern as api/index.ts's useApi()): every
// caller shares one reactive mode, so clicking any P&L/day-change number
// anywhere in the depots view flips the whole view at once.
const mode = ref<DisplayMode>(loadInitial())

/** Shared abs/% display toggle for the Depots view, persisted to localStorage. */
export function useDisplayMode() {
  function toggle() {
    mode.value = mode.value === 'abs' ? 'pct' : 'abs'
    try {
      localStorage.setItem(STORAGE_KEY, mode.value)
    } catch {
      // private browsing / storage disabled — mode still toggles in-memory
    }
  }
  return { mode, toggle }
}
