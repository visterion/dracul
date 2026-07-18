import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useInstrumentOverlayStore = defineStore('instrumentOverlay', () => {
  const openSymbol = ref<string | null>(null)
  function open(symbol: string) { openSymbol.value = symbol }
  function close() { openSymbol.value = null }
  return { openSymbol, open, close }
})
