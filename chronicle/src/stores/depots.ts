import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useApi } from '../api'
import type { Depot, DepotPositionView } from '../api/types'

export const useDepotsStore = defineStore('depots', () => {
  const api = useApi()
  const depots = ref<Depot[]>([])

  // Always ?refresh=false (server-cached, TTL ~60s). Called fire-and-forget on
  // every overlay open — a failed load leaves `depots` empty (banner absent) and
  // is retried on the next call (no "loaded" flag that would freeze the error).
  async function load(): Promise<void> {
    try {
      const res = await api.getDepots(false)
      depots.value = res.depots
    } catch {
      depots.value = []
    }
  }

  function findHolding(symbol: string): { connection: string; position: DepotPositionView } | null {
    const hits: { connection: string; position: DepotPositionView; environment: string }[] = []
    for (const d of depots.value) {
      for (const p of d.positions) {
        if (p.symbol === symbol) hits.push({ connection: d.id, position: p, environment: d.environment })
      }
    }
    if (hits.length === 0) return null
    const live = hits.find(h => h.environment === 'live')
    const chosen = live ?? hits[0]
    return { connection: chosen.connection, position: chosen.position }
  }

  return { load, findHolding }
})
