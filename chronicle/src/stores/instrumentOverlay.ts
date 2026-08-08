import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useInstrumentOverlayStore = defineStore('instrumentOverlay', () => {
  const openSymbol = ref<string | null>(null)
  /** Name from the instrument search, when the overlay was opened from a search hit. */
  const openName = ref<string | null>(null)
  function open(symbol: string, name?: string) {
    openSymbol.value = symbol
    openName.value = name ?? null
  }
  function close() {
    openSymbol.value = null
    openName.value = null
  }
  return { openSymbol, openName, open, close }
})
