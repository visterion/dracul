import { describe, it, expect } from 'vitest'
import { prettifyEnum, orderSideLabel, orderTypeLabel, orderStatusLabel } from './orderDisplay'

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
