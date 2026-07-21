import type {
  ChronicleData, SystemStatus, VerdictDetail, StrigoiDetail,
  WatchlistItem, Pattern, LlmProvider, VistierieData,
  BudgetStatus, BudgetPatch, SettingsBudgetData, PatternAction,
  VerdictDecision, VerdictNote, DecisionResponse,
  CreateWatchlistRequest, PatchWatchlistRequest, PatchPositionRequest, LanguageSetting, CurrencySetting,
  AgentConfigRow, DataSourceHealth, Me, PatternCase,
  AgentDefinition, ToolCatalogView, AgentDefinitionEdit, ExitSignal, MorningReport,
  ExecutorCalibration, ExecutorBehavior,
  DepotsResponse, DepotChart, ChartRange, InstrumentInfo, DepotPositionView, DepotOrderView,
  DepotHistory, RunTranscript, InspectorRunsResponse, DepotMove,
} from './types'

export interface ApiClient {
  getChronicle(includeArchived?: boolean): Promise<ChronicleData>
  getSystemStatus(): Promise<SystemStatus>
  getVerdictDetail(id: string): Promise<VerdictDetail | null>
  getStrigoiDetail(name: string): Promise<StrigoiDetail | null>
  triggerStrigoiRun(name: string): Promise<{ runId: string }>
  getWatchlistItems(): Promise<WatchlistItem[]>
  getPatterns(): Promise<Pattern[]>
  getPatternCases(id: string): Promise<PatternCase[]>
  getProviders(): Promise<LlmProvider[]>
  getVistierieData(): Promise<VistierieData>
  patchPattern(id: string, action: PatternAction): Promise<void>
  updatePatternGate(id: string, gate: unknown | null): Promise<void>
  getSettingsBudgets(): Promise<SettingsBudgetData>
  patchSettingsBudget(patch: BudgetPatch): Promise<BudgetStatus>
  patchAgentBudget(agentName: string, patch: BudgetPatch): Promise<BudgetStatus>
  putVerdictDecision(id: string, decision: VerdictDecision | null): Promise<DecisionResponse>
  getVerdictNotes(id: string): Promise<VerdictNote[]>
  addVerdictNote(id: string, body: string): Promise<VerdictNote>
  createWatchlistItem(req: CreateWatchlistRequest): Promise<WatchlistItem>
  patchWatchlistItem(id: string, req: PatchWatchlistRequest): Promise<WatchlistItem>
  patchWatchlistPosition(id: string, req: PatchPositionRequest): Promise<WatchlistItem>
  deleteWatchlistItem(id: string): Promise<void>
  getLanguage(): Promise<LanguageSetting>
  setLanguage(language: string): Promise<LanguageSetting>
  getDisplayCurrency(): Promise<CurrencySetting>
  setDisplayCurrency(currency: string): Promise<CurrencySetting>
  getAgents(): Promise<AgentConfigRow[]>
  setAgentPaused(name: string, paused: boolean): Promise<AgentConfigRow>
  getAgentDefinition(name: string): Promise<AgentDefinition>
  getToolCatalog(): Promise<ToolCatalogView[]>
  putAgentDefinition(name: string, edit: AgentDefinitionEdit): Promise<AgentDefinition>
  resetAgentDefinition(name: string): Promise<AgentDefinition>
  getDataSources(refresh?: boolean): Promise<DataSourceHealth[]>
  getMe(): Promise<Me>
  getExitSignals(): Promise<ExitSignal[]>
  getMorningReport(): Promise<MorningReport>
  getExecutorCalibration(): Promise<ExecutorCalibration>
  getExecutorBehavior(): Promise<ExecutorBehavior>
  getDepots(refresh?: boolean): Promise<DepotsResponse>
  getDepotChart(connection: string, range: ChartRange): Promise<DepotChart>
  getInstrumentChart(symbol: string, range: ChartRange): Promise<DepotChart>
  getInstrumentInfo(symbol: string): Promise<InstrumentInfo>
  getDepotPosition(
    connection: string,
    symbol: string,
  ): Promise<{ position: DepotPositionView; orders: DepotOrderView[]; asOf: string | null; runId: string | null; moves: DepotMove[] }>
  getDepotHistory(connection: string): Promise<DepotHistory>
  getRunTranscript(runId: string): Promise<RunTranscript>
  getInspectorTranscript(runId: string): Promise<RunTranscript>
  getInspectorRuns(agent: string | null, limit?: number, offset?: number): Promise<InspectorRunsResponse>
  getDecisionDoc(): Promise<{ markdown: string } | null>
}
