import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useToast } from './useToast'

describe('useToast', () => {
  beforeEach(() => { vi.useFakeTimers() })
  // Module-level singleton: drain all pending dismiss timers so tests stay isolated.
  afterEach(() => { vi.runAllTimers(); vi.useRealTimers() })

  it('show() appends a success toast by default', () => {
    const { show, toasts } = useToast()
    show('AVGO hinzugefügt')
    expect(toasts.value).toHaveLength(1)
    expect(toasts.value[0]).toMatchObject({ message: 'AVGO hinzugefügt', type: 'success' })
  })

  it('show() honours the error variant', () => {
    const { show, toasts } = useToast()
    show('Symbol nicht gefunden', { type: 'error' })
    expect(toasts.value.at(-1)).toMatchObject({ type: 'error' })
  })

  it('auto-dismisses a toast after 4s', () => {
    const { show, toasts } = useToast()
    show('kurzlebig')
    expect(toasts.value.some(t => t.message === 'kurzlebig')).toBe(true)
    vi.advanceTimersByTime(4000)
    expect(toasts.value.some(t => t.message === 'kurzlebig')).toBe(false)
  })

  it('stacks toasts and dismisses each on its own clock', () => {
    const { show, toasts } = useToast()
    show('erster')
    vi.advanceTimersByTime(2000)
    show('zweiter')
    expect(toasts.value).toHaveLength(2)
    vi.advanceTimersByTime(2000) // first hits its 4s
    expect(toasts.value.map(t => t.message)).toEqual(['zweiter'])
  })

  it('dismiss() removes a toast immediately', () => {
    const { show, dismiss, toasts } = useToast()
    show('weg damit')
    dismiss(toasts.value[0].id)
    expect(toasts.value).toHaveLength(0)
  })
})
