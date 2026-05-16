import type { ApiClient } from './ApiClient'
import type { ChronicleData, SystemStatus } from './types'

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
}
