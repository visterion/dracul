import type { ApiClient } from './ApiClient'
import type {
  ChronicleData, SystemStatus, VerdictDetail, StrigoiDetail,
  WatchlistItem, Pattern, LlmProvider, VistierieData,
  BudgetStatus, BudgetPatch, SettingsBudgetData, PatternAction,
  VerdictDecision, VerdictNote, DecisionResponse, CreateWatchlistRequest, PatchWatchlistRequest,
  PatchPositionRequest, LanguageSetting, CurrencySetting, AgentConfigRow, DataSourceHealth, Me, PatternCase,
  AgentDefinition, ToolCatalogView, AgentDefinitionEdit, ExitSignal,
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

  async getPatternCases(id: string): Promise<PatternCase[]> {
    const res = await fetch(`${this.baseUrl}/api/patterns/${encodeURIComponent(id)}/cases`)
    if (!res.ok) throw new Error(`getPatternCases failed: HTTP ${res.status}`)
    return res.json() as Promise<PatternCase[]>
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

  async putVerdictDecision(id: string, decision: VerdictDecision | null): Promise<DecisionResponse> {
    const res = await fetch(`${this.baseUrl}/api/verdict/${encodeURIComponent(id)}/decision`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ decision }),
    })
    if (!res.ok) throw new Error(`putVerdictDecision failed: HTTP ${res.status}`)
    return res.json() as Promise<DecisionResponse>
  }

  async getVerdictNotes(id: string): Promise<VerdictNote[]> {
    const res = await fetch(`${this.baseUrl}/api/verdict/${encodeURIComponent(id)}/notes`)
    if (!res.ok) throw new Error(`getVerdictNotes failed: HTTP ${res.status}`)
    const payload = (await res.json()) as { notes: VerdictNote[] }
    return payload.notes
  }

  async addVerdictNote(id: string, body: string): Promise<VerdictNote> {
    const res = await fetch(`${this.baseUrl}/api/verdict/${encodeURIComponent(id)}/notes`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body }),
    })
    if (!res.ok) throw new Error(`addVerdictNote failed: HTTP ${res.status}`)
    return res.json() as Promise<VerdictNote>
  }

  async createWatchlistItem(req: CreateWatchlistRequest): Promise<WatchlistItem> {
    const res = await fetch(`${this.baseUrl}/api/watchlist`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    })
    if (!res.ok) throw new Error(`createWatchlistItem failed: HTTP ${res.status}`)
    return res.json() as Promise<WatchlistItem>
  }

  async patchWatchlistItem(id: string, req: PatchWatchlistRequest): Promise<WatchlistItem> {
    const res = await fetch(`${this.baseUrl}/api/watchlist/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    })
    if (!res.ok) throw new Error(`patchWatchlistItem failed: HTTP ${res.status}`)
    return res.json() as Promise<WatchlistItem>
  }

  async patchWatchlistPosition(id: string, req: PatchPositionRequest): Promise<WatchlistItem> {
    const res = await fetch(`${this.baseUrl}/api/watchlist/${encodeURIComponent(id)}/position`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    })
    if (!res.ok) throw new Error(`patchWatchlistPosition failed: HTTP ${res.status}`)
    return res.json() as Promise<WatchlistItem>
  }

  async deleteWatchlistItem(id: string): Promise<void> {
    const res = await fetch(`${this.baseUrl}/api/watchlist/${encodeURIComponent(id)}`, {
      method: 'DELETE',
    })
    if (!res.ok) throw new Error(`deleteWatchlistItem failed: HTTP ${res.status}`)
  }

  async getLanguage(): Promise<LanguageSetting> {
    const res = await fetch(`${this.baseUrl}/api/settings/language`)
    if (!res.ok) throw new Error(`getLanguage failed: HTTP ${res.status}`)
    return res.json() as Promise<LanguageSetting>
  }

  async setLanguage(language: string): Promise<LanguageSetting> {
    const res = await fetch(`${this.baseUrl}/api/settings/language`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ language }),
    })
    if (!res.ok) throw new Error(`setLanguage failed: HTTP ${res.status}`)
    return res.json() as Promise<LanguageSetting>
  }

  async getDisplayCurrency(): Promise<CurrencySetting> {
    const res = await fetch(`${this.baseUrl}/api/settings/currency`)
    if (!res.ok) throw new Error(`getDisplayCurrency failed: HTTP ${res.status}`)
    return res.json() as Promise<CurrencySetting>
  }

  async setDisplayCurrency(currency: string): Promise<CurrencySetting> {
    const res = await fetch(`${this.baseUrl}/api/settings/currency`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ currency }),
    })
    if (!res.ok) throw new Error(`setDisplayCurrency failed: HTTP ${res.status}`)
    return res.json() as Promise<CurrencySetting>
  }

  async getAgents(): Promise<AgentConfigRow[]> {
    const res = await fetch(`${this.baseUrl}/api/settings/agents`)
    if (!res.ok) throw new Error(`getAgents failed: ${res.status}`)
    return res.json() as Promise<AgentConfigRow[]>
  }

  async setAgentPaused(name: string, paused: boolean): Promise<AgentConfigRow> {
    const res = await fetch(
      `${this.baseUrl}/api/settings/agents/${encodeURIComponent(name)}`,
      {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ paused }),
      },
    )
    if (!res.ok) throw new Error(`setAgentPaused failed: ${res.status}`)
    return res.json() as Promise<AgentConfigRow>
  }

  async getAgentDefinition(name: string): Promise<AgentDefinition> {
    const res = await fetch(`${this.baseUrl}/api/settings/agents/${encodeURIComponent(name)}/definition`)
    if (!res.ok) throw new Error(`getAgentDefinition failed: ${res.status}`)
    return res.json() as Promise<AgentDefinition>
  }

  async getToolCatalog(): Promise<ToolCatalogView[]> {
    const res = await fetch(`${this.baseUrl}/api/settings/agents/tools`)
    if (!res.ok) throw new Error(`getToolCatalog failed: ${res.status}`)
    return res.json() as Promise<ToolCatalogView[]>
  }

  async putAgentDefinition(name: string, edit: AgentDefinitionEdit): Promise<AgentDefinition> {
    const res = await fetch(
      `${this.baseUrl}/api/settings/agents/${encodeURIComponent(name)}/definition`,
      { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(edit) },
    )
    if (!res.ok) {
      let msg = `putAgentDefinition failed: ${res.status}`
      try { const b = await res.json(); if (b?.error) msg = b.error as string } catch { /* ignore */ }
      throw new Error(msg)
    }
    return res.json() as Promise<AgentDefinition>
  }

  async resetAgentDefinition(name: string): Promise<AgentDefinition> {
    const res = await fetch(
      `${this.baseUrl}/api/settings/agents/${encodeURIComponent(name)}/definition/reset`,
      { method: 'POST' },
    )
    if (!res.ok) throw new Error(`resetAgentDefinition failed: ${res.status}`)
    return res.json() as Promise<AgentDefinition>
  }

  async getDataSources(refresh = false): Promise<DataSourceHealth[]> {
    const res = await fetch(`${this.baseUrl}/api/settings/data-sources?refresh=${refresh}`)
    if (!res.ok) throw new Error(`getDataSources failed: ${res.status}`)
    return res.json() as Promise<DataSourceHealth[]>
  }

  async getMe(): Promise<Me> {
    const res = await fetch(`${this.baseUrl}/api/me`)
    if (!res.ok) throw new Error(`getMe failed: HTTP ${res.status}`)
    return res.json() as Promise<Me>
  }

  async getExitSignals(): Promise<ExitSignal[]> {
    const res = await fetch(`${this.baseUrl}/api/exit-signals`)
    if (!res.ok) throw new Error(`getExitSignals failed: HTTP ${res.status}`)
    return res.json() as Promise<ExitSignal[]>
  }
}
