import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises } from '@vue/test-utils'

const getDecisionDoc = vi.fn()

vi.mock('../api', () => ({
  useApi: () => ({ getDecisionDoc }),
}))

beforeEach(() => {
  vi.resetModules()
  getDecisionDoc.mockReset()
})

async function load() {
  const mod = await import('./useDecisionDoc')
  return mod.useDecisionDoc
}

describe('useDecisionDoc', () => {
  it('exposes the markdown when the api returns a doc', async () => {
    getDecisionDoc.mockResolvedValue({ markdown: '# ok' })
    const useDecisionDoc = await load()
    const { markdown, loaded } = useDecisionDoc()
    await flushPromises()
    expect(markdown.value).toBe('# ok')
    expect(loaded.value).toBe(true)
  })

  it('yields null markdown when the api returns null', async () => {
    getDecisionDoc.mockResolvedValue(null)
    const useDecisionDoc = await load()
    const { markdown, loaded } = useDecisionDoc()
    await flushPromises()
    expect(markdown.value).toBeNull()
    expect(loaded.value).toBe(true)
  })

  it('swallows errors: any throw -> null, never rethrown', async () => {
    getDecisionDoc.mockRejectedValue(new Error('boom'))
    const useDecisionDoc = await load()
    const { markdown, loaded } = useDecisionDoc()
    await flushPromises()
    expect(markdown.value).toBeNull()
    expect(loaded.value).toBe(true)
  })

  it('fetches only once across multiple uses (module-scoped cache)', async () => {
    getDecisionDoc.mockResolvedValue({ markdown: '# once' })
    const useDecisionDoc = await load()
    useDecisionDoc()
    useDecisionDoc()
    await flushPromises()
    expect(getDecisionDoc).toHaveBeenCalledTimes(1)
  })
})
