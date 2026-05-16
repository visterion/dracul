import type { ApiClient } from './ApiClient'
import type { ChronicleData, SystemStatus, VerdictDetail, StrigoiDetail, WatchlistItem, Pattern, LlmProvider } from './types'

export class HttpApiClient implements ApiClient {
  constructor(private readonly baseUrl: string) {}

  async getChronicle(): Promise<ChronicleData> {
    const res = await fetch(`${this.baseUrl}/api/chronicle`)
    if (!res.ok) throw new Error(`getChronicle failed: HTTP ${res.status}`)
    return res.json() as Promise<ChronicleData>
  }

  async getSystemStatus(): Promise<SystemStatus> {
    const res = await fetch(`${this.baseUrl}/api/status`)
    if (!res.ok) throw new Error(`getSystemStatus failed: HTTP ${res.status}`)
    return res.json() as Promise<SystemStatus>
  }

  async getVerdictDetail(id: string): Promise<VerdictDetail | null> {
    const res = await fetch(`${this.baseUrl}/api/verdict/${encodeURIComponent(id)}`)
    if (res.status === 404) return null
    if (!res.ok) throw new Error(`getVerdictDetail failed: HTTP ${res.status}`)
    return res.json() as Promise<VerdictDetail>
  }

  async getStrigoiDetail(name: string): Promise<StrigoiDetail | null> {
    const res = await fetch(`${this.baseUrl}/api/strigoi/${encodeURIComponent(name)}`)
    if (res.status === 404) return null
    if (!res.ok) throw new Error(`getStrigoiDetail failed: HTTP ${res.status}`)
    return res.json() as Promise<StrigoiDetail>
  }

  async getWatchlistItems(): Promise<WatchlistItem[]> {
    throw new Error('Not implemented')
  }

  async getPatterns(): Promise<Pattern[]> {
    throw new Error('Not implemented')
  }

  async getProviders(): Promise<LlmProvider[]> {
    throw new Error('Not implemented')
  }
}
