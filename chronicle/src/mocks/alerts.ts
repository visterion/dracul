import type { DaywalkerAlert } from '../api/types'

const today = (hour: number, minute: number) => {
  const d = new Date()
  d.setHours(hour, minute, 0, 0)
  return d.toISOString()
}

export const mockAlerts: DaywalkerAlert[] = [
  {
    id: 'alert-1',
    symbol: 'AVGO',
    description: 'Price spike +4.2% on 3.1× average volume — possible institutional accumulation',
    severity: 'WARNING',
    triggeredAt: today(14, 23),
  },
  {
    id: 'alert-2',
    symbol: 'NVDA',
    description: 'Insider sale Form 4 filed — CFO sold $890K at market open',
    severity: 'WARNING',
    triggeredAt: today(9, 15),
  },
]
