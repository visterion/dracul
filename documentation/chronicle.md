# Chronicle (frontend)

The Chronicle is Dracul's Vue 3 / Vuetify 3 frontend. It is the
operator's morning view and daily workspace.

## Stack

- Vue 3.5 (`<script setup lang="ts">` throughout)
- Vuetify 3.8 (Dracul dark theme — `--crypt-black` bg, `--blood-crimson` accent)
- Pinia 2 (Setup Stores)
- Vue Router 4 (`createWebHistory`)
- Vite 6 + TypeScript 5 (strict mode)
- ApexCharts / vue3-apexcharts (used in View 3 Strigoi Detail; stubs in Views 6, 7)
- @phosphor-icons/vue for functional iconography
- SSE via native `EventSource` for live Daywalker updates (Etappe 12)

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
| 2 | Verdict Detail | `/verdict/:id` | Deep-read of one consolidated finding | Low (prose) | ✅ Etappe 10 |
| 3 | Strigoi Detail | `/strigoi/:name` | One agent's runs, stats, configuration | High | ✅ Etappe 10 |
| 4 | Watchlist | `/watchlist` | Active monitoring of held/tracked instruments | Medium |
| 5 | Pattern Library | `/patterns` | Approve Voievod lessons, view active patterns | Low |
| 6 | Vistierie | `/vistierie` | Cost dashboard, tier budgets, trends | High |
| 7 | Backtest | `/backtest` | Historical validation of Strigoi strategies | High |
| 8 | Settings | `/settings` | Providers, budgets, agent config, notifications | Variable |

## Application shell

Present on every view:

- **Top bar (64px)**: wordmark "DRACUL" left, navigation tabs center
  (6 entries), moon-icon + avatar placeholder right.
- **Bottom status bar (32px)**: operational summary from `useStatusStore` —
  `☾ 6 strigoi · 2 hunting · daywalker active · $0.43 today`

The status bar currently reads from Pinia (mock data). SSE integration
(`/api/events`) comes in Etappe 12 (Daywalker).

## ApiClient abstraction

The frontend uses an `ApiClient` interface with two implementations:

| Env | Implementation | Source |
|-----|---------------|--------|
| `VITE_MOCK=true` (dev) | `MockApiClient` | `src/mocks/` typed fixtures |
| `VITE_MOCK=false` (prod) | `HttpApiClient` | `fetch` against `/api/*` |

Switch via `VITE_MOCK` in `.env.development` / `.env.production`. Factory
in `src/api/index.ts` exports `useApi()`.

## Development

```bash
cd chronicle
npm install
npm run dev        # http://localhost:5173 (mock data)
npm run type-check # vue-tsc --noEmit
npm run build      # production build → dist/
```

## Live updates

The Daywalker will push `alert.new` events via the SSE endpoint
(`/api/events`). The Chronicle view and the Watchlist view will consume
this stream. This is planned for Etappe 12.

## Implementation status (Etappe 10)

**View 2 — Verdict Detail** (`/verdict/:id`): Fully implemented with mock data.
Two-pane layout (3fr + 1fr sticky sidebar). Main pane: breadcrumb, symbol header,
anomaly type badges, full prose summary, signals list (gold bullets), risks list
(ash-gray bullets), contributing strigoi sub-cards with router-links to View 3.
Sidebar: Decision panel (Track/Interesting/Dismiss buttons + notes textarea), Quick
stats table (price, consensus, avg confidence, horizon, discovered), Daywalker
status panel.

**View 3 — Strigoi Detail** (`/strigoi/:name`): Fully implemented with mock data.
Single-pane layout. Page header with bat icon, state pill (hunting/resting/paused/
budget-hit), schedule. Three stat cards (hunts, avg prey, hit rate — green when
≥60%). Expandable run trace timeline (newest auto-expanded, trace rows styled by
event type). 3-column recent prey grid (reuses PreyCard). 2-column configuration
panel. ApexCharts dual-axis line chart (hit rate % left axis, prey count right axis,
25 weeks of data).

## Implementation status (Etappe 11)

**View 4 — Watchlist** (`/watchlist`): Fully implemented with mock data.
Two-pane layout (60% list / 40% detail). Left pane: search input, filter chips
(All/Held/Tracking/Alerts with live counts), add-to-watchlist button (stub), scrollable
item list with ticker, price, day change, status dot (calm/elevated/alert). Right pane:
selected item detail with Daywalker alert timeline (max 5 alerts), 30-day ApexCharts
area sparkline, linked verdict card (when verdictId is set), ghost action buttons (stubs).
Selected state is a local `ref<string | null>` initialized to the first item on load.

**View 5 — Pattern Library** (`/patterns`): Fully implemented with mock data.
Single-pane max-width 960px. Pending section: Voievod-proposed lesson cards with gold
left border, evidence counts, and Approve/Reject/Defer buttons (stubs). Active section:
filterable by Strigoi chip (derived from unique `appliesToStrigoi` values); each row
expands to show full pattern text and Deactivate button (stub). Expand state uses the
`ref<Set<string>>` reactivity pattern from StrigoiDetailView — new Set created on each
toggle for correct Vue reactivity.

**View 8 — Settings** (`/settings`): Fully implemented with mock data.
Two-pane: 220px sidebar nav + scrollable content area. LLM Providers section fully
implemented: 3 provider cards (Anthropic connected, OpenAI fallback, Ollama local) with
API key, endpoint, models, and today's usage/cost. All other nav sections show a
"coming in a future etappe" stub. Multi-User Settings is visually disabled (opacity 0.4,
pointer-events: none) with a Phase 2 badge.

## Navigation structure

- **Chronicle** is the home page. Most navigation starts here.
- **Verdict Detail** and **Strigoi Detail** are deep-linked from
  Chronicle items. Strigoi names in VerdictCard sublines are clickable links.
- **Watchlist** receives items via the "Track on Watchlist" action in
  Verdict Detail (button rendered, not yet wired).
- **Pattern Library** is reviewed periodically when the Voievod proposes
  new patterns.
- **Vistierie** and **Backtest** are reference / diagnostic views.
- **Settings** is utility.

## Module location

Frontend source lives in `chronicle/` at the repository root.
Standalone Vite project — no Maven integration yet (planned for Etappe
13 when the `dracul-app/` Spring Boot module is created).
