import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useApi } from '../api'
import type { SystemStatus } from '../api/types'
import { formatNumber } from '../utils/format'

export const useStatusStore = defineStore('status', () => {
  const status = ref<SystemStatus | null>(null)

  const huntingCount = computed(
    () => status.value?.strigoi.filter(s => s.state === 'hunting').length ?? 0,
  )

  const strigoiCount = computed(() => status.value?.strigoi.length ?? 0)

  const dailyCost = computed(() => status.value?.dailyCostUsd ?? 0)

  /** Compact relative age of the last verdict, e.g. "12m", "3h", "2d", or "—". */
  const lastVerdictRelative = computed(() => {
    const iso = status.value?.lastVerdictAt
    if (!iso) return '—'
    const then = new Date(iso).getTime()
    if (Number.isNaN(then)) return '—'
    const diffSec = Math.max(0, Math.floor((Date.now() - then) / 1000))
    if (diffSec < 60) return `${diffSec}s`
    const min = Math.floor(diffSec / 60)
    if (min < 60) return `${min}m`
    const hr = Math.floor(min / 60)
    if (hr < 24) return `${hr}h`
    return `${Math.floor(hr / 24)}d`
  })

  const summaryLine = computed(() => {
    if (!status.value) return '☾ loading…'
    const { strigoi, daywalkerActive, dailyCostUsd } = status.value
    const cost = formatNumber(dailyCostUsd, 2)
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

  return {
    status,
    huntingCount,
    strigoiCount,
    dailyCost,
    lastVerdictRelative,
    summaryLine,
    load,
  }
})
