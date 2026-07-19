import { describe, it, expect } from 'vitest'
import { prettifyEnum, orderSideLabel, orderTypeLabel, orderStatusLabel, normalizeRole, orderStateLabel, groupOrders } from './orderDisplay'
import type { DepotOrderView } from '../api/types'

// Stub translate: echoes the key so tests assert which i18n key was chosen.
const t = (k: string) => k

describe('prettifyEnum', () => {
  it('splits snake_case and Title-Cases', () => {
    expect(prettifyEnum('partially_filled')).toBe('Partially Filled')
  })
  it('splits camelCase', () => {
    expect(prettifyEnum('stopIfTraded')).toBe('Stop If Traded')
  })
  it('lowercases the tail of each word', () => {
    expect(prettifyEnum('PENDING_NEW')).toBe('Pending New')
  })
})

describe('orderSideLabel', () => {
  it('maps buy → green, up arrow', () => {
    expect(orderSideLabel('buy', t)).toEqual({ label: 'depots.orders.side.buy', tone: 'green', arrow: '↑' })
  })
  it('maps sell → crimson, down arrow', () => {
    expect(orderSideLabel('sell', t)).toEqual({ label: 'depots.orders.side.sell', tone: 'crimson', arrow: '↓' })
  })
  it('is case-insensitive', () => {
    expect(orderSideLabel('BUY', t).tone).toBe('green')
  })
  it('null → dash, ash, no arrow', () => {
    expect(orderSideLabel(null, t)).toEqual({ label: '—', tone: 'ash', arrow: '' })
  })
  it('empty string → dash', () => {
    expect(orderSideLabel('  ', t).label).toBe('—')
  })
  it('unknown → prettified, ash, no arrow', () => {
    expect(orderSideLabel('short_sell', t)).toEqual({ label: 'Short Sell', tone: 'ash', arrow: '' })
  })
})

describe('orderTypeLabel', () => {
  it('maps limit', () => {
    expect(orderTypeLabel('limit', t).label).toBe('depots.orders.type.limit')
  })
  it('maps stopiftraded → the stop key (simplified)', () => {
    expect(orderTypeLabel('stopiftraded', t).label).toBe('depots.orders.type.stop')
  })
  it('maps stop_limit', () => {
    expect(orderTypeLabel('stop_limit', t).label).toBe('depots.orders.type.stopLimit')
  })
  it('null → dash', () => {
    expect(orderTypeLabel(null, t).label).toBe('—')
  })
  it('unknown → prettified', () => {
    expect(orderTypeLabel('trailing_stop', t).label).toBe('Trailing Stop')
  })
})

describe('orderStatusLabel', () => {
  it('maps working → active/gold', () => {
    expect(orderStatusLabel('working', t)).toEqual({ label: 'depots.orders.status.active', tone: 'gold' })
  })
  it('maps notworking → inactive/ash', () => {
    expect(orderStatusLabel('notworking', t)).toEqual({ label: 'depots.orders.status.inactive', tone: 'ash' })
  })
  it('maps filled → green', () => {
    expect(orderStatusLabel('filled', t).tone).toBe('green')
  })
  it('maps partially_filled → green', () => {
    expect(orderStatusLabel('partially_filled', t)).toEqual({ label: 'depots.orders.status.partiallyFilled', tone: 'green' })
  })
  it('maps rejected → crimson', () => {
    expect(orderStatusLabel('rejected', t).tone).toBe('crimson')
  })
  it('maps both canceled and cancelled', () => {
    expect(orderStatusLabel('canceled', t).label).toBe('depots.orders.status.canceled')
    expect(orderStatusLabel('cancelled', t).label).toBe('depots.orders.status.canceled')
  })
  it('is case-insensitive', () => {
    expect(orderStatusLabel('FILLED', t).tone).toBe('green')
  })
  it('null → dash/ash', () => {
    expect(orderStatusLabel(null, t)).toEqual({ label: '—', tone: 'ash' })
  })
  it('unknown → prettified, ash', () => {
    expect(orderStatusLabel('pending_new', t)).toEqual({ label: 'Pending New', tone: 'ash' })
  })
})

describe('normalizeRole', () => {
  it('maps real Agora role strings to canonical roles', () => {
    expect(normalizeRole('entry')).toBe('entry')
    expect(normalizeRole('stop_loss')).toBe('stop')
    expect(normalizeRole('take_profit')).toBe('target')
    expect(normalizeRole('other')).toBe('other')
    expect(normalizeRole(null)).toBe(null)
    expect(normalizeRole('STOP_LOSS')).toBe('stop') // case-insensitive
    expect(normalizeRole('exit')).toBe('other')     // unknown non-blank → other
  })
})

describe('orderStateLabel', () => {
  it('says "waiting for entry" for an inactive protective leg', () => {
    expect(orderStateLabel('notWorking', 'target', t).label).toBe('depots.orders.state.waitingForEntry')
  })
  it('keeps normal status for the entry leg', () => {
    expect(orderStateLabel('working', 'entry', t).label).toBe('depots.orders.status.active')
  })
})

function ord(p: Partial<DepotOrderView>): DepotOrderView {
  return { brokerOrderId: 'x', symbol: 'STT', side: null, qty: 6, type: 'limit',
    status: 'working', role: 'entry', parentId: null, ...p }
}

describe('groupOrders', () => {
  it('groups an entry with its stop/target legs by parentId, entry first', () => {
    // Real broker shape: entry has parentId=null, legs carry parentId = entry id.
    const orders = [
      ord({ brokerOrderId: 'e', role: 'entry', parentId: null, status: 'working' }),
      ord({ brokerOrderId: 't', role: 'take_profit', parentId: 'e', status: 'notWorking', type: 'limit' }),
      ord({ brokerOrderId: 's', role: 'stop_loss', parentId: 'e', status: 'notWorking', type: 'stop' }),
    ]
    const groups = groupOrders(orders)
    expect(groups).toHaveLength(1)
    expect(groups[0].legs.map(l => l.canonicalRole)).toEqual(['entry', 'target', 'stop'])
  })

  it('falls back to symbol grouping when parentId is null', () => {
    const orders = [
      ord({ brokerOrderId: 'e', role: 'entry', parentId: null }),
      ord({ brokerOrderId: 's', role: 'stop_loss', parentId: null, type: 'stop' }),
    ]
    const groups = groupOrders(orders)
    expect(groups).toHaveLength(1)
    expect(groups[0].legs).toHaveLength(2)
  })

  it('keeps an unrelated single order as its own group', () => {
    const groups = groupOrders([ord({ brokerOrderId: 'z', symbol: 'AAA', role: 'other', parentId: null })])
    expect(groups).toHaveLength(1)
    expect(groups[0].legs).toHaveLength(1)
  })

  it('nails the documented caveat: two entries + one orphan leg, same symbol (no parentId)', () => {
    const groups = groupOrders([
      ord({ brokerOrderId: 'e1', role: 'entry', parentId: null }),
      ord({ brokerOrderId: 'e2', role: 'entry', parentId: null }),
      ord({ brokerOrderId: 's', role: 'stop_loss', parentId: null, type: 'stop' }),
    ])
    expect(groups).toHaveLength(2)
    const withStop = groups.find(g => g.legs.some(l => l.canonicalRole === 'stop'))!
    expect(withStop.legs.map(l => l.order.brokerOrderId)).toContain('e1')
  })
})
