# Chronicle (frontend)

The Chronicle is Dracul's Vue 3 / Vuetify 3 frontend. It is the
operator's morning view and daily workspace.

## Stack

- Vue 3.5 (`<script setup lang="ts">` throughout)
- Vuetify 3.8 (Dracul dark theme — `--crypt-black` bg, `--blood-crimson` accent)
- Pinia 2 (Setup Stores)
- Vue Router 4 (`createWebHistory`)
- Vite 6 + TypeScript 5 (strict mode)
- Hand-rolled inline-SVG charts (`LineChart`, `BarChart`) — no charting
  library dependency (ApexCharts was removed in the Chronicle redesign)
- @phosphor-icons/vue for functional iconography
- SSE via native `EventSource` for live Daywalker updates (Etappe 12)

## Design system

The visual identity ("gothic substance, modern restraint") is fully
specified in the companion documents at the repo root:

- **DESIGN.md** — color tokens, typography, spacing, all 22 components
- **GUI-MOCKUP.md** — view-by-view layout and mockup prompts

Both documents are required reading before implementing any view.

## Views and routes

| # | View | Route | Purpose | Density | Status |
|---|------|-------|---------|---------|--------|
| 1 | Chronicle | `/` | Morning dashboard — new prey, verdicts, alerts, lessons | Medium | ✅ Etappe 9 |
| 2 | Verdict Detail | `/verdict/:id` | Deep-read of one consolidated finding | Low (prose) | ✅ Etappe 10 |
| 3 | Strigoi Detail | `/strigoi/:name` | One agent's runs, stats, configuration | High | ✅ Etappe 10 |
| 4 | Prey Detail | `/prey/:id` | Single prey card — signals, risks, kill criteria, thesis, outcome | Medium | ✅ Chronicle redesign |
| 5 | Watchlist | `/watchlist` | Tracking-only monitoring of watched candidates (positions live in Portfolio) | Medium | ✅ Etappe 11 |
| 6 | Pattern Library | `/patterns` | Approve Voievod lessons, view active patterns | Low | ✅ Etappe 11 |
| 7 | Backtest | `/backtest` | Historical validation of Strigoi strategies | High | ✅ Etappe 13 |
| 8 | Settings | `/settings` | Providers, budgets, agent config, notifications; embedded Schatzkammer (admin-only) | Variable | ✅ Etappe 11 |
| 9 | Portfolio | `/portfolio` | Held positions with P&L and Groparul's latest exit-signal badge | Medium | ✅ |
| 10 | Exit Signal Detail | `/exit-signal/:id` | Full rationale, fired rules, thesis status, position context | Low (prose) | ✅ |
| 11 | Morning Report | `/report` | Daily morning report — per-position stop, +2R target, current price, distance-to-stop, and a read-only order ticket | Medium | ✅ |

> **Vistierie (Schatzkammer):** Vistierie no longer has a standalone route. It is an
> embeddable component (`VistierieView` with an `embedded` prop) hosted admin-only
> inside the Settings view as the pinned "Schatzkammer · Vistierie" section. The legacy
> `/vistierie` path (and any unmatched path) is caught by the catch-all route and
> redirected to `/`.


## Live alert panel

A top-bar indicator (🔔 with a connection dot + unread badge) opens a dedicated
live-alert panel. The panel subscribes to `GET /api/events` over SSE and lists
incoming Daywalker alerts (severity, symbol, trigger, thesis) newest-first, with
a connection status. Active only against a real backend (disabled in mock mode).

Two additional trigger types emitted by the stop-proximity watcher now appear in the live panel and the persistent alert list:

| Trigger type | Localized label (de) | Severity | When |
|---|---|---|---|
| `STOP_PROXIMITY` | Stop-Nähe | WARNING | Live price has entered the stop-proximity zone (`active_stop < price ≤ stop + 0.5·ATR`) |
| `STOP_BREACHED` | Stop gerissen | CRITICAL | Live price is at or below `active_stop` |

Both types are routed through the existing `useEnumLabels()` composable (see `enums.*` i18n keys) and rendered with the same severity colour coding as all other alert types (WARNING = gold, CRITICAL = crimson).

A `verdict.kill_criteria_breached` SSE event (see `documentation/api.md`) is
also surfaced in the live panel — one panel entry per poll, with a `KILL: …`
thesis per newly-breached criterion — and additionally triggers a chronicle
overview reload (`useChronicleStore().load()`) so the affected verdict's
list-level summary data refreshes without a manual page reload. The breach
criteria themselves (the badge described below) are not part of the
overview list payload — they only render on the Verdict Detail view.

On the Verdict Detail view (`/verdict/:id`), a breached verdict shows a
`TagPill tone="crimson"` badge per breached criterion (`KILL: <criterion>`,
`data-testid="vd-kill-breach"`) in the decision card next to the decision
badge, sourced from `VerdictDetail.killCriteriaBreached`. Breaches are
cumulative and never clear automatically — a kill criterion marks the thesis
as dead, so a later price recovery does not remove the badge.

## Application shell

Present on every view:

- **Top bar (64px)**: wordmark "DRACUL" left, navigation tabs center
  (7 destinations: Chronicle, Watchlist, Portfolio, Report, Pattern Library, Backtest, Settings),
  moon-icon + avatar placeholder right.
- **Bottom status bar (32px)**: operational summary from `useStatusStore` —
  `☾ 6 strigoi · 2 hunting · daywalker active · $0.43 today`

The status bar currently reads from Pinia (mock data). SSE integration
(`/api/events`) comes in Etappe 12 (Daywalker).

### Responsive layout

Chronicle is responsive across phones, tablets, and desktop. The single
breakpoint is **960px** (`--bp-mobile`), chosen as the iPad portrait/landscape
divide — phones and portrait tablets get the mobile layout, landscape tablets
and desktop keep the desktop shell. The JS switch is Vuetify
`useDisplay().smAndDown` (true `< 960px`); CSS uses `@media (max-width: 959.98px)`.

Below 960px:

- The centered top-bar nav is replaced by **`AppBottomNav`** — a fixed,
  horizontally scrollable bottom tab bar with all seven destinations (no "More"
  overflow), the active tab in `--blood-crimson`. It reserves
  `env(safe-area-inset-bottom)` and uses ≥44px tap targets. Both navs draw their
  entries from a shared `useNavItems()` composable.
- The top bar collapses to a slim header (wordmark + live-alert bell only); the
  status bar is hidden.
- Multi-column view grids stack to a single column; data-dense tables (Backtest)
  scroll horizontally via the global `.table-scroll` helper (no columns hidden);
  the Settings section nav becomes a horizontal scrollable chip row.
- **Watchlist** switches from its two-pane layout to a **drill-in**: the list is
  full-width, tapping a row opens the detail as a full-screen overlay with a
  `‹ Watchlist` back control. Desktop keeps both panes.

The responsive system is specified in `DESIGN.md` Part 4 (Breakpoints), Part 5.8
(Tables, mobile) and Part 9.1 (Mobile Shell).

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

## Language / i18n

The Chronicle GUI is multilingual: German (default) and English, powered by
`vue-i18n`.

- **Startup**: the app calls `GET /api/settings/language` to load the active
  locale from the backend (server-authoritative). On network failure it falls
  back to German (`de`).
- **Live switching**: Settings → "Sprache" lets the user switch language
  without a page reload. The choice is persisted via
  `PUT /api/settings/language` (accepted values: `de`, `en`).
- **LLM narratives**: agent-generated prose (summaries, signals, theses) is
  localized separately — the backend selects the narrative language at
  agent-registration time. See `configuration.md` and
  `vistierie-integration.md` for details.
- **Domain codes**: backend enum/code values (alert severity, daywalker trigger
  type, anomaly type, time horizon, SSE connection status, agent role/tier/state)
  must never be rendered raw in a component template. Route them through the
  `useEnumLabels()` composable (`src/composables/useEnumLabels.ts`), which maps
  each code to a localized label and falls back to the raw value when no
  translation key exists. Labels live under the shared `enums.*` namespace in
  `src/i18n/locales/{de,en}.ts` (agent state reuses `strigoi.state.*`). Note:
  vue-i18n treats `@` as a special linked-message character — never put a literal
  `@` in a label string.

## Display currency

All portfolio and watchlist market values (entry price, current price, P&L
amounts) are shown in the operator's configured display currency.

- **Startup**: the app calls `GET /api/settings/currency` to load the active
  currency from the backend. On network failure it falls back to EUR.
- **Live switching**: Settings → "Währung" / "Currency" lets the operator
  choose a display currency without a page reload. The choice is persisted via
  `PUT /api/settings/currency` (accepted values: `EUR`, `USD`, `GBP`, `CHF`).
- **Default**: EUR.
- **FX conversion**: the backend converts stored USD prices to the target
  currency at the current FX rate (Yahoo Finance `/v8/finance/chart/{from}{to}=X`,
  result cached per session) and stamps each watchlist/portfolio item with its
  effective `currency` code. The frontend renders the value using
  `Intl.NumberFormat` with that code — it never applies a client-side conversion.
- **Native original price**: when a price was stored in a currency that differs
  from the display currency, the original pre-conversion value is shown in
  parentheses after the converted amount — e.g. `1.147,70 € (urspr. $1,247.50)`.
  The parenthetical is hidden when the native currency equals the display currency
  (no conversion needed). This applies to all converted-price surfaces: watchlist
  current price, portfolio entry/current price, and verdict current price.
- **LLM costs**: agent run costs are always shown in USD regardless of the
  display-currency setting (Vistierie's cost ledger is USD-denominated).

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
BackLink + PageHead (bat-glyph eyebrow "Strigoi · {focus}", mono agent name, sub line
with StateDot + localized state label (jagt/ruht/pausiert/Budget erreicht) + next-run
summary). Four `.stat-tile`s from real fields only — prey per hunt (`avgPreyPerHunt`),
hit rate (`hitRate90d` with numerator/denominator foot), hunts (`huntsThisMonth` of
`scheduledHuntsThisMonth`), tier (`configuration.tier` with cron foot); no fabricated
return metric. Two-column `verdict-grid`: left stack = "Letzter Lauf · Trace" card
(RunTrace component rendering `recentRuns[0].trace`, with run meta ranAt/model/prey/cost)
+ "Jüngste Beute" feed (PreyCard per `recentPrey`, empty state when none); right
sticky aside = "Konfiguration" kv-list (cron, next run, tier, allowed models, daily/
monthly budget used vs cap, primary/fallback provider, disabled flag). No trigger/pause
action is rendered — no such write endpoint exists in v1, so the prototype's button is
intentionally omitted rather than faked. **RunTrace** (`components/common/RunTrace.vue`):
maps `TraceEvent.type` → start (▼) / end (▲) crimson event rows, llm-call highlighted
row, info/other plain rows.

## Implementation status (Etappe 11)

**View 4 — Watchlist** (`/watchlist`): **Tracking-only** — it shows watched candidates
(items with `entryPrice == null`); held positions live in the **Portfolio** view, not
here. Wired to the real API (`POST`, `PATCH`, `DELETE /api/watchlist/{id}`).
Master/detail layout (`.watch-grid`, 420px list pane / fluid detail pane). The view
scopes its data to `trackingItems = items.filter(i => i.entryPrice == null)`. Left pane:
search input (filters ticker/company; when the filter is empty and the query is a ticker
not already tracked, an "‚{symbol}' hinzufügen" CTA opens the add dialog prefilled with
that symbol), two tabs (**Alle** / **Aktuelle Alarme** — counts derived from tracking
items: all = `trackingItems.length`, alerts = `alerts.length > 0`), an add-to-watchlist
dialog (creates a `TRACKING` item; no tag choice), and `.watch-row` rows showing ticker,
company, price, day change (pos/neg color), status dot (calm→positive, elevated→warning,
alert→danger), and an owner badge; own rows have a delete control, foreign rows are
read-only. Right pane: header (mono ticker, company, tracked-since, live quote), the
Daywalker alert feed (the `AlertRow` atom, level elevated→warning / info→info /
neutral→neutral), and the linked-verdict card (when `verdictId` is set). Selected state is
a local `ref<string | null>` initialized to the first tracking item on load (auto-selection
suppressed on mobile so the list is the entry point; see the mobile drill-in under
"Responsive layout"). Position management (entry/size/P&L) lives entirely in the Portfolio
view.

##### Compare mode

The watchlist view has a `Liste | Vergleich` toggle. In **Vergleich** mode you
pick another user (from the owner dropdown — populated from the owners present
in the shared watchlist) and the view groups tickers into three buckets:

- **Beide** — tickers you both track. The shared market price is shown once;
  each user's status and alert count are listed per-user, since those differ
  between users. (Compare is tracking-only — positions are excluded.)
- **Nur ich** — tickers only you watch.
- **Nur \<user\>** — tickers only the other user watches.

The comparison is computed entirely in the browser from `GET /api/watchlist`
(which already returns every user's items with an `owner` field) — there is no
dedicated compare endpoint. The toggle is disabled when no other user keeps a
watchlist.

##### Portfolio and exit signals

**Portfolio** (`/portfolio`): lists **your own** held positions — the watchlist
items that carry an `entryPrice` and a `shareCount` — as `PositionRow` cards. The
portfolio is user-scoped: it shows only positions belonging to the logged-in user,
backed by `GET /api/portfolio` (not `GET /api/watchlist`). In contrast, the
Watchlist remains the shared, collaborative board across all users.

Each row shows the ticker, company, entry/size/current numbers, a live P&L readout
(`(currentPrice − entryPrice) / entryPrice × 100`, colored pos/neg), the thesis
status, and **Groparul's latest exit-signal badge** (`SELL` / `TRIM` / `HOLD`, or
"kein Signal" when none has fired). Exit signals are likewise scoped to the current
user. You add a position by symbol via the `PositionDialog` (the new item is
created with the `HELD` tag, then its entry/size are PATCHed in), edit a
position's entry/size, or clear it (the clear control confirms, then PATCHes the
position fields back to `null`). Rows that have a signal are clickable and route
to the exit detail.

Positions live in exactly one place: the Portfolio holds them (`entryPrice != null`),
the Watchlist holds tracked candidates (`entryPrice == null`). The two surfaces are
fully separated.

**Exit Signal Detail** (`/exit-signal/:id`): the deep-read for one signal. It
renders the `action` badge, the full `rationale` prose, the `firedRules[]` as a
list with plain-text explanations (`exitSignal.rules.<rule>` i18n keys, falling
back to the raw rule key), the `thesisStatus` (INTACT / WEAKENING / INVALIDATED),
the `confidence` as a `ConfidenceBar`, the position context (entry / size /
current / P&L), and — when the underlying position carries a `verdictId` — a link
through to the verdict detail. A back control (`exit-back`) returns to
`/portfolio`.

**View 11 — Morning Report** (`/report`): Fully implemented and wired to `GET /api/morning-report`.
A read-only daily digest fed by Groparul's projection of all held positions.
(The view and endpoint always show **all** held positions — SELL, TRIM, and HOLD;
only the scheduled Telegram digest is actionable-only, staying silent on all-HOLD days.)
Each position renders as a `.report-row` card showing:

- **Action pill** (`SELL` / `TRIM` / `HOLD`) — colour-coded crimson / gold / ash.
- **Symbol and company name**.
- **Metric bar**: active stop, next +2R target, current close price, distance-to-stop (%).
  When a position's trailing stop has risen above its static +2R target, the target column shows `✓ übertroffen` ("✓ exceeded") instead of a target value below the stop.
- **Rationale** — short Groparul prose for the recommended action.
- **Order ticket** (`OrderTicketCard`, `data-testid="order-ticket"`) — side, shares, limit
  reference, stop, and target rendered read-only. The ticket is purely informational:
  Dracul places no orders.

A read-only note at the top of the view makes the informational intent explicit
("Nur informativ — Dracul platziert keine Orders." / "Informational only — Dracul places
no orders."). When there are no held positions the view renders an empty state
(`data-testid="report-empty"`).

The view is reachable from the primary nav ("Report") at route `/report`
(Vue Router name `morning-report`). It is the intended morning entry point after
checking the Chronicle dashboard.

**View 5 — Pattern Library** (`/patterns`): Fully implemented and wired to the real API;
approve/reject/defer/deactivate all call `PATCH /api/patterns/{id}`.
Single-pane max-width 960px. Pending section: Voievod-proposed lesson cards with gold
left border, evidence counts, and Approve/Reject/Defer buttons (stubs). A pending card's
"Unterstützende Fälle ansehen →" link opens the **`PatternCasesDialog.vue`** modal, which
loads `GET /api/patterns/{id}/cases` and lists the supporting cases in a table
(ticker / company / anomaly / date / outcome / return); the dialog is full-screen on mobile.
Active section: filterable by Strigoi chip (derived from unique `appliesToStrigoi` values);
each row expands to show full pattern text and Deactivate button (stub). Expand state uses the
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

The seven top-level nav destinations (Chronicle, Watchlist, Portfolio, Report,
Pattern Library, Backtest, Settings) are available from both the desktop top-bar
and the mobile bottom tab bar. Deep-linked views (Verdict Detail, Strigoi Detail,
Prey Detail) are not in the nav but are reachable via in-app links.

- **Chronicle** is the home page. Most navigation starts here.
- **Verdict Detail** and **Strigoi Detail** are deep-linked from Chronicle items.
  Strigoi names in VerdictCard sublines are clickable links. **Prey Detail**
  (`/prey/:id`) is resolved client-side from the chronicle store.
- **Watchlist** receives items via the "Track on Watchlist" action in Verdict
  Detail (button rendered, not yet wired).
- **Report** is the daily Morning Report — the intended first stop each morning
  to review Groparul's stop/target/action summary for every held position.
- **Pattern Library** is reviewed periodically when the Voievod proposes new patterns.
- **Backtest** is a reference view; the compute engine is deferred to Stufe 5.
- **Settings** holds utility config plus the admin-only embedded Schatzkammer
  (Vistierie cost panel).

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

**View 6 — Vistierie (Schatzkammer)** (`GET /api/vistierie`): Fully implemented with real
backend data. In the Chronicle redesign it became an **embeddable** component
(`VistierieView` with an `embedded` prop) hosted admin-only inside Settings — there is no
longer a standalone `/vistierie` route. Layout: two stat tiles (month spent/cap, avg/day —
no prey/cost-per-prey tiles since `VistierieData` carries no such fields), a tier-budget
ledger (gold/crimson fills by utilisation) plus a month-total row, an agent-spend list
(`SpendBar` per agent), and a hand-rolled inline-SVG `LineChart` for the 30-day daily-spend
trend (replacing the former ApexCharts area chart). Backend derives tier budgets from
`application.yaml` config and aggregates provider costs from `VistierieClient`.

**View 7 — Backtest** (`/backtest`): View structure complete, backtest engine deferred to
Stufe 5 — Run button is disabled, data is hardcoded. Config panel: strigoi chip-group
(multi-select), date range with preset chips, universe radio group, disabled "Run Backtest"
button. Three recent backtest cards are clickable and switch the results section. Four
result tabs: Overview (4 stat cards), Trades (8 simulated trades), Equity Curve (inline-SVG
`LineChart` dual-series vs SPY), Comparison (strategy vs SPY table).

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

#### Agent config

Lists every Dracul agent with its runtime config (tier, schedule, next run,
today's budget usage, provider). Each row also exposes an **Edit** action that
opens a modal to edit the agent's prompt and its tool selection (with an
optional per-tool description override). Under an **Advanced** disclosure the
modal edits model purpose, schedule, max turns and max run seconds, plus an
**enabled** toggle and a **Reset to default** action (which persists
immediately). Note that `enabled` (whether the agent is registered with
Vistierie at all) is distinct from the row's **pause / resume** (which only
skips scheduled runs while keeping the registration). The remaining row config
(tier, next run, today's budget usage, provider) stays read-only — it comes
from code (config-as-code). Budgets are edited in the separate Budgets section;
multi-user agent ownership is Phase 2.

#### Data sources

Live health of each market-data source (EDGAR, Yahoo, Finnhub, Wikipedia):
status chip (ok / rate-limited / error / not-configured / timeout), HTTP status,
latency, which Strigoi use it, and the free-tier rate-limit note. Probed on open
(cached ~60s) with a manual **Re-check**. Probing is server-side, one minimal
request per source, concurrent with a 4s timeout — it never hammers a source.

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

**Playwright E2E tests** (`chronicle/e2e/`): 22 spec files covering all views, navigation smoke tests, plus `responsive.spec.ts` (mobile shell + Watchlist drill-in, run at a 390×844 viewport via a file-level `test.use`) and six `responsive-*.spec.ts` regression specs (top-bar controls at narrow desktop, portfolio/exit-signal/settings mobile layout, backtest breakpoint, detail-title gap). Includes `report.spec.ts` (8 tests for the Morning Report view: container visibility, all three mock positions, order tickets, German read-only note, and per-position action pills). Tests run against `VITE_MOCK=true` (no backend required). Chromium only.

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
history) is resolved synchronously via `AgoraMarketData` (Agora `get_quote` /
`get_ohlc` over MCP).

**Watchlist prices are server-refreshed in the background.** A
`WatchlistPriceRefresher` (`@Scheduled`, US market hours) updates
`watchlist_items.current_price` / `day_change_percent` in place every minute
during the session. `GET /api/watchlist` serves these stored values — no
per-load market-data call is made on read.

`GET /api/chronicle` now filters DISMISS-ed verdicts by default. Pass
`?includeDismissed=true` to see them.

Frontend wiring (Decision buttons, Notes textarea, Add-to-Watchlist) is deferred
to a follow-up etappe.
