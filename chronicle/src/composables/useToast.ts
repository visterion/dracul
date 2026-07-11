import { ref } from 'vue'
import type { Ref } from 'vue'

export interface Toast {
  id: number
  message: string
  type: 'success' | 'error'
}

export const TOAST_DISMISS_MS = 4000

// Module-level singleton: every caller feeds the one AppToast outlet in the shell.
const toasts = ref<Toast[]>([])
let nextId = 1

export function useToast(): {
  toasts: Ref<Toast[]>
  show(message: string, opts?: { type?: 'success' | 'error' }): void
  dismiss(id: number): void
} {
  function dismiss(id: number): void {
    toasts.value = toasts.value.filter(t => t.id !== id)
  }
  function show(message: string, opts?: { type?: 'success' | 'error' }): void {
    const toast: Toast = { id: nextId++, message, type: opts?.type ?? 'success' }
    toasts.value = [...toasts.value, toast]
    setTimeout(() => dismiss(toast.id), TOAST_DISMISS_MS)
  }
  return { toasts, show, dismiss }
}
