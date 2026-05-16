import type { ChronicleData, SystemStatus, VerdictDetail, StrigoiDetail } from './types'

export interface ApiClient {
  getChronicle(): Promise<ChronicleData>
  getSystemStatus(): Promise<SystemStatus>
  getVerdictDetail(id: string): Promise<VerdictDetail | null>
  getStrigoiDetail(name: string): Promise<StrigoiDetail | null>
}
