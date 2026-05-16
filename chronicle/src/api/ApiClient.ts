import type { ChronicleData, SystemStatus } from './types'

export interface ApiClient {
  getChronicle(): Promise<ChronicleData>
  getSystemStatus(): Promise<SystemStatus>
}
