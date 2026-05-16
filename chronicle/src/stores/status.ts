import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useApi } from '../api'
import type { SystemStatus } from '../api/types'

export const useStatusStore = defineStore('status', () => {
  const status = ref<SystemStatus | null>(null)

  const huntingCount = computed(
    () => status.value?.strigoi.filter(s => s.state === 'hunting').length ?? 0,
  )

  const summaryLine = computed(() => {
    if (!status.value) return '☾ loading…'
    const { strigoi, daywalkerActive, dailyCostUsd } = status.value
    const cost = dailyCostUsd.toFixed(2)
    const daywalker = daywalkerActive ? 'daywalker active' : 'daywalker resting'
    return `☾ ${strigoi.length} strigoi · ${huntingCount.value} hunting · ${daywalker} · $${cost} today`
  })

  async function load() {
    try {
      status.value = await useApi().getSystemStatus()
    } catch {
      // status bar degrades gracefully — never block the UI
    }
  }

  return { status, huntingCount, summaryLine, load }
})
