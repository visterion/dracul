import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useChronicleStore } from './chronicle'
import type { ChronicleData } from '../api/types'

const emptyChronicle: ChronicleData = { prey: [], verdicts: [], alerts: [], pendingPatterns: [] }
const mockGetChronicle = vi.fn(async (_includeArchived?: boolean) => emptyChronicle)

vi.mock('../api', () => ({
  useApi: () => ({ getChronicle: mockGetChronicle }),
}))

beforeEach(() => {
  setActivePinia(createPinia())
  mockGetChronicle.mockClear()
})

describe('useChronicleStore load / archive toggle', () => {
  it('defaults to active-only: load() with no argument fetches includeArchived=false', async () => {
    const store = useChronicleStore()
    await store.load()
    expect(mockGetChronicle).toHaveBeenCalledWith(false)
    expect(store.includeArchived).toBe(false)
  })

  it('load(true) fetches archived prey and flips includeArchived', async () => {
    const store = useChronicleStore()
    await store.load(true)
    expect(mockGetChronicle).toHaveBeenCalledWith(true)
    expect(store.includeArchived).toBe(true)
  })
})
