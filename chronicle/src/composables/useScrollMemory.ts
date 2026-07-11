import { onBeforeUnmount, onMounted } from 'vue'

/**
 * Remembers/restores the scroll position of the app scroll container
 * (main.app-main, see App.vue) per view key via sessionStorage.
 */
export function useScrollMemory(key: string) {
  const storageKey = `scroll:${key}`
  let el: HTMLElement | null = null

  function save() {
    if (el) sessionStorage.setItem(storageKey, String(el.scrollTop))
  }

  function restore() {
    const raw = sessionStorage.getItem(storageKey)
    if (el && raw !== null) el.scrollTop = Number(raw)
  }

  onMounted(() => {
    el = document.querySelector<HTMLElement>('main.app-main')
    el?.addEventListener('scroll', save, { passive: true })
  })
  onBeforeUnmount(() => {
    el?.removeEventListener('scroll', save)
  })

  return { restore, save }
}
