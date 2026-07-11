import type { Pattern } from '../api/types'

const now = Date.now()
const daysAgo = (d: number) => new Date(now - d * 86_400_000).toISOString()
const monthsAgo = (m: number) => new Date(now - m * 30 * 86_400_000).toISOString()

export const mockPatterns: Pattern[] = [
  // ── Pending ───────────────────────────────────────────────────
  {
    id: 'pattern-pending-1',
    appliesToStrigoi: 'strigoi-spin',
    statement:
      'Spin-offs from technology sector parents (SIC codes 7370–7379) significantly outperform spin-offs from industrial parents within the first 90 days post-separation. Strigoi-Spin should weight technology spin-offs +0.15 in confidence calculation.',
    status: 'PENDING',
    evidenceCount: 12,
    supportedCount: 9,
    avgUpliftPercent: 18,
    proposedAt: daysAgo(2),
  },
  {
    id: 'pattern-pending-2',
    appliesToStrigoi: 'strigoi-insider',
    statement:
      'Insider clusters that include CFO purchases have 23% higher follow-through rates than clusters with only board-level buyers. Recommend adding a CFO-presence boost to confidence.',
    status: 'PENDING',
    evidenceCount: 28,
    supportedCount: 21,
    avgUpliftPercent: null,
    proposedAt: daysAgo(7),
  },
  {
    id: 'pattern-pending-3',
    appliesToStrigoi: 'strigoi-lazarus',
    statement:
      'Quality-at-52w-low signals fail when the company is in a sector experiencing structural decline (newspapers, traditional retail, legacy telecom). Recommend excluding instruments from these sectors.',
    status: 'PENDING',
    evidenceCount: 7,
    supportedCount: 0,
    avgUpliftPercent: null,
    proposedAt: daysAgo(1),
  },
  {
    id: 'pattern-e2e-pending',
    appliesToStrigoi: 'strigoi-echo',
    statement:
      'E2E seed: PEAD candidates announced on Fridays drift 1.3x stronger than mid-week announcements. Boost Friday announcements by +0.05 confidence.',
    status: 'PENDING',
    evidenceCount: 5,
    supportedCount: 4,
    avgUpliftPercent: 7,
    proposedAt: daysAgo(0.5),
  },

  // ── Active ────────────────────────────────────────────────────
  {
    id: 'pattern-active-1',
    appliesToStrigoi: 'strigoi-spin',
    name: 'tech-spinoffs-outperform-industrials',
    statement:
      'Technology-sector spin-offs demonstrate statistically significant outperformance (avg +18%) versus industrial spin-offs within 90 days. Weight technology spin-offs +0.15 in confidence.',
    status: 'ACTIVE',
    evidenceCount: 24,
    supportedCount: 19,
    proposedAt: monthsAgo(3),
  },
  {
    id: 'pattern-active-2',
    appliesToStrigoi: 'strigoi-insider',
    name: 'cluster-includes-cfo-boost',
    statement:
      'CFO participation in insider clusters predicts 23% better follow-through. Apply +0.12 confidence boost when CFO is among cluster buyers.',
    status: 'ACTIVE',
    evidenceCount: 41,
    supportedCount: 32,
    proposedAt: monthsAgo(2),
  },
  {
    id: 'pattern-active-3',
    appliesToStrigoi: 'strigoi-echo',
    name: 'weekly-options-expiry-noise-filter',
    statement:
      'PEAD signals arriving within 3 days of monthly options expiry have 40% lower follow-through. Reduce confidence by 0.20 for earnings releases in this window.',
    status: 'ACTIVE',
    evidenceCount: 18,
    supportedCount: 14,
    proposedAt: monthsAgo(4),
  },
  {
    id: 'pattern-active-4',
    appliesToStrigoi: 'strigoi-lazarus',
    name: 'exclude-declining-sectors',
    statement:
      'Quality-at-52w-low signals in structurally declining sectors (SIC: newspapers, traditional retail, legacy telecom) have negative expected value. Exclude these sectors entirely.',
    status: 'ACTIVE',
    evidenceCount: 11,
    supportedCount: 11,
    proposedAt: monthsAgo(1),
  },
  {
    id: 'pattern-active-5',
    appliesToStrigoi: 'strigoi-spin',
    name: 'exclude-smallcap-tracking-stocks',
    statement:
      'Tracking stocks and smallcap shells (<$100M market cap) produce false positives at 3x the rate of normal spin-offs. Exclude from candidate set.',
    status: 'ACTIVE',
    evidenceCount: 8,
    supportedCount: 8,
    proposedAt: monthsAgo(5),
  },
  {
    id: 'pattern-active-6',
    appliesToStrigoi: 'strigoi-merger',
    name: 'deal-break-risk-elevated-during-recession',
    statement:
      'M&A deal-break probability increases by 2.4x during NBER-defined recession periods. Reduce merger arbitrage confidence by 0.25 when leading indicators signal contraction.',
    status: 'ACTIVE',
    evidenceCount: 6,
    supportedCount: 6,
    proposedAt: monthsAgo(2),
  },
  {
    id: 'pattern-active-7',
    appliesToStrigoi: 'strigoi-insider',
    name: 'new-ceo-first-purchase-strong-signal',
    statement:
      'First open-market purchase by a CEO within 6 months of appointment has 71% hit rate in this dataset. Apply +0.18 confidence boost for this pattern.',
    status: 'ACTIVE',
    evidenceCount: 31,
    supportedCount: 22,
    proposedAt: monthsAgo(6),
  },
  {
    id: 'pattern-active-8',
    appliesToStrigoi: 'strigoi-echo',
    name: 'pead-amplified-by-analyst-silence',
    statement:
      'PEAD effect is 1.8x stronger when zero analyst revisions follow the earnings report for 5+ trading days. Increase confidence by 0.10 in this scenario.',
    status: 'ACTIVE',
    evidenceCount: 22,
    supportedCount: 16,
    proposedAt: monthsAgo(3),
  },
  {
    id: 'pattern-active-9',
    appliesToStrigoi: 'strigoi-spin',
    name: 'spinoff-retained-equity-above-20pct',
    statement:
      'When the parent retains more than 20% equity post-spin, distribution timelines and post-spin price discovery follow predictably. Weight +0.08 confidence.',
    status: 'ACTIVE',
    evidenceCount: 15,
    supportedCount: 11,
    proposedAt: monthsAgo(4),
  },
  {
    id: 'pattern-active-10',
    appliesToStrigoi: 'strigoi-index',
    name: 'russell-rebalance-window-5d',
    statement:
      'Index inclusion candidates identified 5+ trading days before the Russell reconstitution date capture 85% of the full inclusion drift. Earlier detection is strongly rewarded.',
    status: 'ACTIVE',
    evidenceCount: 9,
    supportedCount: 8,
    proposedAt: monthsAgo(7),
  },
  {
    id: 'pattern-active-11',
    appliesToStrigoi: 'strigoi-insider',
    name: 'cluster-size-3-minimum',
    statement:
      'Insider clusters with fewer than 3 distinct buyers have 2x higher false positive rate. Minimum cluster size of 3 should be enforced in pre-screen.',
    status: 'ACTIVE',
    evidenceCount: 47,
    supportedCount: 40,
    proposedAt: monthsAgo(8),
  },
  {
    id: 'pattern-active-12',
    appliesToStrigoi: 'strigoi-lazarus',
    name: 'quality-score-threshold-0.7',
    statement:
      'Quality composite scores below 0.70 at 52w-low are not predictive. The signal only activates above this threshold — below it, mean reversion is not reliably observed.',
    status: 'ACTIVE',
    evidenceCount: 19,
    supportedCount: 15,
    proposedAt: monthsAgo(5),
  },
  {
    id: 'pattern-active-13',
    appliesToStrigoi: 'strigoi-merger',
    name: 'cash-deals-outperform-stock-deals',
    statement:
      'All-cash M&A deals close 34% faster and have 28% lower deal-break rate than stock-based deals. All-cash deals receive +0.15 confidence boost.',
    status: 'ACTIVE',
    evidenceCount: 14,
    supportedCount: 11,
    proposedAt: monthsAgo(3),
  },
  {
    id: 'pattern-active-14',
    appliesToStrigoi: 'strigoi-echo',
    name: 'pead-strongest-in-small-mid-cap',
    statement:
      'PEAD effect is most pronounced in small/mid-cap names (market cap $500M–$10B) where institutional coverage is lower. Apply +0.12 to candidates in this range.',
    status: 'ACTIVE',
    evidenceCount: 33,
    supportedCount: 24,
    proposedAt: monthsAgo(2),
  },
]
