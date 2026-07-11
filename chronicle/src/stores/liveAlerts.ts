import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { LiveAlert } from '../api/types'
import { useChronicleStore } from './chronicle'

const MAX_ALERTS = 50

/** Wire shape of an `alert.new` SSE payload (snake_case from the backend). */
interface AlertNewPayload {
  symbol: string
  trigger_type: string
  severity: string
  thesis: string
  ts: string
}

/** Wire shape of a `verdict.kill_criteria_breached` SSE payload (snake_case from the backend). */
interface VerdictKillCriteriaBreachedPayload {
  verdict_id: string
  symbol: string
  breached: string[]
  ts: string
}

export const useLiveAlertsStore = defineStore('liveAlerts', () => {
  const alerts = ref<LiveAlert[]>([])
  const unread = ref(0)
  const status = ref<'connecting' | 'open' | 'closed'>('closed')
  let source: EventSource | null = null

  const isLive = computed(() => status.value === 'open')

  function connect() {
    // No live stream in mock mode (no backend); panel shows the closed state.
    if (import.meta.env.VITE_MOCK === 'true') return
    if (source) return
    status.value = 'connecting'
    const base = import.meta.env.VITE_API_BASE ?? ''
    source = new EventSource(`${base}/api/events`)
    source.onopen = () => { status.value = 'open' }
    source.onerror = () => { status.value = 'closed' } // EventSource auto-reconnects
    source.addEventListener('alert.new', (e: MessageEvent) => {
      try {
        const p = JSON.parse(e.data) as AlertNewPayload
        alerts.value.unshift({
          symbol: p.symbol,
          triggerType: p.trigger_type,
          severity: p.severity,
          thesis: p.thesis,
          ts: p.ts,
        })
        if (alerts.value.length > MAX_ALERTS) alerts.value.length = MAX_ALERTS
        unread.value += 1
      } catch {
        // ignore malformed payloads
      }
    })
    source.addEventListener('verdict.kill_criteria_breached', (e: MessageEvent) => {
      try {
        const p = JSON.parse(e.data) as VerdictKillCriteriaBreachedPayload
        alerts.value.unshift({
          symbol: p.symbol,
          triggerType: 'KILL_CRITERIA_BREACHED',
          severity: 'CRITICAL',
          thesis: p.breached.map((c) => `KILL: ${c}`).join(' · '),
          ts: p.ts,
        })
        if (alerts.value.length > MAX_ALERTS) alerts.value.length = MAX_ALERTS
        unread.value += 1
        // Refresh the chronicle overview so the breached verdict's summary reflects the
        // new state; VerdictDetailView re-fetches its own detail on next visit/reload.
        void useChronicleStore().load()
      } catch {
        // ignore malformed payloads
      }
    })
  }

  function markRead() {
    unread.value = 0
  }

  function disconnect() {
    source?.close()
    source = null
    status.value = 'closed'
  }

  return { alerts, unread, status, isLive, connect, markRead, disconnect }
})
