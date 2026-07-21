import { watch, onUnmounted, type Ref } from 'vue'

// Module-level ref count so nested modals share one lock on the real
// scroll container (main.app-main — see App.vue). Toggling overflow on a
// div neither moves nor loses scrollTop, so useScrollMemory is unaffected.
let count = 0
let savedOverflow: string | null = null

function el(): HTMLElement | null {
  return document.querySelector<HTMLElement>('main.app-main')
}
function acquire() {
  if (count === 0) {
    const m = el()
    if (m) { savedOverflow = m.style.overflow; m.style.overflow = 'hidden' }
  }
  count++
}
function release() {
  if (count === 0) return
  count--
  if (count === 0) {
    const m = el()
    if (m) m.style.overflow = savedOverflow ?? ''
    savedOverflow = null
  }
}

/** Lock main.app-main scrolling while `active` is true. Ref-counted and
 *  idempotent per instance; auto-releases on unmount if still held. */
export function useScrollLock(active: Ref<boolean>) {
  let held = false
  const sync = (on: boolean) => {
    if (on && !held) { acquire(); held = true }
    else if (!on && held) { release(); held = false }
  }
  watch(active, sync, { immediate: true })
  onUnmounted(() => sync(false))
}
