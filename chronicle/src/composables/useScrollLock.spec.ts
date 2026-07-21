import { describe, it, expect, beforeEach } from 'vitest'
import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import { useScrollLock } from './useScrollLock'

function stubMain(overflow = 'auto') {
  document.querySelector('main.app-main')?.remove()
  const m = document.createElement('main')
  m.className = 'app-main'; m.style.overflow = overflow
  document.body.appendChild(m); return m
}
const main = () => document.querySelector<HTMLElement>('main.app-main')!
const harness = (active = ref(false)) =>
  mount({ setup() { useScrollLock(active); return () => null } })

describe('useScrollLock', () => {
  beforeEach(() => stubMain('auto'))

  it('locks and restores main.app-main overflow', async () => {
    const a = ref(false); harness(a)
    expect(main().style.overflow).toBe('auto')
    a.value = true; await Promise.resolve()
    expect(main().style.overflow).toBe('hidden')
    a.value = false; await Promise.resolve()
    expect(main().style.overflow).toBe('auto')
  })

  it('ref-counts nested locks: releases only when the last releases', async () => {
    const a = ref(true), b = ref(true); harness(a); harness(b)
    expect(main().style.overflow).toBe('hidden')
    a.value = false; await Promise.resolve()
    expect(main().style.overflow).toBe('hidden')
    b.value = false; await Promise.resolve()
    expect(main().style.overflow).toBe('auto')
  })

  it('releases on unmount if still active (no leak)', async () => {
    const a = ref(true); const w = harness(a)
    expect(main().style.overflow).toBe('hidden')
    w.unmount(); await Promise.resolve()
    expect(main().style.overflow).toBe('auto')
  })
})
