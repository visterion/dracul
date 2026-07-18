export type AnomalyType = 'SPIN' | 'INSIDER' | 'PEAD' | 'LAZARUS' | 'INDEX' | 'MERGER'
export type Severity = 'INFO' | 'WARNING' | 'CRITICAL'
export type PatternStatus = 'PENDING' | 'ACTIVE' | 'REJECTED'
export type TimeHorizon = '30d' | '60d' | '90d' | '180d'
export type StrigoiState = 'hunting' | 'resting' | 'paused' | 'budget-hit'

export interface Prey {
  id: string
  symbol: string
  companyName: string
  anomalyType: AnomalyType
  /** 0–1 — drives left border color and confidence bar */
  confidence: number
  thesis: string
  signals: string[]
  risks: string[]
  /** Falsifiable exit conditions — what would kill the thesis */
  killCriteria: string[]
  horizon: TimeHorizon
  /** e.g. "strigoi-echo" */
  discoveredBy: string
  /** ISO 8601 */
  discoveredAt: string
}

export interface Verdict {
  id: string
  symbol: string
  companyName: string
  contributingStrigoi: string[]
  /** 0–1 — weighted consensus across contributing Strigoi */
  consensusScore: number
  summary: string
  createdAt: string
}

export interface DaywalkerAlert {
  id: string
  symbol: string
  description: string
  severity: Severity
  triggeredAt: string
}

export interface Pattern {
  id: string
  appliesToStrigoi: string
  statement: string
  status: PatternStatus
  evidenceCount: number
  proposedAt: string
  supportedCount?: number          // how many of evidenceCount cases supported the lesson
  avgUpliftPercent?: number | null // null for exclusion/negative lessons
  name?: string                    // slug for active patterns, e.g. "tech-spinoffs-outperform-industrials"
  gate?: Record<string, unknown> | null // machine-checkable veto predicate; null/absent = advisory
  blockedCount?: number                 // distinct signals blocked by this gate (server-computed)
}

export interface PatternCase {
  symbol: string
  companyName: string
  anomalyType: string
  /** ISO 8601 */
  occurredAt: string
  /** whether this instance supported (true) or refuted (false) the lesson */
  supported: boolean
  /** realized return over the horizon; null if open/unknown */
  returnPercent: number | null
  note?: string | null
}

export interface StrigoiStatus {
  name: string
  state: StrigoiState
  lastRunAt?: string
  nextRunAt?: string
}

export interface SystemStatus {
  strigoi: StrigoiStatus[]
  lastVerdictAt?: string
  dailyCostUsd: number
  daywalkerActive: boolean
}

export interface ChronicleData {
  prey: Prey[]
  verdicts: Verdict[]
  alerts: DaywalkerAlert[]
  pendingPatterns: Pattern[]
}

export interface ContributingStrigoiDetail {
  name: string
  confidence: number
  thesis: string
}

export interface VerdictDetail extends Verdict {
  anomalyTypes: AnomalyType[]
  currentPrice: number
  avgConfidence: number
  horizon: TimeHorizon
  signals: string[]
  risks: string[]
  contributingDetails: ContributingStrigoiDetail[]
  currency: string                  // display currency code
  nativeCurrentPrice: number | null // original price before conversion
  nativeCurrency: string            // original currency of currentPrice
  killCriteriaBreached?: string[]   // kill criteria currently breached (VerdictKillCriteriaWatcher)
}

export type VerdictDecision = 'TRACK' | 'INTERESTING' | 'DISMISS' | 'ACTED'

export interface VerdictNote {
  id: string
  verdictId: string
  body: string
  createdAt: string
}

export interface DecisionResponse {
  id: string
  decision: VerdictDecision | null
  decidedAt: string
}

export interface TraceEvent {
  offset: string
  type: 'start' | 'end' | 'llm-call' | 'info'
  message: string
}

export interface RunEntry {
  id: string
  ranAt: string
  preyCount: number
  costUsd: number
  model: string
  trace: TraceEvent[]
}

export interface StrigoiConfiguration {
  cron: string
  nextRunAt: string
  disabled: boolean
  tier: string
  allowedModels: string[]
  dailyBudgetUsd: number
  dailyUsedUsd: number
  monthlyBudgetUsd: number
  monthlyUsedUsd: number
  primaryProvider: string
  fallbackProvider: string | null
}

export interface WeeklyPerformance {
  week: string
  hitRate: number
  preyCount: number
}

export interface StrigoiDetail {
  name: string
  anomalyType: AnomalyType
  description: string
  reference: string
  state: StrigoiState
  lastRunAt: string
  nextRunAt: string
  huntsThisMonth: number
  scheduledHuntsThisMonth: number
  avgPreyPerHunt: number
  hitRate90d: number
  hitRateNumerator: number
  hitRateDenominator: number
  recentRuns: RunEntry[]
  recentPrey: Prey[]
  configuration: StrigoiConfiguration
  weeklyPerformance: WeeklyPerformance[]
}

// ── Watchlist ──────────────────────────────────────────────────

export type WatchlistStatus = 'calm' | 'elevated' | 'alert'
export type WatchlistTag = 'HELD' | 'TRACKING'

export interface WatchlistAlert {
  id: string
  at: string
  message: string
  level: 'elevated' | 'info' | 'neutral'
  severity: string | null
}

export interface LiveAlert {
  symbol: string
  triggerType: string
  severity: string
  thesis: string
  ts: string
}

export interface WatchlistItem {
  id: string
  ticker: string
  companyName: string
  currentPrice: number
  dayChangePercent: number
  status: WatchlistStatus
  addedAt: string          // ISO date "2026-05-14"
  tag: WatchlistTag
  verdictId: string | null // links to VerdictDetail when tag === 'TRACKING'
  alerts: WatchlistAlert[]
  priceHistory30d: number[] // 30 data points for sparkline
  entryPrice: number | null
  shareCount: number | null
  owner: string
  currency: string                  // display currency code (converted value's code)
  entryCurrency: string             // native currency the entry price was entered in
  nativeCurrentPrice: number        // original current price, before conversion
  nativeCurrency: string            // original currency of currentPrice
  nativeEntryPrice: number | null   // original entry price, before conversion
  source: string                    // provenance: 'manual' | 'seed' | 'verdict' | `agent:<name>`
}

export interface ExitSignal {
  id: string
  watchlistItemId: string | null
  symbol: string
  action: 'SELL' | 'TRIM' | 'HOLD'
  firedRules: string[]
  gainLossPct: number | null
  thesisStatus: 'INTACT' | 'WEAKENING' | 'INVALIDATED' | 'NONE' | null
  rationale: string | null
  confidence: number | null
  vistierieRunId: string | null
  runAt: string
}

export interface OrderTicket {
  side: 'SELL' | 'TRIM' | 'HOLD'
  symbol: string
  shares: number
  limitReference: number | null
  stop: number | null
  target: number | null
}

export interface MorningReportLine {
  symbol: string
  companyName: string
  shareCount: number
  entryPrice: number
  currentClose: number | null
  activeStop: number | null
  nextTarget2r: number | null
  distanceToStopPct: number | null
  targetReached: boolean
  action: 'SELL' | 'TRIM' | 'HOLD'
  thesisStatus: 'INTACT' | 'WEAKENING' | 'INVALIDATED' | 'NONE' | null
  confidence: number | null
  rationale: string | null
  ticket: OrderTicket
}

export interface MorningReport {
  generatedAt: string
  sellCount: number
  trimCount: number
  holdCount: number
  positions: MorningReportLine[]
}

export interface Me { email: string }

export interface PatchPositionRequest {
  entryPrice: number | null
  shareCount: number | null
}

export interface CreateWatchlistRequest {
  symbol: string
  tag: WatchlistTag
  sourceVerdictId?: string | null
}

export interface PatchWatchlistRequest {
  tag: WatchlistTag
}

// ── Settings / Providers ───────────────────────────────────────

export type ProviderStatus = 'connected' | 'fallback' | 'local'

export interface LlmProvider {
  id: string
  name: string
  status: ProviderStatus
  apiKeyMasked: string | null  // "··· 4f3a" or null for local
  endpoint: string | null       // for Ollama only
  models: string[]
  todayInputTokens: number
  todayOutputTokens: number
  todayCostUsd: number
  callsToday: number | null     // for local providers (Ollama)
}

// ── Vistierie Dashboard ────────────────────────────────────────

export interface TierBudget {
  name: string
  models: string
  budgetUsd: number
  usedUsd: number
}

export interface AgentSpend {
  agent: string
  totalUsd: number
  pct: number
}

export interface DailySpend {
  date: string
  totalUsd: number
}

export interface VistierieData {
  tiers: TierBudget[]
  spendingByAgent: AgentSpend[]
  dailySpend30d: DailySpend[]
  monthlyTotalUsd: number
  monthlyBudgetUsd: number
}

// ── Budget & Settings ──────────────────────────────────────────

export interface BudgetStatus {
  dailyCapMicros: number | null
  monthlyCapMicros: number | null
  dailyWarnPercent: number | null
  monthlyWarnPercent: number | null
  dailyUsageMicros: number
  monthlyUsageMicros: number
  dailyWarned: boolean
  monthlyWarned: boolean
  dailyBlocked: boolean
  monthlyBlocked: boolean
}

export interface BudgetPatch {
  dailyCapMicros?: number | null
  monthlyCapMicros?: number | null
  dailyWarnPercent?: number | null
  monthlyWarnPercent?: number | null
}

export interface AgentBudget {
  name: string
  budget: BudgetStatus
}

export interface SettingsBudgetData {
  tenant: BudgetStatus
  agents: AgentBudget[]
}

export type PatternAction = 'approve' | 'reject' | 'deactivate' | 'defer'

export interface LanguageSetting {
  language: string
}

export interface CurrencySetting {
  currency: string
}

// ── Agent Config ───────────────────────────────────────────────

export interface AgentConfigRow {
  name: string
  role: string
  state: string
  paused: boolean
  tier: string | null
  schedule: string | null
  nextRunAt: string | null
  dailyUsedUsd: number
  dailyBudgetUsd: number
  primaryProvider: string | null
  budgetMissing: boolean
}

export interface ToolBinding {
  toolName: string
  description: string | null
}

export interface AgentDefinition {
  name: string
  modelPurpose: string
  promptText: string
  outputSchema: unknown
  schedule: string | null
  maxTurns: number
  maxRunSeconds: number
  completionPath: string
  eventSourcePath: string | null
  sessionDurationSeconds: number | null
  pollIntervalSeconds: number | null
  enabled: boolean
  tools: ToolBinding[]
}

export interface ToolCatalogView {
  toolName: string
  defaultDescription: string
}

export interface AgentDefinitionEdit {
  prompt: string
  schedule: string
  modelPurpose: string
  enabled: boolean
  maxTurns: number
  maxRunSeconds: number
  tools: { toolName: string; description: string | null }[]
}

// ── Executor calibration / behavior (operator analytics) ───────

export interface CalibrationBucket {
  range: string
  n: number
  predicted: number
  observed: number
}

/** Brier calibration for one unit (the executor overall, or one hunter).
 *  All numeric fields are primitives on the wire (never null) — an empty
 *  sample serializes as brier 0 with insufficient: true. */
export interface CalibrationUnit {
  brier: number
  n: number
  insufficient: boolean
  buckets: CalibrationBucket[]
}

export interface HunterCalibration extends CalibrationUnit {
  agent: string
}

export interface ExecutorCalibration {
  executor: CalibrationUnit
  hunters: HunterCalibration[]
}

export interface VetoPrecisionRow {
  reason_code: string
  n: number
  skipped: number
  mean_hypothetical_r_20d: number
  mean_hypothetical_r_60d: number
  stopped_out_pct: number
}

export interface HardExitLatency {
  n: number
  max_seconds: number
  p95_seconds: number
}

export interface WhipsawStats {
  reentry_within_10d: number
  roundtrip_under_5d: number
}

export interface StopBasisRow {
  basis: string
  n: number
  mean_realized_r: number
  mean_mae_r: number
}

export interface SlippageStats {
  n: number
  mean: number
  worst: number
}

export interface ExecutorBehavior {
  veto_precision: VetoPrecisionRow[]
  caveats: string[]
  hard_exit_latency: HardExitLatency
  whipsaw: WhipsawStats
  stop_basis: StopBasisRow[]
  slippage: SlippageStats
}

// ── Settings / Data Sources ────────────────────────────────────

export interface DataSourceHealth {
  id: string
  label: string
  configured: boolean
  status: string
  httpStatus: number | null
  detail: string | null
  latencyMs: number | null
  usedBy: string[]
  rateLimitNote: string
  checkedAt: string
}

// ── Depots ─────────────────────────────────────────────────────

export interface DepotPositionView {
  symbol: string
  qty: number
  avgEntryPrice: number
  marketValue: number
  unrealizedPl: number
  unrealizedPlPct: number | null
  price: number | null
  dayChangePercent: number | null
  weightPct: number | null
  currency: string
  name: string | null
  assetType: string | null
  valueDate: string | null
  nativePrice: number | null
  nativeCurrency: string | null
}

export interface DepotAggregates {
  investedValue: number
  totalUnrealizedPl: number
  totalUnrealizedPlPct: number | null
  dayChangeAbs: number | null
  dayChangePct: number | null
}

export interface DepotAccountView {
  cash: number
  equity: number
  buyingPower: number
  currency: string
  status: string
  asOf: string
}

export interface DepotOrderView {
  brokerOrderId: string
  symbol: string
  side: string | null
  qty: number
  type: string
  status: string
  role: string | null
}

export interface Depot {
  id: string
  provider: string
  environment: 'paper' | 'live'
  status: string
  probedAt: string | null
  error: string | null
  account: DepotAccountView | null
  aggregates: DepotAggregates | null
  positions: DepotPositionView[]
  orders: DepotOrderView[]
  asOf: string | null
}

export interface DepotsResponse {
  depots: Depot[]
  error: string | null
}

export interface ChartPoint {
  t: string
  value: number
}

/** Shared by both chart endpoints. The depot curve (`GET /api/depots/{connection}/chart`)
 *  populates `relative` and `partial`; the instrument chart (`GET /api/depots/chart`) is
 *  pure market data and only ever sends `points`. */
export interface DepotChart {
  points: ChartPoint[]
  relative?: { t: string; pct: number }[] | null
  partial?: boolean
}

export type ChartRange = '1d' | '1w' | '1m' | '1y' | 'max'

export interface InstrumentInfo {
  symbol: string
  profile: unknown | null
  news: unknown | null
  /** `{ earnings: [...] }` — rows already server-filtered to this symbol, each row carries its own `symbol` */
  earnings: unknown | null
  analystEstimates: unknown | null
  earningsEstimates: unknown | null
  fundamentalScore: unknown | null
  fundamentals: unknown | null
  /** `{ transactions: [...] }` — rows already server-filtered to this symbol, each row carries its own `ticker` */
  insiderActivity: unknown | null
}
