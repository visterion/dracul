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
