import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useApi } from '../api'

export const useDisplayCurrencyStore = defineStore('displayCurrency', () => {
  const currency = ref<string>('EUR')

  async function load() {
    try {
      currency.value = (await useApi().getDisplayCurrency()).currency
    } catch { /* keep default */ }
  }

  async function setCurrency(c: string) {
    const res = await useApi().setDisplayCurrency(c)
    currency.value = res.currency
  }

  return { currency, load, setCurrency }
})
