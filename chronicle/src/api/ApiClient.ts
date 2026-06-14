import type {
  ChronicleData, SystemStatus, VerdictDetail, StrigoiDetail,
  WatchlistItem, Pattern, LlmProvider, VistierieData,
  BudgetStatus, BudgetPatch, SettingsBudgetData, PatternAction,
  VerdictDecision, VerdictNote, DecisionResponse,
  CreateWatchlistRequest, PatchWatchlistRequest, PatchPositionRequest, LanguageSetting,
  AgentConfigRow, DataSourceHealth, Me, PatternCase,
  AgentDefinition, ToolCatalogView, AgentDefinitionEdit,
} from './types'

export interface ApiClient {
  getChronicle(): Promise<ChronicleData>
  getSystemStatus(): Promise<SystemStatus>
  getVerdictDetail(id: string): Promise<VerdictDetail | null>
  getStrigoiDetail(name: string): Promise<StrigoiDetail | null>
  getWatchlistItems(): Promise<WatchlistItem[]>
  getPatterns(): Promise<Pattern[]>
  getPatternCases(id: string): Promise<PatternCase[]>
  getProviders(): Promise<LlmProvider[]>
  getVistierieData(): Promise<VistierieData>
  patchPattern(id: string, action: PatternAction): Promise<void>
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
  getAgents(): Promise<AgentConfigRow[]>
  setAgentPaused(name: string, paused: boolean): Promise<AgentConfigRow>
  getAgentDefinition(name: string): Promise<AgentDefinition>
  getToolCatalog(): Promise<ToolCatalogView[]>
  putAgentDefinition(name: string, edit: AgentDefinitionEdit): Promise<AgentDefinition>
  resetAgentDefinition(name: string): Promise<AgentDefinition>
  getDataSources(refresh?: boolean): Promise<DataSourceHealth[]>
  getMe(): Promise<Me>
}
