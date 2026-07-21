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
  'hunter.daywalker': {
    title: 'Daywalker — Day watcher',
    sections: [
      { heading: 'Role', body: 'Watches your held positions and watchlist during the day. Detects notable events on its own — without the LLM — and lets the LLM only classify the severity.' },
      { heading: 'When', body: 'On weekdays during trading hours, at regular intervals (default every 5 minutes, configurable — every 15 minutes on this system).' },
      { heading: 'What for', body: 'Price jump, volume spike, insider sale, negative news, analyst downgrade.', table: [{ label: 'Price jump', value: '≥ 3 %' }, { label: 'Volume', value: '≥ 3×' }, { label: 'Severities', value: 'INFO · WARNING · CRITICAL' }] },
      { heading: 'What it does', body: 'Writes an alert into the chronicle and sends a Telegram note on CRITICAL. It trades nothing.' },
    ],
  },
  'hunter.daywalker-deep': {
    title: 'Daywalker-Deep — Second opinion',
    sections: [
      { heading: 'Role', body: 'The more thorough second assessor for the day watcher. Steps in only when the LLM is itself unsure about a CRITICAL alert.' },
      { heading: 'When', body: 'Only on low self-confidence (confidence below 0.6) on a CRITICAL alert — asynchronously, in the background.' },
      { heading: 'Important', body: 'Never delays or lowers the original alert. A severity is only ever raised on the same day, never lowered.' },
    ],
  },
  'hunter.gropar': {
    title: 'Gropar — Evening exit',
    sections: [
      { heading: 'Role', body: 'Advises on every open position in the evening: sell, trim or hold. An advisor — it does not trade.' },
      { heading: 'When', body: 'On weekdays at 22:00 UTC.' },
      { heading: 'What it measures', body: 'Deterministic exit indicators.', table: [{ label: 'Trailing stop', value: 'ATR 22 × 3.0' }, { label: 'Trend', value: 'MA 50 / 200' }, { label: 'Target', value: '+40 %' }, { label: 'Stop', value: '−15 %' }] },
      { heading: 'What it does', body: 'Writes an exit signal (SELL/TRIM/HOLD) into the chronicle, Telegram on SELL/TRIM.' },
    ],
  },
  'hunter.voievod': {
    title: 'Voievod — The council',
    sections: [
      { heading: 'Role', body: 'Bundles the hunters\' finds into a verdict: only symbols that at least two different Strigoi flagged independently.' },
      { heading: 'When', body: 'On weekdays at 08:00 UTC.' },
      { heading: 'How', body: 'The consensus value is computed in code; the LLM only writes the summary. A decision you have already made is never overwritten.' },
    ],
  },
  'hunter.voievod-outcome': {
    title: 'Voievod-Outcome — The learning',
    sections: [
      { heading: 'Role', body: 'Weekly review: checks how old finds turned out and proposes new patterns (lessons) from them.' },
      { heading: 'When', body: 'On Saturdays at 07:00 UTC.' },
      { heading: 'What it does', body: 'Looks at prey whose horizon expired more than 30 days ago and creates pattern proposals for approval — active only after you confirm them.' },
    ],
  },
  'hunter.renfield': {
    title: 'Renfield — Suggestions',
    sections: [
      { heading: 'Role', body: 'Daily review of your watchlist. Gathers facts per symbol and lets the LLM turn them into ranked trade suggestions.' },
      { heading: 'When', body: 'On weekdays at 12:00 UTC.' },
      { heading: 'Important', body: 'The LLM has no tools here and never trades — it only suggests (up to 30 symbols). Telegram digest per run.' },
    ],
  },
  'hunter.executor': {
    title: 'Executor — The execution',
    sections: [
      { heading: 'Role', body: 'The only agent that actually places orders — guarded entries (with target and stop) and exits.' },
      { heading: 'Guarded by', body: 'Veto catalog, order guard, position sizer, stop ratchet — the LLM cannot trigger an order directly, only guarded tools.', table: [{ label: 'Minimum confidence', value: '0.65' }, { label: 'Max. positions', value: '5' }, { label: 'Max. per sector', value: '2' }, { label: 'Pace', value: '2 / week' }, { label: 'Signal age', value: '≤ 5 days' }] },
      { heading: 'Important', body: 'Budget and venue are configurable (default: 10,000, paper/Saxo-Sim). Runs only when enabled.' },
    ],
  },
  'decision.overview': {
    title: 'How Dracul decides',
    sections: [
      { heading: 'What Dracul is', body: 'Dracul is an autonomous research assistant for stock anomalies — not an auto-trader, not investment advice. It finds candidates; you decide.' },
      { heading: 'The hunters (Strigoi)', body: 'Six specialised pattern hunters wake at night and scan the market for one documented pattern each.', table: [{ label: 'Spin', value: 'Spin-offs' }, { label: 'Insider', value: 'Insider cluster buys' }, { label: 'Echo', value: 'Post-earnings drift (PEAD)' }, { label: 'Lazarus', value: 'Quality at the 52-week low' }, { label: 'Index', value: 'Index-inclusion drift' }, { label: 'Merger', value: 'Merger arbitrage' }] },
      { heading: 'From signal to prey', body: 'Before the expensive LLM, deterministic pre-checks filter in code. Only what passes goes to the LLM. The result is "prey" (a find) with a confidence.' },
      { heading: 'The council (Voievod)', body: 'On weekdays at 08:00 UTC the council bundles symbols that at least two hunters flagged independently into a verdict. The consensus value comes from code, the LLM only writes the summary — your decision is never overwritten.' },
      { heading: 'The watchers', body: 'During the day the Daywalker checks held positions regularly (default every 5 min., configurable — here every 15 min.) for price jump ≥3 %, volume ≥3×, insider sale, negative news, downgrade. In the evening (22:00 UTC) the Gropar advises sell/trim/hold (ATR stop 22×3.0, MA 50/200, target +40 % / stop −15 %).' },
      { heading: 'The suggestions (Renfield)', body: 'On weekdays at 12:00 UTC Renfield reviews up to 30 watchlist symbols and makes ranked suggestions — without tools, without trading itself.' },
      { heading: 'The execution (Executor)', body: 'The only agent that places orders — strictly guarded. Budget and venue are configurable (default 10,000, paper/Saxo-Sim); it runs only when enabled.', table: [{ label: 'Minimum confidence', value: '0.65' }, { label: 'Max. positions', value: '5' }, { label: 'Max. per sector', value: '2' }, { label: 'Pace', value: '2 / week' }, { label: 'Signal age', value: '≤ 5 days' }] },
      { heading: 'The learning (Voievod-Outcome)', body: 'On Saturdays at 07:00 UTC Dracul looks back: prey whose horizon expired more than 30 days ago is evaluated and turned into new pattern proposals — active only after your approval.' },
      { heading: 'Your decision', body: 'Every morning you review the finds in the chronicle and decide. Dracul only executes what you (or the enabled, guarded Executor) approve.' },
    ],
  },
  'orders.roles': {
    title: 'Entry, target & stop',
    sections: [
      { anchor: 'entry', heading: 'Entry', body: 'The actual buy. Until it fills, the target and stop are not yet armed.' },
      { anchor: 'target', heading: 'Target (take-profit)', body: 'A limit order that sells the position at a profit when the price reaches the target.' },
      { anchor: 'stop', heading: 'Stop (loss protection)', body: 'A stop order that closes the position when the price falls below the stop level.' },
      { anchor: 'limitVsStop', heading: 'Limit vs. stop', body: 'Limit = buys/sells only at this price or better. Stop = becomes an order only once the price reaches the stop level.' },
    ],
  },
  'depot.metrics': {
    title: 'Cash, invested & buying power',
    sections: [
      { anchor: 'cash', heading: 'Cash', body: 'Free money in the account, not tied up in positions.' },
      { anchor: 'invested', heading: 'Invested', body: 'The current value of the open positions.' },
      { anchor: 'buyingPower', heading: 'Buying power', body: 'The amount still available to buy with right now.' },
    ],
  },
  'calibration': {
    title: 'Executor calibration',
    sections: [
      { anchor: 'brier', heading: 'Brier score', body: "A measure of how well the executor's probability estimates land (smaller is better, 0 would be perfect). Needs at least 30 cases, otherwise it shows \"insufficient data\"." },
      { anchor: 'vetoPrecision', heading: 'Veto precision', body: 'How often a rejected trade turned out to be rightly rejected. The reasons below are the most common — this is not the full list (there are rarer ones such as budget or concentration limits).' },
      { anchor: 'brokerError', heading: 'BROKER_ERROR', body: 'The broker rejected the order or there was a technical error.' },
      { anchor: 'noStop', heading: 'NO_STOP', body: 'No valid stop could be computed — nothing is traded without protection.' },
      { anchor: 'lowConfidence', heading: 'LOW_CONFIDENCE', body: 'Conviction was below the minimum threshold.' },
      { anchor: 'paceLimit', heading: 'PACE_LIMIT', body: 'The weekly limit on new entries was reached.' },
      { anchor: 'cooldown', heading: 'COOLDOWN', body: 'Cooldown period after the last trade in the same name.' },
      { anchor: 'belowAnchor', heading: 'BELOW_ANCHOR', body: 'The price was on the invalidating side of the anchor/reference level.' },
      { anchor: 'avgR', heading: 'Avg R 20d / 60d', body: 'The average hypothetical result of rejected signals in "R" (a multiple of the risked amount) after 20 or 60 trading days — computed optimistically (assuming fills at the reference price).' },
      { anchor: 'stoppedOut', heading: 'Stopped out', body: 'The share of cases that would have been closed at a loss by the stop.' },
      { anchor: 'slippage', heading: 'Slippage', body: 'The difference between the expected and the actual fill price.' },
      { anchor: 'hardExitLatency', heading: 'Hard-exit latency', body: 'The time from detecting an emergency exit to submitting the order to the broker.' },
    ],
  },
}

export default en
