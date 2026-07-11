import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/** Pure part: which fade edges are visible for a horizontal scroller. */
export function computeEdgeFades(scrollLeft: number, clientWidth: number, scrollWidth: number): { left: boolean; right: boolean } {
  const max = scrollWidth - clientWidth
  if (max <= 1) return { left: false, right: false }
  return { left: scrollLeft > 1, right: scrollLeft < max - 1 }
}

/** Tracks fade-edge visibility of a horizontal scroll container. */
export function useEdgeFades(el: Ref<HTMLElement | null>) {
  const left = ref(false)
  const right = ref(false)

  function update() {
    const e = el.value
    if (!e) return
    const fades = computeEdgeFades(e.scrollLeft, e.clientWidth, e.scrollWidth)
    left.value = fades.left
    right.value = fades.right
  }

  onMounted(() => {
    update()
    el.value?.addEventListener('scroll', update, { passive: true })
    window.addEventListener('resize', update)
  })
  onBeforeUnmount(() => {
    el.value?.removeEventListener('scroll', update)
    window.removeEventListener('resize', update)
  })

  return { left, right, update }
}
