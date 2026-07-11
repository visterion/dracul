import { describe, it, expect, beforeEach } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { useScrollMemory } from './useScrollMemory'

function host() {
  let api!: ReturnType<typeof useScrollMemory>
  const Host = defineComponent({
    setup() { api = useScrollMemory('test'); return () => null },
  })
  const wrapper = mount(Host)
  return { wrapper, api: () => api }
}

describe('useScrollMemory', () => {
  beforeEach(() => {
    sessionStorage.clear()
    document.body.innerHTML = '<main class="app-main"></main>'
  })

  it('saves scrollTop of main.app-main on scroll', () => {
    host()
    const main = document.querySelector<HTMLElement>('main.app-main')!
    main.scrollTop = 120
    main.dispatchEvent(new Event('scroll'))
    expect(sessionStorage.getItem('scroll:test')).toBe('120')
  })

  it('restores a previously saved position', () => {
    sessionStorage.setItem('scroll:test', '340')
    const { api } = host()
    api().restore()
    expect(document.querySelector<HTMLElement>('main.app-main')!.scrollTop).toBe(340)
  })

  it('does nothing without a saved value', () => {
    const { api } = host()
    api().restore()
    expect(document.querySelector<HTMLElement>('main.app-main')!.scrollTop).toBe(0)
  })
})
