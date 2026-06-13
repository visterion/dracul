import type { ApiClient } from './ApiClient'
import type {
  ChronicleData, SystemStatus, VerdictDetail, StrigoiDetail,
  WatchlistItem, Pattern, LlmProvider, VistierieData,
  BudgetStatus, BudgetPatch, SettingsBudgetData, PatternAction,
  VerdictDecision, VerdictNote, DecisionResponse, CreateWatchlistRequest, PatchWatchlistRequest,
  PatchPositionRequest, LanguageSetting, AgentConfigRow, DataSourceHealth, Me, PatternCase,
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

const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

export class MockApiClient implements ApiClient {
  private notes: VerdictNote[] = [...mockVerdictNotes]
  private decisions = new Map<string, DecisionResponse>()
  private watchlist: WatchlistItem[] = initialWatchlist.map(i => ({ ...i }))
  private _language = 'de'
  async getChronicle(): Promise<ChronicleData> {
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

  async getWatchlistItems(): Promise<WatchlistItem[]> {
    await delay(50)
    return [...this.watchlist]
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

  private agents: AgentConfigRow[] = [
    { name: 'strigoi-spin',    role: 'SPIN',    state: 'hunting',    paused: false, tier: 'Reasoning', schedule: '0 22 * * 1-5', nextRunAt: null, dailyUsedUsd: 0.03, dailyBudgetUsd: 1.0,  primaryProvider: 'anthropic' },
    { name: 'strigoi-insider', role: 'INSIDER', state: 'resting',    paused: false, tier: 'Reasoning', schedule: '0 21 * * 1-5', nextRunAt: null, dailyUsedUsd: 0.0,  dailyBudgetUsd: 1.0,  primaryProvider: 'anthropic' },
    { name: 'strigoi-echo',    role: 'PEAD',    state: 'resting',    paused: false, tier: 'Reasoning', schedule: '0 20 * * 2,4', nextRunAt: null, dailyUsedUsd: 0.01, dailyBudgetUsd: 0.75, primaryProvider: 'anthropic' },
    { name: 'strigoi-lazarus', role: 'QUALITY_52W_LOW', state: 'paused', paused: true, tier: 'Reasoning', schedule: '0 6 * * 1-5', nextRunAt: null, dailyUsedUsd: 0.0, dailyBudgetUsd: 0.5, primaryProvider: 'anthropic' },
    { name: 'strigoi-index',   role: 'INDEX',   state: 'resting',    paused: false, tier: 'Reasoning', schedule: '0 7 * * 1-5', nextRunAt: null, dailyUsedUsd: 0.0,  dailyBudgetUsd: 0.5,  primaryProvider: 'anthropic' },
    { name: 'strigoi-merger',  role: 'MERGER_ARB', state: 'budget-hit', paused: false, tier: 'Reasoning', schedule: '0 5 * * 1-5', nextRunAt: null, dailyUsedUsd: 0.0, dailyBudgetUsd: 0.5, primaryProvider: 'anthropic' },
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

  async getMe(): Promise<Me> {
    return { email: 'you@dracul.local' }
  }

  async getDataSources(_refresh = false): Promise<DataSourceHealth[]> {
    const now = new Date().toISOString()
    return [
      { id: 'edgar', label: 'SEC EDGAR', configured: true, status: 'ok', httpStatus: 200, detail: null, latencyMs: 142, usedBy: ['strigoi-spin', 'strigoi-insider', 'strigoi-merger', 'daywalker'], rateLimitNote: '10 req/s', checkedAt: now },
      { id: 'yahoo', label: 'Yahoo Finance', configured: true, status: 'rate_limited', httpStatus: 429, detail: 'Too Many Requests', latencyMs: 88, usedBy: ['strigoi-echo', 'daywalker'], rateLimitNote: 'unofficial / scraped', checkedAt: now },
      { id: 'finnhub', label: 'Finnhub', configured: true, status: 'ok', httpStatus: 200, detail: null, latencyMs: 210, usedBy: ['strigoi-lazarus', 'daywalker'], rateLimitNote: 'provider-dependent (free tier)', checkedAt: now },
      { id: 'wikipedia', label: 'Wikipedia', configured: true, status: 'ok', httpStatus: 200, detail: null, latencyMs: 175, usedBy: ['strigoi-index'], rateLimitNote: 'MediaWiki UA policy', checkedAt: now },
    ]
  }
}
