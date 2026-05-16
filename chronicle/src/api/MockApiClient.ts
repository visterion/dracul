import type { ApiClient } from './ApiClient'
import type { ChronicleData, SystemStatus } from './types'
import { mockPrey } from '../mocks/prey'
import { mockVerdicts } from '../mocks/verdicts'
import { mockAlerts } from '../mocks/alerts'
import { mockPatterns } from '../mocks/patterns'
import { mockSystemStatus } from '../mocks/status'

const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

export class MockApiClient implements ApiClient {
  async getChronicle(): Promise<ChronicleData> {
    await delay(50)
    return {
      prey: mockPrey,
      verdicts: mockVerdicts,
      alerts: mockAlerts,
      pendingPatterns: mockPatterns,
    }
  }

  async getSystemStatus(): Promise<SystemStatus> {
    await delay(50)
    return mockSystemStatus
  }
}
