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

| # | View | Route | Purpose | Density | Status |
|---|------|-------|---------|---------|--------|
| 1 | Chronicle | `/` | Morning dashboard — new prey, verdicts, alerts, lessons | Medium | ✅ Etappe 9 |
| 2 | Verdict Detail | `/verdict/:id` | Deep-read of one consolidated finding | Low (prose) | ✅ Etappe 10 |
| 3 | Strigoi Detail | `/strigoi/:name` | One agent's runs, stats, configuration | High | ✅ Etappe 10 |
| 4 | Watchlist | `/watchlist` | Active monitoring of held/tracked instruments | Medium | ✅ Etappe 11 |
| 5 | Pattern Library | `/patterns` | Approve Voievod lessons, view active patterns | Low | ✅ Etappe 11 |
| 6 | Vistierie | `/vistierie` | Cost dashboard, tier budgets, trends | High | ✅ Etappe 13 |
| 7 | Backtest | `/backtest` | Historical validation of Strigoi strategies | High | ✅ Etappe 13 |
| 8 | Settings | `/settings` | Providers, budgets, agent config, notifications | Variable | ✅ Etappe 11 |

## Live alert panel

A top-bar indicator (🔔 with a connection dot + unread badge) opens a dedicated
live-alert panel. The panel subscribes to `GET /api/events` over SSE and lists
incoming Daywalker alerts (severity, symbol, trigger, thesis) newest-first, with
a connection status. Active only against a real backend (disabled in mock mode).

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

To run the dev server against the real backend locally, copy
`.env.local.example` to `.env.local` (gitignored):

```bash
cp chronicle/.env.local.example chronicle/.env.local
# then: npm run dev → proxies /api/* to http://localhost:8080
```

In production, the frontend is served directly by Spring Boot (Vue
`dist/` baked into `src/main/resources/static/` of the JAR; `SpaFallbackController`
handles Vue Router paths).

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

**View 2 — Verdict Detail** (`/verdict/:id`): Fully implemented and wired to the real API,
including `PUT /api/verdict/{id}/decision` and `POST`/`GET /api/verdict/{id}/notes`.
Two-pane layout (3fr + 1fr sticky sidebar). Main pane: breadcrumb, symbol header,
anomaly type badges, full prose summary, signals list (gold bullets), risks list
(ash-gray bullets), contributing strigoi sub-cards with router-links to View 3.
Sidebar: Decision panel (Track/Interesting/Dismiss buttons + notes textarea), Quick
stats table (price, consensus, avg confidence, horizon, discovered), Daywalker
status panel.

**View 3 — Strigoi Detail** (`/strigoi/:name`): Fully implemented and wired to the real API.
Single-pane layout. Page header with bat icon, state pill (hunting/resting/paused/
budget-hit), schedule. Three stat cards (hunts, avg prey, hit rate — green when
≥60%). Expandable run trace timeline (newest auto-expanded, trace rows styled by
event type). 3-column recent prey grid (reuses PreyCard). 2-column configuration
panel. ApexCharts dual-axis line chart (hit rate % left axis, prey count right axis,
25 weeks of data).

## Implementation status (Etappe 11)

**View 4 — Watchlist** (`/watchlist`): Fully implemented and wired to the real API, full CRUD
(`POST`, `PATCH`, `DELETE /api/watchlist/{id}`) wired.
Two-pane layout (60% list / 40% detail). Left pane: search input, filter chips
(All/Held/Tracking/Alerts with live counts), add-to-watchlist button (stub), scrollable
item list with ticker, price, day change, status dot (calm/elevated/alert). Right pane:
selected item detail with Daywalker alert timeline (max 5 alerts), 30-day ApexCharts
area sparkline, linked verdict card (when verdictId is set), ghost action buttons (stubs).
Selected state is a local `ref<string | null>` initialized to the first item on load.

**View 5 — Pattern Library** (`/patterns`): Fully implemented and wired to the real API;
approve/reject/defer/deactivate all call `PATCH /api/patterns/{id}`.
Single-pane max-width 960px. Pending section: Voievod-proposed lesson cards with gold
left border, evidence counts, and Approve/Reject/Defer buttons (stubs). Active section:
filterable by Strigoi chip (derived from unique `appliesToStrigoi` values); each row
expands to show full pattern text and Deactivate button (stub). Expand state uses the
`ref<Set<string>>` reactivity pattern from StrigoiDetailView — new Set created on each
toggle for correct Vue reactivity.

**View 8 — Settings** (`/settings`): Fully implemented and wired to the real API,
including `GET`/`PATCH /api/settings/budgets` and `PATCH /api/settings/budgets/agents/{name}`.
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

## Implementation status (Etappe 12 — Backend REST API)

The Spring Boot 4 backend (`java-server/`) is fully scaffolded and all
7 REST endpoints are live:

| Endpoint | Purpose |
|----------|---------|
| `GET /api/chronicle` | Morning dashboard — recent prey + verdicts |
| `GET /api/verdict/{id}` | Verdict detail with contributing prey |
| `GET /api/patterns` | Pattern library (pending + active) |
| `GET /api/watchlist` | Watchlist items with alerts and verdictId |
| `GET /api/status` | Runtime status (strigoi count, cost today) |
| `GET /api/strigoi` | Strigoi metadata list |
| `GET /api/providers` | LLM provider list |

**HttpApiClient** (`chronicle/src/api/HttpApiClient.ts`): all 7 methods
implemented against the real backend. Switch via `VITE_MOCK=false` in
`chronicle/.env.local` (see `.env.local.example`).

**Integration tests**: 4 IT classes (`ChronicleControllerIT`,
`VerdictControllerIT`, `PatternControllerIT`, `WatchlistControllerIT`)
run against Testcontainers Postgres with seed data.

**Frontend status** with `VITE_MOCK=false`: Views 1–5 + 8 fully wired
to real backend data. Views 6 (Vistierie) and 7 (Backtest) remain
intentional stubs pending their respective etappes.

## Implementation status (Etappe 13)

**View 6 — Vistierie Dashboard** (`/vistierie`): Fully implemented with real backend data
(`GET /api/vistierie`). Three-column layout: tier budget bars (green/gold/crimson by
utilisation), horizontal agent spending bars, ApexCharts area trend chart (30-day daily
spend). TierBudgetBar is a reusable component (`src/components/TierBudgetBar.vue`).
Backend derives tier budgets from `application.yaml` config and aggregates provider costs
from `VistierieClient`.

**View 7 — Backtest** (`/backtest`): View structure complete, backtest engine deferred to
Stufe 5 — Run button is disabled, data is hardcoded. Config panel: strigoi chip-group
(multi-select), date range with preset chips, universe radio group, disabled "Run Backtest"
button. Three recent backtest cards are clickable and switch the results section. Four
result tabs: Overview (4 stat cards), Trades (8 simulated trades), Equity Curve (ApexCharts
dual-series vs SPY), Comparison (strategy vs SPY table).

## Implementation status (Etappe 14)

**Pattern Library actions wired** (`PATCH /api/patterns/{id}`): Approve/Reject/Defer/Deactivate
buttons now call the backend. Approve auto-generates a slug from the first 5 words of the
pattern statement (lowercased, kebab-cased). Defer is a no-op (status unchanged). Reject and
Deactivate both set status → REJECTED. The frontend removes the pattern from the pending list
(approve/reject/defer) or marks it REJECTED in-place (deactivate) without a full reload.
Per-card `pendingLoadingId` / `activeLoadingId` refs prevent double-clicks during in-flight requests.

**Settings > Budgets section** (`GET/PATCH /api/settings/budgets`,
`PATCH /api/settings/budgets/agents/{name}`): Tenant-level and per-agent budget caps
(daily + monthly) are now fully editable. Budget values transit as micros internally
(1 USD = 1 000 000 micros); conversion happens at the API client boundary via
`microsToUsd` / `usdToMicros` helpers. The section lazy-loads on first navigation
(watch on navSection). A 4-column grid for tenant caps, a table for per-agent caps
with inline save buttons.

**VistierieClient expanded** — 11 new methods added to the interface:
`patchAgent`, `listRuns`, `triggerRun`, `getRunEvents`, `getTenantBudget`,
`patchTenantBudget`, `getAgentBudget`, `patchAgentBudget`, `getKillStatus`,
`setKill`, `clearKill`. MockVistierieClient and HttpVistierieClient both implement
the full interface. `getDashboardData()` in HttpVistierieClient now calls the real
`/admin/cost?granularity=day` endpoint instead of returning zeros.

**New REST endpoints (Etappe 14):**

| Endpoint | Purpose |
|----------|---------|
| `PATCH /api/patterns/{id}` | approve / reject / defer / deactivate a pattern |
| `GET /api/settings/budgets` | tenant budget + per-agent budgets |
| `PATCH /api/settings/budgets` | update tenant budget caps |
| `PATCH /api/settings/budgets/agents/{name}` | update one agent's budget caps |

## E2E Test Suite (Etappe 15)

**Playwright E2E tests** (`chronicle/e2e/`): 9 spec files covering all 8 views plus navigation smoke tests. Tests run against `VITE_MOCK=true` (no backend required). Chromium only.

Run locally: `cd chronicle && npm run test:e2e`

CI: `e2e` job in `.github/workflows/docker.yml` runs in parallel with the Docker build job.

## Implementation status (verdict decisions + watchlist CRUD)

Backend now persists verdict decisions and watchlist mutations.

**Verdict decision** (`PUT /api/verdict/{id}/decision`) accepts TRACK, INTERESTING,
DISMISS, ACTED, or null. The decision is stored on the verdict alongside a
`decidedAt` timestamp.

**Verdict notes** (`POST` and `GET /api/verdict/{id}/notes`) provide an append-only
journal per verdict — useful for capturing evolving reasoning over time. The GET
endpoint returns notes in descending order by createdAt.

**Watchlist CRUD** (`POST`, `PATCH`, `DELETE /api/watchlist/{id}`): operators add
tickers manually or by reference to a verdict. POST is idempotent — adding an
existing symbol returns the existing item; the source verdict link is merged in
only when previously null. Symbol metadata (company name, current price, 30-day
history) is resolved synchronously via the new MarketDataPort (Yahoo Finance
v1 adapter).

`GET /api/chronicle` now filters DISMISS-ed verdicts by default. Pass
`?includeDismissed=true` to see them.

Frontend wiring (Decision buttons, Notes textarea, Add-to-Watchlist) is deferred
to a follow-up etappe.
