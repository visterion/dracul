# Chronicle (frontend)

The Chronicle is Dracul's Vue 3 / Vuetify 3 frontend. It is the
operator's morning view and daily workspace.

## Stack

- Vue 3 (Composition API)
- Vuetify 3 (Dracul custom theme — see DESIGN.md)
- Pinia
- Vite
- ApexCharts (or Chart.js) for performance charts
- Phosphor Icons for functional iconography
- SSE via native `EventSource` for live Daywalker updates

## Design system

The visual identity ("gothic substance, modern restraint") is fully
specified in the companion documents at the repo root:

- **DESIGN.md** — color tokens, typography, spacing, all 22 components
- **GUI-MOCKUP.md** — view-by-view layout and mockup prompts

Both documents are required reading before implementing any view.

## Eight views

| # | View | Route | Purpose | Density |
|---|------|-------|---------|---------|
| 1 | Chronicle | `/` | Morning dashboard — new prey, verdicts, alerts, lessons | Medium |
| 2 | Verdict Detail | `/verdict/:id` | Deep-read of one consolidated finding | Low (prose) |
| 3 | Strigoi Detail | `/strigoi/:name` | One agent's runs, stats, configuration | High |
| 4 | Watchlist | `/watchlist` | Active monitoring of held/tracked instruments | Medium |
| 5 | Pattern Library | `/patterns` | Approve Voievod lessons, view active patterns | Low |
| 6 | Vistierie | `/vistierie` | Cost dashboard, tier budgets, trends | High |
| 7 | Backtest | `/backtest` | Historical validation of Strigoi strategies | High |
| 8 | Settings | `/settings` | Providers, budgets, agent config, notifications | Variable |

## Application shell

Present on every view:

- **Top bar (64px)**: wordmark "DRACUL" left, navigation tabs center,
  user menu + theme toggle right.
- **Bottom status bar (32px)**: live operational summary pushed via SSE —
  `☾ 8 entities · 6 strigoi resting · daywalker active · $0.43 today`

## Live updates

The Daywalker pushes `alert.new` events via the SSE endpoint
(`/api/events`). The Chronicle view and the Watchlist view consume this
stream and update without page reload. The status bar also updates its
cost and agent-status tokens in real time.

## Navigation structure

- **Chronicle** is the home page. Most navigation starts here.
- **Verdict Detail** and **Strigoi Detail** are deep-linked from
  Chronicle items.
- **Watchlist** receives items via the "Track on Watchlist" action in
  Verdict Detail.
- **Pattern Library** is reviewed periodically when the Voievod proposes
  new patterns.
- **Vistierie** and **Backtest** are reference / diagnostic views.
- **Settings** is utility.

## Module location

Frontend source lives in `chronicle/` (not `dracul-frontend/` — the
`CLAUDE.md` note supersedes the plan-level naming; confirm with code
when the module is scaffolded).
