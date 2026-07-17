import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useApi } from '../api'
import type { Prey, Verdict, DaywalkerAlert, Pattern } from '../api/types'

export const useChronicleStore = defineStore('chronicle', () => {
  const prey = ref<Prey[]>([])
  const verdicts = ref<Verdict[]>([])
  const alerts = ref<DaywalkerAlert[]>([])
  const pendingPatterns = ref<Pattern[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  // Default off: active prey only. The chronicle view's archive toggle flips
  // this and re-loads; kept on the store (not the view) so the "N Beute"
  // tally and the fetched set can never drift out of sync with each other.
  const includeArchived = ref(false)

  const hasContent = computed(
    () => prey.value.length > 0 || verdicts.value.length > 0,
  )

  async function load(archived: boolean = includeArchived.value) {
    includeArchived.value = archived
    loading.value = true
    error.value = null
    try {
      const data = await useApi().getChronicle(archived)
      prey.value = data.prey
      verdicts.value = data.verdicts
      alerts.value = data.alerts
      pendingPatterns.value = data.pendingPatterns
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load chronicle'
    } finally {
      loading.value = false
    }
  }

  return { prey, verdicts, alerts, pendingPatterns, loading, error, includeArchived, hasContent, load }
})
