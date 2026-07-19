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
  'hunter.spin': {
    title: 'strigoi-spin — Spin-offs',
    sections: [
      { anchor: 'idea', heading: 'Idea', body: 'When a conglomerate spins a subsidiary off as its own stock, many funds automatically dump the new share (it does not fit their mandate). This forced selling pushes the price below fair value.' },
      { anchor: 'inputs', heading: 'Inputs & hunting grounds', body: 'SEC registrations (Form 10-12B) from the last 60 days plus the spin-off term sheet (ratio, record date, size).' },
      { anchor: 'strike', heading: 'How it strikes', body: 'Tracks each spin-off through its stages and flags a candidate inside the forced-selling window after distribution.' },
    ],
  },
  'hunter.insider': {
    title: 'strigoi-insider — Insider cluster buys',
    sections: [
      { anchor: 'idea', heading: 'Idea', body: 'When several executives buy their own shares in quick succession, they often know something good the market has not seen yet.' },
      { anchor: 'inputs', heading: 'Inputs & hunting grounds', body: 'SEC Form-4 filings filtered to clusters (>=3 buyers within 30 days, over $500,000 combined); enriched with company size, analyst coverage and whether buys are routine or opportunistic.' },
      { anchor: 'strike', heading: 'How it strikes', body: 'Favours small, under-followed firms with genuine (non-planned) buys and reports the most convincing clusters.' },
    ],
  },
  'hunter.echo': {
    title: 'strigoi-echo — Post-earnings drift',
    sections: [
      { anchor: 'idea', heading: 'Idea', body: 'After a surprisingly strong report the price often keeps drifting for weeks — the market reacts too slowly.' },
      { anchor: 'inputs', heading: 'Inputs & hunting grounds', body: 'Earnings calendar, earnings surprise (admitted from a 5% EPS surprise; the SUE score then refines conviction), report-day market reaction and analyst revisions.' },
      { anchor: 'strike', heading: 'How it strikes', body: 'Only genuine, cash-backed surprises (no accounting tricks), no imminent next report, and a positive market reaction on the report day.' },
    ],
  },
  'hunter.lazarus': {
    title: 'strigoi-lazarus — Quality at the 52-week low',
    sections: [
      { anchor: 'idea', heading: 'Idea', body: 'Solid firms sitting near their yearly low purely because of market panic often recover — quality at a fire-sale price.' },
      { anchor: 'inputs', heading: 'Inputs & hunting grounds', body: 'Your watchlist near the 52-week low (about 10%); Piotroski F-score (balance-sheet quality), Altman-Z (bankruptcy risk), valuation and timing signals.' },
      { anchor: 'strike', heading: 'How it strikes', body: 'Demands high balance-sheet quality, excludes bankruptcy candidates and falling knives, and reports only cheap-and-healthy names.' },
    ],
  },
  'hunter.index': {
    title: 'strigoi-index — Index inclusion',
    sections: [
      { anchor: 'idea', heading: 'Idea', body: 'When a stock is newly added to a large index, index funds must buy it — the forced buying drives the price into the effective date.' },
      { anchor: 'inputs', heading: 'Inputs & hunting grounds', body: 'Announced index changes (S&P press releases, Russell) with announcement and effective date, plus liquidity.' },
      { anchor: 'strike', heading: 'How it strikes', body: 'Reports only while the buy window (today to effective date) is still open; already-effective inclusions are too late.' },
    ],
  },
  'hunter.merger': {
    title: 'strigoi-merger — Merger arbitrage',
    sections: [
      { anchor: 'idea', heading: 'Idea', body: 'After a takeover is announced the target trades just below the offer price — the gap is the profit if the deal closes.' },
      { anchor: 'inputs', heading: 'Inputs & hunting grounds', body: 'SEC deal filings (DEFM14A, tender offers) from the last 45 days, offer price and conditions from the term sheet, current price and expected close.' },
      { anchor: 'strike', heading: 'How it strikes', body: 'Weighs the annualized spread against the break-downside, dampens stock deals and couples the horizon to the expected close.' },
    ],
  },
  'hunter.overview': {
    title: 'The hunters (brood)',
    sections: [
      { anchor: 'was', heading: 'What are the hunters?', body: 'Six specialised hunters ("Strigoi") each hunt exactly one documented market anomaly at night and record finds in the database. Each has its own idea, data sources and trigger. The (i) on each hunter shows its idea, inputs and how it strikes.' },
      { anchor: 'spin', heading: 'spin — Spin-offs', body: 'Forced selling after a subsidiary is spun off pushes the price below fair value.' },
      { anchor: 'insider', heading: 'insider — Insider buys', body: 'Several executives buying their own shares in quick succession — a cluster signals conviction.' },
      { anchor: 'echo', heading: 'echo — Post-earnings drift', body: 'After a surprisingly strong report the price often keeps drifting for weeks.' },
      { anchor: 'lazarus', heading: 'lazarus — Quality at the low', body: 'Healthy firms near their yearly low often recover — quality at a fire-sale price.' },
      { anchor: 'index', heading: 'index — Index inclusion', body: 'A new addition to a large index forces index funds to buy into the effective date.' },
      { anchor: 'merger', heading: 'merger — Merger arbitrage', body: 'The target trades below the offer price — the gap is the profit if the deal closes.' },
    ],
  },
}

export default en
