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
