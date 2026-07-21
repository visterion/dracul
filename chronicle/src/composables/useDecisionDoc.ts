import { ref } from 'vue'
import { useApi } from '../api'

// Module-level singletons: the decision doc is fetched once and shared.
const markdown = ref<string | null>(null)
const loaded = ref(false)
let started = false

export function useDecisionDoc() {
  if (!started) {
    started = true
    ;(async () => {
      try {
        const r = await useApi().getDecisionDoc()
        markdown.value = r?.markdown ?? null
      } catch (e) {
        // Any failure -> null; never rethrow. The panel degrades gracefully.
        console.warn('decision-doc load failed', e)
        markdown.value = null
      } finally {
        loaded.value = true
      }
    })()
  }
  return { markdown, loaded }
}
