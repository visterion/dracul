import type { ChronicleData, SystemStatus, VerdictDetail, StrigoiDetail, WatchlistItem, Pattern, LlmProvider } from './types'

export interface ApiClient {
  getChronicle(): Promise<ChronicleData>
  getSystemStatus(): Promise<SystemStatus>
  getVerdictDetail(id: string): Promise<VerdictDetail | null>
  getStrigoiDetail(name: string): Promise<StrigoiDetail | null>
  getWatchlistItems(): Promise<WatchlistItem[]>
  getPatterns(): Promise<Pattern[]>
  getProviders(): Promise<LlmProvider[]>
}
