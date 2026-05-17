import type { ApiClient } from './ApiClient'
import type {
  ChronicleData, SystemStatus, VerdictDetail, StrigoiDetail,
  WatchlistItem, Pattern, LlmProvider, VistierieData,
  BudgetStatus, BudgetPatch, SettingsBudgetData, PatternAction,
} from './types'

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
    const res = await fetch(`${this.baseUrl}/api/watchlist`)
    if (!res.ok) throw new Error(`getWatchlistItems failed: HTTP ${res.status}`)
    return res.json() as Promise<WatchlistItem[]>
  }

  async getPatterns(): Promise<Pattern[]> {
    const res = await fetch(`${this.baseUrl}/api/patterns`)
    if (!res.ok) throw new Error(`getPatterns failed: HTTP ${res.status}`)
    return res.json() as Promise<Pattern[]>
  }

  async getProviders(): Promise<LlmProvider[]> {
    const res = await fetch(`${this.baseUrl}/api/providers`)
    if (!res.ok) throw new Error(`getProviders failed: HTTP ${res.status}`)
    return res.json() as Promise<LlmProvider[]>
  }

  async getVistierieData(): Promise<VistierieData> {
    const res = await fetch(`${this.baseUrl}/api/vistierie`)
    if (!res.ok) throw new Error(`getVistierieData failed: HTTP ${res.status}`)
    return res.json() as Promise<VistierieData>
  }

  async patchPattern(id: string, action: PatternAction): Promise<void> {
    const res = await fetch(`${this.baseUrl}/api/patterns/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action }),
    })
    if (!res.ok) throw new Error(`patchPattern failed: HTTP ${res.status}`)
  }

  async getSettingsBudgets(): Promise<SettingsBudgetData> {
    const res = await fetch(`${this.baseUrl}/api/settings/budgets`)
    if (!res.ok) throw new Error(`getSettingsBudgets failed: HTTP ${res.status}`)
    return res.json() as Promise<SettingsBudgetData>
  }

  async patchSettingsBudget(patch: BudgetPatch): Promise<BudgetStatus> {
    const res = await fetch(`${this.baseUrl}/api/settings/budgets`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    })
    if (!res.ok) throw new Error(`patchSettingsBudget failed: HTTP ${res.status}`)
    return res.json() as Promise<BudgetStatus>
  }

  async patchAgentBudget(agentName: string, patch: BudgetPatch): Promise<BudgetStatus> {
    const res = await fetch(
      `${this.baseUrl}/api/settings/budgets/agents/${encodeURIComponent(agentName)}`,
      {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(patch),
      }
    )
    if (!res.ok) throw new Error(`patchAgentBudget failed: HTTP ${res.status}`)
    return res.json() as Promise<BudgetStatus>
  }
}
