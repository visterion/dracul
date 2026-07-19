import type { ExplainerTable } from './explainers'

const en: ExplainerTable = {
  'orders.bracket': {
    title: 'Protected order',
    sections: [
      {
        anchor: 'bracket',
        heading: 'What is a protected order?',
        body: 'A buy with automatic protection — three linked orders: entry (the buy), target (take-profit) and stop (loss protection). Target and stop only arm once the entry has filled.',
      },
    ],
  },
}

export default en
