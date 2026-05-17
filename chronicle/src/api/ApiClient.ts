import type {
  ChronicleData, SystemStatus, VerdictDetail, StrigoiDetail,
  WatchlistItem, Pattern, LlmProvider, VistierieData,
  BudgetStatus, BudgetPatch, SettingsBudgetData, PatternAction,
} from './types'

export interface ApiClient {
  getChronicle(): Promise<ChronicleData>
  getSystemStatus(): Promise<SystemStatus>
  getVerdictDetail(id: string): Promise<VerdictDetail | null>
  getStrigoiDetail(name: string): Promise<StrigoiDetail | null>
  getWatchlistItems(): Promise<WatchlistItem[]>
  getPatterns(): Promise<Pattern[]>
  getProviders(): Promise<LlmProvider[]>
  getVistierieData(): Promise<VistierieData>
  patchPattern(id: string, action: PatternAction): Promise<void>
  getSettingsBudgets(): Promise<SettingsBudgetData>
  patchSettingsBudget(patch: BudgetPatch): Promise<BudgetStatus>
  patchAgentBudget(agentName: string, patch: BudgetPatch): Promise<BudgetStatus>
}
