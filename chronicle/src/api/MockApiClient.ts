import type { ApiClient } from './ApiClient'
import type { ChronicleData, SystemStatus, VerdictDetail, StrigoiDetail, WatchlistItem, Pattern, LlmProvider, VistierieData } from './types'
import { mockPrey } from '../mocks/prey'
import { mockVerdicts } from '../mocks/verdicts'
import { mockAlerts } from '../mocks/alerts'
import { mockPatterns } from '../mocks/patterns'
import { mockSystemStatus } from '../mocks/status'
import { mockVerdictDetails } from '../mocks/verdictDetails'
import { mockStrigoiDetails } from '../mocks/strigoiDetails'
import { mockWatchlistItems } from '../mocks/watchlistItems'
import { mockProviders } from '../mocks/providers'

const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

export class MockApiClient implements ApiClient {
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
    return mockWatchlistItems
  }

  async getPatterns(): Promise<Pattern[]> {
    await delay(50)
    return mockPatterns
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
}
