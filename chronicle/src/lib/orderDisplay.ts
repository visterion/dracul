import type { DepotOrderView } from '../api/types'

export type OrderTone = 'gold' | 'crimson' | 'green' | 'ash'

type TFn = (key: string) => string

/** Turn an unknown broker enum into readable Title Case: split camelCase and
 *  snake/kebab, then Title-Case each word. Never returns an empty string for
 *  non-empty input. */
export function prettifyEnum(raw: string): string {
  return raw
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .trim()
    .split(/\s+/)
    .map(w => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
    .join(' ')
}

function isBlank(v: string | null): v is null {
  return v == null || v.trim() === ''
}

const SIDE: Record<string, { key: string; tone: OrderTone; arrow: '↑' | '↓' }> = {
  buy: { key: 'depots.orders.side.buy', tone: 'green', arrow: '↑' },
  sell: { key: 'depots.orders.side.sell', tone: 'crimson', arrow: '↓' },
}

export function orderSideLabel(
  side: string | null,
  t: TFn,
): { label: string; tone: OrderTone; arrow: '↑' | '↓' | '' } {
  if (isBlank(side)) return { label: '—', tone: 'ash', arrow: '' }
  const hit = SIDE[side.toLowerCase()]
  if (hit) return { label: t(hit.key), tone: hit.tone, arrow: hit.arrow }
  return { label: prettifyEnum(side), tone: 'ash', arrow: '' }
}

const TYPE: Record<string, string> = {
  market: 'depots.orders.type.market',
  limit: 'depots.orders.type.limit',
  stop: 'depots.orders.type.stop',
  stop_limit: 'depots.orders.type.stopLimit',
  stopiftraded: 'depots.orders.type.stop',
}

export function orderTypeLabel(type: string | null, t: TFn): { label: string } {
  if (isBlank(type)) return { label: '—' }
  const hit = TYPE[type.toLowerCase()]
  if (hit) return { label: t(hit) }
  return { label: prettifyEnum(type) }
}

const STATUS: Record<string, { key: string; tone: OrderTone }> = {
  working: { key: 'depots.orders.status.active', tone: 'gold' },
  new: { key: 'depots.orders.status.open', tone: 'gold' },
  accepted: { key: 'depots.orders.status.open', tone: 'gold' },
  pending: { key: 'depots.orders.status.open', tone: 'gold' },
  filled: { key: 'depots.orders.status.filled', tone: 'green' },
  partially_filled: { key: 'depots.orders.status.partiallyFilled', tone: 'green' },
  notworking: { key: 'depots.orders.status.inactive', tone: 'ash' },
  canceled: { key: 'depots.orders.status.canceled', tone: 'ash' },
  cancelled: { key: 'depots.orders.status.canceled', tone: 'ash' },
  expired: { key: 'depots.orders.status.expired', tone: 'ash' },
  rejected: { key: 'depots.orders.status.rejected', tone: 'crimson' },
}

export function orderStatusLabel(
  status: string | null,
  t: TFn,
): { label: string; tone: OrderTone } {
  if (isBlank(status)) return { label: '—', tone: 'ash' }
  const hit = STATUS[status.toLowerCase()]
  if (hit) return { label: t(hit.key), tone: hit.tone }
  return { label: prettifyEnum(status), tone: 'ash' }
}

/** Canonical role vocabulary used by the UI (i18n keys depots.orders.role.*).
 *  Agora emits entry/stop_loss/take_profit/other — normalize to entry/stop/target/other. */
export type CanonicalRole = 'entry' | 'stop' | 'target' | 'other'

const ROLE_ALIASES: Record<string, CanonicalRole> = {
  entry: 'entry',
  stop: 'stop',
  stop_loss: 'stop',
  target: 'target',
  take_profit: 'target',
  other: 'other',
}

export function normalizeRole(role: string | null): CanonicalRole | null {
  if (isBlank(role)) return null
  return ROLE_ALIASES[role.toLowerCase()] ?? 'other'
}

/** Status label with lay-language override: a protective leg (target/stop) that
 *  is not yet armed reads "waiting for entry" instead of the bare broker
 *  "inactive". The entry leg and all filled/active states keep orderStatusLabel. */
export function orderStateLabel(
  status: string | null,
  role: string | null,
  t: TFn,
): { label: string; tone: OrderTone } {
  const canonical = normalizeRole(role)
  const raw = (status ?? '').toLowerCase()
  const inactive = raw === 'notworking' || raw === 'inactive'
  if (inactive && (canonical === 'target' || canonical === 'stop')) {
    return { label: t('depots.orders.state.waitingForEntry'), tone: 'ash' }
  }
  return orderStatusLabel(status, t)
}

export interface OrderLeg {
  order: DepotOrderView
  canonicalRole: CanonicalRole | null
}
export interface OrderGroup {
  key: string
  symbol: string
  legs: OrderLeg[]
}

const ROLE_ORDER: Record<string, number> = { entry: 0, target: 1, stop: 2, other: 3 }

function pushLeg(map: Map<string, OrderLeg[]>, key: string, leg: OrderLeg): void {
  const list = map.get(key) ?? []
  list.push(leg)
  map.set(key, list)
}

/** Group orders into logical brackets. Two passes:
 *  1. Seed groups by the bracket's entry-order id: a leg with a parentId belongs
 *     to bracket <parentId>; an entry (no parentId) seeds bracket <its own id>.
 *  2. Attach each orphan (a protective leg with no parentId — defensive fallback)
 *     to an existing entry-group of the same symbol; if none exists it becomes
 *     its own group. An `other`/unknown orphan always becomes its own group.
 *  Within a group, legs are ordered entry → target → stop → other. */
export function groupOrders(orders: DepotOrderView[]): OrderGroup[] {
  const groups = new Map<string, OrderLeg[]>()
  const orphans: OrderLeg[] = []
  for (const o of orders) {
    const leg: OrderLeg = { order: o, canonicalRole: normalizeRole(o.role) }
    if (o.parentId != null) pushLeg(groups, `p:${o.parentId}`, leg)
    else if (leg.canonicalRole === 'entry') pushLeg(groups, `p:${o.brokerOrderId}`, leg)
    else orphans.push(leg)
  }
  for (const leg of orphans) {
    const protective = leg.canonicalRole === 'target' || leg.canonicalRole === 'stop'
    const match = protective
      ? [...groups.values()].find(
          g => g[0].order.symbol === leg.order.symbol && g.some(l => l.canonicalRole === 'entry'),
        )
      : undefined
    if (match) match.push(leg)
    else pushLeg(groups, `s:${leg.order.brokerOrderId}`, leg)
  }
  const result: OrderGroup[] = []
  for (const [key, legs] of groups) {
    legs.sort((a, b) => ROLE_ORDER[a.canonicalRole ?? 'other'] - ROLE_ORDER[b.canonicalRole ?? 'other'])
    result.push({ key, symbol: legs[0].order.symbol, legs })
  }
  return result
}
