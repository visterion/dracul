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
  lastVerdictAt: string
  dailyCostUsd: number
  daywalkerActive: boolean
}

export interface ChronicleData {
  prey: Prey[]
  verdicts: Verdict[]
  alerts: DaywalkerAlert[]
  pendingPatterns: Pattern[]
}
