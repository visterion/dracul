import type { ApiClient } from './ApiClient'
import type {
  ChronicleData, SystemStatus, VerdictDetail, StrigoiDetail,
  WatchlistItem, Pattern, LlmProvider, VistierieData,
  BudgetStatus, BudgetPatch, SettingsBudgetData, PatternAction,
  VerdictDecision, VerdictNote, DecisionResponse, CreateWatchlistRequest, PatchWatchlistRequest,
  PatchPositionRequest, LanguageSetting, CurrencySetting, AgentConfigRow, DataSourceHealth, Me, PatternCase,
  AgentDefinition, ToolCatalogView, AgentDefinitionEdit, ExitSignal, MorningReport,
  ExecutorCalibration, ExecutorBehavior,
  DepotsResponse, DepotChart, ChartRange, InstrumentInfo, DepotPositionView, DepotOrderView,
} from './types'
import { mockPrey } from '../mocks/prey'
import { mockVerdicts } from '../mocks/verdicts'
import { mockAlerts } from '../mocks/alerts'
import { mockPatterns } from '../mocks/patterns'
import { mockPatternCases } from '../mocks/patternCases'
import { mockSystemStatus } from '../mocks/status'
import { mockVerdictDetails } from '../mocks/verdictDetails'
import { mockStrigoiDetails } from '../mocks/strigoiDetails'
import { mockWatchlistItems as initialWatchlist } from '../mocks/watchlistItems'
import { mockVerdictNotes } from '../mocks/verdictNotes'
import { mockProviders } from '../mocks/providers'
import { mockExitSignals } from '../mocks/exitSignals'
import { mockMorningReport } from '../mocks/morningReport'
import { mockExecutorCalibration, mockExecutorBehavior } from '../mocks/executorCalibration'
import {
  mockDepots, mockDepotsResponse, mockDepotChart, mockInstrumentChart, mockInstrumentInfo,
} from '../mocks/depots'

const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

export class MockApiClient implements ApiClient {
  private notes: VerdictNote[] = [...mockVerdictNotes]
  private decisions = new Map<string, DecisionResponse>()
  private watchlist: WatchlistItem[] = initialWatchlist.map(i => ({ ...i }))
  private _language = 'de'
  private _currency = 'EUR'
  async getChronicle(_includeArchived = false): Promise<ChronicleData> {
    await delay(50)
    return {
      prey: mockPrey,
      verdicts: mockVerdicts,
      alerts: mockAlerts,
      pendingPatterns: mockPatterns.filter(p => p.status === 'PENDING'),
    }
  }

  async getSystemStatus(): Promise<SystemStatus> {
    await delay(50)
    return mockSystemStatus
  }

  async getVerdictDetail(id: string): Promise<VerdictDetail | null> {
    await delay(50)
    return mockVerdictDetails.find(v => v.id === id) ?? null
  }

  async getStrigoiDetail(name: string): Promise<StrigoiDetail | null> {
    await delay(50)
    return mockStrigoiDetails.find(s => s.name === name) ?? null
  }

  async triggerStrigoiRun(name: string): Promise<{ runId: string }> {
    await delay(150)
    const agent = this.agents.find(a => a.name === name)
    if (!agent) throw new Error(`unknown strigoi: ${name}`)
    if (agent.paused) throw new Error('agent is paused')
    return { runId: `run-mock-${name}` }
  }

  async getWatchlistItems(): Promise<WatchlistItem[]> {
    await delay(50)
    return this.watchlist.map(i => ({ ...i, currency: this._currency }))
  }

  async getPatterns(): Promise<Pattern[]> {
    await delay(50)
    return mockPatterns
  }

  async getPatternCases(id: string): Promise<PatternCase[]> {
    await delay(50)
    return mockPatternCases(id)
  }

  async getProviders(): Promise<LlmProvider[]> {
    await delay(50)
    return mockProviders
  }

  async getVistierieData(): Promise<VistierieData> {
    await delay(50)
    const today = new Date()
    const dailySpend30d = Array.from({ length: 30 }, (_, i) => {
      const d = new Date(today)
      d.setDate(d.getDate() - (29 - i))
      const base = 0.35 + 0.20 * Math.sin(i * 0.4)
      const noise = 0.08 * Math.sin(i * 1.7 + 0.5)
      return {
        date: d.toISOString().slice(0, 10),
        totalUsd: Math.round((base + noise) * 100) / 100,
      }
    })
    return {
      tiers: [
        { name: 'Reasoning', models: 'Sonnet, Opus', budgetUsd: 2.00, usedUsd: 1.60 },
        { name: 'Routine',   models: 'Haiku',         budgetUsd: 1.00, usedUsd: 0.45 },
        { name: 'Local',     models: 'Ollama',         budgetUsd: 0.00, usedUsd: 0.00 },
      ],
      spendingByAgent: [
        { agent: 'strigoi-spin',    totalUsd: 0.80, pct: 50 },
        { agent: 'strigoi-insider', totalUsd: 0.40, pct: 25 },
        { agent: 'voievod',         totalUsd: 0.24, pct: 15 },
        { agent: 'daywalker',       totalUsd: 0.16, pct: 10 },
      ],
      dailySpend30d,
      monthlyTotalUsd: 1.60,
      monthlyBudgetUsd: 5.00,
    }
  }

  async patchPattern(_id: string, _action: PatternAction): Promise<void> {
    await delay(200)
  }

  async getSettingsBudgets(): Promise<SettingsBudgetData> {
    await delay(50)
    const mockBudget = (daily: number, monthly: number): BudgetStatus => ({
      dailyCapMicros: daily,
      monthlyCapMicros: monthly,
      dailyWarnPercent: 80,
      monthlyWarnPercent: 80,
      dailyUsageMicros: Math.round(daily * 0.086),
      monthlyUsageMicros: Math.round(monthly * 0.083),
      dailyWarned: false,
      monthlyWarned: false,
      dailyBlocked: false,
      monthlyBlocked: false,
    })
    return {
      tenant: mockBudget(5_000_000, 150_000_000),
      agents: [
        { name: 'strigoi-spin',    budget: mockBudget(1_000_000,  25_000_000) },
        { name: 'strigoi-insider', budget: mockBudget(1_000_000,  20_000_000) },
        { name: 'strigoi-echo',    budget: mockBudget(750_000,    15_000_000) },
        { name: 'strigoi-lazarus', budget: mockBudget(500_000,    10_000_000) },
        { name: 'strigoi-index',   budget: mockBudget(500_000,    10_000_000) },
        { name: 'strigoi-merger',  budget: mockBudget(500_000,    10_000_000) },
      ],
    }
  }

  async patchSettingsBudget(_patch: BudgetPatch): Promise<BudgetStatus> {
    await delay(200)
    return (await this.getSettingsBudgets()).tenant
  }

  async patchAgentBudget(agentName: string, _patch: BudgetPatch): Promise<BudgetStatus> {
    await delay(200)
    const data = await this.getSettingsBudgets()
    return data.agents.find(a => a.name === agentName)?.budget ?? data.agents[0].budget
  }

  async putVerdictDecision(id: string, decision: VerdictDecision | null): Promise<DecisionResponse> {
    await delay(50)
    const resp: DecisionResponse = { id, decision, decidedAt: new Date().toISOString() }
    if (decision === null) this.decisions.delete(id)
    else this.decisions.set(id, resp)
    return resp
  }

  async getVerdictNotes(id: string): Promise<VerdictNote[]> {
    await delay(50)
    return this.notes
      .filter(n => n.verdictId === id)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  }

  async addVerdictNote(id: string, body: string): Promise<VerdictNote> {
    await delay(50)
    const note: VerdictNote = {
      id: crypto.randomUUID(),
      verdictId: id,
      body: body.trim(),
      createdAt: new Date().toISOString(),
    }
    this.notes.push(note)
    return note
  }

  async createWatchlistItem(req: CreateWatchlistRequest): Promise<WatchlistItem> {
    await delay(50)
    const existing = this.watchlist.find(i => i.ticker === req.symbol)
    if (existing) {
      if (req.sourceVerdictId && !existing.verdictId) {
        existing.verdictId = req.sourceVerdictId
      }
      return { ...existing }
    }
    const item: WatchlistItem = {
      id: crypto.randomUUID(),
      ticker: req.symbol,
      companyName: req.symbol,
      currentPrice: 0,
      dayChangePercent: 0,
      status: 'calm',
      addedAt: new Date().toISOString().slice(0, 10),
      tag: req.tag,
      verdictId: req.sourceVerdictId ?? null,
      alerts: [],
      priceHistory30d: Array.from({ length: 30 }, () => 0),
      entryPrice: null,
      shareCount: null,
      owner: 'you@dracul.local',
      currency: this._currency,
      entryCurrency: this._currency,
      nativeCurrentPrice: 0,
      nativeCurrency: this._currency,
      nativeEntryPrice: null,
      source: req.sourceVerdictId ? 'verdict' : 'manual',
    }
    this.watchlist.unshift(item)
    return { ...item }
  }

  async patchWatchlistItem(id: string, req: PatchWatchlistRequest): Promise<WatchlistItem> {
    await delay(50)
    const item = this.watchlist.find(i => i.id === id)
    if (!item) throw new Error(`watchlist item ${id} not found`)
    item.tag = req.tag
    return { ...item }
  }

  async patchWatchlistPosition(id: string, req: PatchPositionRequest): Promise<WatchlistItem> {
    await delay(50)
    const item = this.watchlist.find(i => i.id === id)
    if (!item) throw new Error(`watchlist item ${id} not found`)
    item.entryPrice = req.entryPrice
    item.shareCount = req.shareCount
    return { ...item }
  }

  async deleteWatchlistItem(id: string): Promise<void> {
    await delay(50)
    const idx = this.watchlist.findIndex(i => i.id === id)
    if (idx === -1) throw new Error(`watchlist item ${id} not found`)
    this.watchlist.splice(idx, 1)
  }

  async getLanguage(): Promise<LanguageSetting> {
    return { language: this._language }
  }

  async setLanguage(language: string): Promise<LanguageSetting> {
    this._language = language === 'en' ? 'en' : 'de'
    return { language: this._language }
  }

  async getDisplayCurrency(): Promise<CurrencySetting> {
    return { currency: this._currency }
  }

  async setDisplayCurrency(currency: string): Promise<CurrencySetting> {
    const valid = ['EUR', 'USD', 'GBP', 'CHF']
    if (valid.includes(currency)) this._currency = currency
    return { currency: this._currency }
  }

  private agents: AgentConfigRow[] = [
    { name: 'strigoi-spin',    role: 'SPIN',    state: 'hunting',    paused: false, tier: 'Reasoning', schedule: '0 22 * * 1-5', nextRunAt: null, dailyUsedUsd: 0.03, dailyBudgetUsd: 1.0,  primaryProvider: 'anthropic', budgetMissing: false },
    { name: 'strigoi-insider', role: 'INSIDER', state: 'resting',    paused: false, tier: 'Reasoning', schedule: '0 21 * * 1-5', nextRunAt: null, dailyUsedUsd: 0.0,  dailyBudgetUsd: 1.0,  primaryProvider: 'anthropic', budgetMissing: false },
    { name: 'strigoi-echo',    role: 'PEAD',    state: 'resting',    paused: false, tier: 'Reasoning', schedule: '0 20 * * 2,4', nextRunAt: null, dailyUsedUsd: 0.01, dailyBudgetUsd: 0.75, primaryProvider: 'anthropic', budgetMissing: false },
    { name: 'strigoi-lazarus', role: 'QUALITY_52W_LOW', state: 'paused', paused: true, tier: 'Reasoning', schedule: '0 6 * * 1-5', nextRunAt: null, dailyUsedUsd: 0.0, dailyBudgetUsd: 0.5, primaryProvider: 'anthropic', budgetMissing: false },
    { name: 'strigoi-index',   role: 'INDEX',   state: 'resting',    paused: false, tier: 'Reasoning', schedule: '0 7 * * 1-5', nextRunAt: null, dailyUsedUsd: 0.0,  dailyBudgetUsd: 0.5,  primaryProvider: 'anthropic', budgetMissing: false },
    { name: 'strigoi-merger',  role: 'MERGER_ARB', state: 'budget-hit', paused: false, tier: 'Reasoning', schedule: '0 5 * * 1-5', nextRunAt: null, dailyUsedUsd: 0.0, dailyBudgetUsd: 0.5, primaryProvider: 'anthropic', budgetMissing: false },
  ]

  async getAgents(): Promise<AgentConfigRow[]> {
    return this.agents.map(a => ({ ...a }))
  }

  async setAgentPaused(name: string, paused: boolean): Promise<AgentConfigRow> {
    const a = this.agents.find(x => x.name === name)
    if (!a) throw new Error(`unknown agent: ${name}`)
    a.paused = paused
    a.state = paused ? 'paused' : (a.state === 'paused' ? 'resting' : a.state)
    return { ...a }
  }

  private agentDefs = new Map<string, AgentDefinition>()

  private toolCatalog: ToolCatalogView[] = [
    { toolName: 'fetch_recent_spinoff_candidates', defaultDescription: 'Recent SEC spin-off filings' },
    { toolName: 'fetch_recent_clusters', defaultDescription: 'Recent insider buy clusters' },
    { toolName: 'fetch_recent_pead_candidates', defaultDescription: 'Recent post-earnings drift candidates' },
    { toolName: 'fetch_quality_at_low_candidates', defaultDescription: 'Quality names at 52-week lows' },
    { toolName: 'fetch_recent_index_additions', defaultDescription: 'Recent index-inclusion additions' },
    { toolName: 'fetch_recent_merger_candidates', defaultDescription: 'Recent merger-arbitrage filings' },
  ]

  private mockToolFor(name: string): string {
    const m: Record<string, string> = {
      'strigoi-spin': 'fetch_recent_spinoff_candidates',
      'strigoi-insider': 'fetch_recent_clusters',
      'strigoi-echo': 'fetch_recent_pead_candidates',
      'strigoi-lazarus': 'fetch_quality_at_low_candidates',
      'strigoi-index': 'fetch_recent_index_additions',
      'strigoi-merger': 'fetch_recent_merger_candidates',
    }
    return m[name] ?? 'fetch_recent_spinoff_candidates'
  }

  private defaultDef(name: string): AgentDefinition {
    return {
      name, modelPurpose: 'reasoning',
      promptText: `You are ${name}. Hunt your pattern and report candidates.`,
      outputSchema: {}, schedule: '0 0 22 * * 1-5', maxTurns: 25, maxRunSeconds: 1800,
      completionPath: `/api/${name}/complete`, eventSourcePath: null,
      sessionDurationSeconds: null, pollIntervalSeconds: null, enabled: true,
      tools: [{ toolName: this.mockToolFor(name), description: null }],
    }
  }

  async getAgentDefinition(name: string): Promise<AgentDefinition> {
    return { ...(this.agentDefs.get(name) ?? this.defaultDef(name)) }
  }

  async getToolCatalog(): Promise<ToolCatalogView[]> {
    return this.toolCatalog.map(t => ({ ...t }))
  }

  async putAgentDefinition(name: string, edit: AgentDefinitionEdit): Promise<AgentDefinition> {
    const def: AgentDefinition = {
      ...(this.agentDefs.get(name) ?? this.defaultDef(name)),
      promptText: edit.prompt, modelPurpose: edit.modelPurpose, schedule: edit.schedule,
      maxTurns: edit.maxTurns, maxRunSeconds: edit.maxRunSeconds, enabled: edit.enabled,
      tools: edit.tools.map(t => ({ toolName: t.toolName, description: t.description })),
    }
    this.agentDefs.set(name, def)
    return { ...def }
  }

  async resetAgentDefinition(name: string): Promise<AgentDefinition> {
    const def = this.defaultDef(name)
    this.agentDefs.set(name, def)
    return { ...def }
  }

  async getMe(): Promise<Me> {
    return { email: 'you@dracul.local' }
  }

  async getExitSignals(): Promise<ExitSignal[]> {
    await delay(50)
    const mineIds = new Set(
      this.watchlist.filter(i => i.owner === 'you@dracul.local').map(i => i.id),
    )
    const mineTickers = new Set(
      this.watchlist.filter(i => i.owner === 'you@dracul.local').map(i => i.ticker),
    )
    return mockExitSignals
      .filter(s => (s.watchlistItemId != null && mineIds.has(s.watchlistItemId)) || mineTickers.has(s.symbol))
      .map(s => ({ ...s }))
  }

  async getMorningReport(): Promise<MorningReport> {
    await delay(50)
    return structuredClone(mockMorningReport)
  }

  async getExecutorCalibration(): Promise<ExecutorCalibration> {
    await delay(50)
    return structuredClone(mockExecutorCalibration)
  }

  async getExecutorBehavior(): Promise<ExecutorBehavior> {
    await delay(50)
    return structuredClone(mockExecutorBehavior)
  }

  async getDataSources(_refresh = false): Promise<DataSourceHealth[]> {
    const now = new Date().toISOString()
    // After the 7a–7d Agora migration Dracul's only market-data dependency is Agora,
    // so health is a single "agora" row mirroring AgoraDataSourceHealthService.
    return [
      { id: 'agora', label: 'Agora', configured: true, status: 'ok', httpStatus: null, detail: null, latencyMs: 34, usedBy: ['quotes', 'ohlc', 'filings', 'fundamentals', 'earnings', 'news', 'index', 'intraday', 'fx'], rateLimitNote: 'in-cluster MCP', checkedAt: now },
    ]
  }

  async getDepots(_refresh = false): Promise<DepotsResponse> {
    await delay(50)
    return structuredClone(mockDepotsResponse)
  }

  async getDepotChart(connection: string, _range: ChartRange): Promise<DepotChart> {
    await delay(50)
    const depot = mockDepots.find(d => d.id === connection)
    if (!depot) throw new Error(`getDepotChart: connection not found: ${connection}`)
    return structuredClone(mockDepotChart)
  }

  async getInstrumentChart(_symbol: string, _range: ChartRange): Promise<DepotChart> {
    await delay(50)
    return structuredClone(mockInstrumentChart)
  }

  async getInstrumentInfo(symbol: string): Promise<InstrumentInfo> {
    await delay(50)
    return { ...structuredClone(mockInstrumentInfo), symbol }
  }

  async getDepotPosition(
    connection: string,
    symbol: string,
  ): Promise<{ position: DepotPositionView; orders: DepotOrderView[]; asOf: string | null }> {
    await delay(50)
    const depot = mockDepots.find(d => d.id === connection)
    if (!depot) throw new Error(`getDepotPosition: connection not found: ${connection}`)
    const position = depot.positions.find(p => p.symbol === symbol)
    if (!position) throw new Error(`getDepotPosition: not found: ${connection}/${symbol}`)
    return {
      position: { ...position },
      orders: depot.orders.filter(o => o.symbol === symbol).map(o => ({ ...o })),
      asOf: depot.asOf,
    }
  }
}
