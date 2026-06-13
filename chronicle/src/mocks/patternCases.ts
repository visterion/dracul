import type { PatternCase } from '../api/types'

// Deterministic generator for mock supporting cases. Each pending mock pattern
// gets a set whose total count and supported-count exactly match the aggregate
// counts shown on its card (see mocks/patterns.ts):
//   pattern-pending-1 → 12 cases, 9 supported
//   pattern-pending-2 → 28 cases, 21 supported
//   pattern-pending-3 → 7 cases, 0 supported
// Cases use plausible tickers/companies/anomaly types and dates before the
// pattern's proposed_at, ordered occurredAt DESC.

interface CaseSpec {
  total: number
  supported: number
  anomalyType: string
  /** days before "now" the pattern was proposed — cases fall before this */
  proposedDaysAgo: number
  pool: { symbol: string; companyName: string }[]
}

const POOLS = {
  techSpin: [
    { symbol: 'WBD', companyName: 'Warner Bros. Discovery' },
    { symbol: 'GEHC', companyName: 'GE HealthCare' },
    { symbol: 'KVUE', companyName: 'Kenvue' },
    { symbol: 'VEEV', companyName: 'Veeva Spinco' },
    { symbol: 'SOLV', companyName: 'Solventum' },
    { symbol: 'PHIN', companyName: 'PHINIA' },
    { symbol: 'CRGY', companyName: 'Crescent Energy Tech' },
    { symbol: 'NCNO', companyName: 'nCino Spinout' },
    { symbol: 'GXO', companyName: 'GXO Logistics' },
    { symbol: 'OTIS', companyName: 'Otis Worldwide' },
    { symbol: 'CARR', companyName: 'Carrier Global' },
    { symbol: 'IBM', companyName: 'Kyndryl Holdings' },
  ],
  insider: [
    { symbol: 'CVNA', companyName: 'Carvana' },
    { symbol: 'OXY', companyName: 'Occidental Petroleum' },
    { symbol: 'PLTR', companyName: 'Palantir Technologies' },
    { symbol: 'DKNG', companyName: 'DraftKings' },
    { symbol: 'AFRM', companyName: 'Affirm Holdings' },
    { symbol: 'SOFI', companyName: 'SoFi Technologies' },
    { symbol: 'RBLX', companyName: 'Roblox' },
    { symbol: 'COIN', companyName: 'Coinbase Global' },
    { symbol: 'HOOD', companyName: 'Robinhood Markets' },
    { symbol: 'U', companyName: 'Unity Software' },
    { symbol: 'PATH', companyName: 'UiPath' },
    { symbol: 'BILL', companyName: 'BILL Holdings' },
    { symbol: 'DOCN', companyName: 'DigitalOcean' },
    { symbol: 'FROG', companyName: 'JFrog' },
    { symbol: 'GTLB', companyName: 'GitLab' },
    { symbol: 'ESTC', companyName: 'Elastic' },
    { symbol: 'CFLT', companyName: 'Confluent' },
    { symbol: 'S', companyName: 'SentinelOne' },
    { symbol: 'NET', companyName: 'Cloudflare' },
    { symbol: 'DDOG', companyName: 'Datadog' },
    { symbol: 'MDB', companyName: 'MongoDB' },
    { symbol: 'SNOW', companyName: 'Snowflake' },
    { symbol: 'CRWD', companyName: 'CrowdStrike' },
    { symbol: 'ZS', companyName: 'Zscaler' },
    { symbol: 'OKTA', companyName: 'Okta' },
    { symbol: 'TWLO', companyName: 'Twilio' },
    { symbol: 'TEAM', companyName: 'Atlassian' },
    { symbol: 'WDAY', companyName: 'Workday' },
  ],
  lazarus: [
    { symbol: 'NYT', companyName: 'New York Times Legacy Print' },
    { symbol: 'GCI', companyName: 'Gannett' },
    { symbol: 'M', companyName: 'Macy\'s' },
    { symbol: 'JWN', companyName: 'Nordstrom' },
    { symbol: 'KSS', companyName: 'Kohl\'s' },
    { symbol: 'LUMN', companyName: 'Lumen Technologies' },
    { symbol: 'FTR', companyName: 'Frontier Communications' },
  ],
}

const SPECS: Record<string, CaseSpec> = {
  'pattern-pending-1': { total: 12, supported: 9, anomalyType: 'SPIN', proposedDaysAgo: 2, pool: POOLS.techSpin },
  'pattern-pending-2': { total: 28, supported: 21, anomalyType: 'INSIDER', proposedDaysAgo: 7, pool: POOLS.insider },
  'pattern-pending-3': { total: 7, supported: 0, anomalyType: 'LAZARUS', proposedDaysAgo: 1, pool: POOLS.lazarus },
}

const DAY = 86_400_000

function buildCases(spec: CaseSpec): PatternCase[] {
  const cases: PatternCase[] = []
  // Earliest case starts well before proposedAt; each subsequent one is more recent.
  const baseOffsetDays = spec.proposedDaysAgo + 30
  for (let i = 0; i < spec.total; i++) {
    const supported = i < spec.supported
    const company = spec.pool[i % spec.pool.length]
    // Spread occurrence dates across the window before proposedAt, descending.
    const daysBeforeNow = baseOffsetDays + (spec.total - 1 - i) * 9
    const occurredAt = new Date(Date.now() - daysBeforeNow * DAY).toISOString()
    // Plausible return: supported cases skew positive, refuted skew negative.
    const magnitude = 4 + ((i * 37) % 19)
    const returnPercent =
      i % 7 === 5 && !supported
        ? null // a few open/unknown cases
        : supported
          ? Math.round((magnitude + 2) * 10) / 10
          : Math.round(-(magnitude * 0.6 + 1) * 10) / 10
    cases.push({
      symbol: company.symbol,
      companyName: company.companyName,
      anomalyType: spec.anomalyType,
      occurredAt,
      supported,
      returnPercent,
    })
  }
  // occurredAt DESC (most recent first)
  return cases.sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
}

export function mockPatternCases(id: string): PatternCase[] {
  const spec = SPECS[id]
  if (!spec) return []
  return buildCases(spec)
}
