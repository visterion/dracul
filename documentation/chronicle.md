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
| 5 | Watchlist | `/watchlist` | Tracking-only monitoring of watched candidates (held positions live in Depots now) | Medium | ✅ Etappe 11 |
| 6 | Pattern Library | `/patterns` | Approve Voievod lessons, view active patterns | Low | ✅ Etappe 11 |
| 7 | Backtest | `/backtest` | Historical validation of Strigoi strategies | High | ✅ Etappe 13 |
| 8 | Settings | `/settings` | Providers, budgets, agent config, notifications; embedded Schatzkammer (admin-only) | Variable | ✅ Etappe 11 |
| 9 | Exit Signal Detail | `/exit-signal/:id` | Full rationale, fired rules, thesis status, position context (resolved by symbol against depot-1) | Low (prose) | ✅ |
| 10 | Morning Report | `/report` | Daily morning report — per-position stop, +2R target, current price, distance-to-stop, and a read-only order ticket | Medium | ✅ |
| 11 | Depots | `/depots` | Trade-Republic-style live broker overview: summary bar (Σ equity, day change, total cash), one section per depot (header with provider/environment/probe status/"Stand:" freshness, headline value + day change + P&L, cash/invested/buying-power stats, performance chart with 1T/1W/1M/1J/Max ranges, allocation bar, positions table, orders), plus the operator-analytics Calibration card | High | ✅ Task C2 / A9 |
| 12 | Depot Position Detail | `/depots/:connection/:symbol` | Trade-Republic-style instrument page: header (price + selected-timeframe change), 1T/1W/1M/1J/Max chart with dotted baseline, stat tiles, open orders, profile description, and horizontally scrollable News/Ereignisse/Insights/Finanzen card rows | High | ✅ Task C3 |

> **Portfolio retired (Task A9, depot-as-SSOT).** The manual, watchlist-HELD-based
> "Portfolio" view (`/portfolio`, backed by `GET /api/portfolio`) has been removed.
> `/portfolio` now redirects to `/depots`, which is the operator's single source of
> truth for held positions — reading live positions from `GET /api/depots` instead
> of watchlist rows with a manually entered `entryPrice`/`shareCount`. The nav item
> is gone; `/depots` covers both destinations.

> **Vistierie (Schatzkammer):** Vistierie no longer has a standalone route. It is an
> embeddable component (`VistierieView` with an `embedded` prop) hosted admin-only
> inside the Settings view as the pinned "Schatzkammer · Vistierie" section. The legacy
> `/vistierie` path (and any unmatched path) is caught by the catch-all route and
> redirected to `/`.


## Chronicle feed: filtering, day grouping, chunking

The individual-prey feed on the Chronicle view (`/`) groups items by calendar
day (newest day first) and sorts each day's items by confidence descending
(`buildPreyGroups()` in `src/utils/preyGroups.ts`). Day labels are localized —
"Heute"/"Today", "Gestern"/"Yesterday", then a full date for older days.

- **Filter state lives in the URL** (`src/utils/filterQuery.ts`): the active
  filter round-trips through the query string as `?filter=high` (high
  confidence) or `?class=<AnomalyType>` (e.g. `?class=PEAD`); no query param
  means "all". This makes a filtered view linkable/bookmarkable/shareable and
  survives a page reload.
- **Chunked rendering**: `visibleGroupCount()` renders whole day-groups up to
  a ~30-card budget, then an "Ältere Beute anzeigen" / "Show older prey" button
  (`data-testid="show-older"`) reveals one more day-group per click. The reveal
  count persists per-session (`sessionStorage`, key `chronicle:extraGroups`)
  so it survives a reload but resets whenever the active filter changes (a new
  filter starts from the same ~30-card first page).
- **Mobile filter sheet**: below the 960px breakpoint the desktop filter rail
  (aside) is replaced by a floating filter button (`data-testid="filter-fab"`,
  shows the active filter count) that opens **`FilterSheet.vue`** — a
  bottom-sheet modal (backdrop + slide-up panel, Escape-to-close, body-scroll
  lock while open) listing the same filter chips (All / High confidence /
  per-anomaly-class) plus the Brood mini-profiles, sharing the same counts
  computed from `store.prey` as the desktop rail. Selecting a chip applies the
  filter and closes the sheet.

## Prey archive toggle (Chronicle view)

The Chronicle view (`/`) has an **archive toggle** (`data-testid="chronicle-archive-toggle"`,
labelled "Archivierte Beute anzeigen" / "Show archived prey") above the feed. It is
**off by default**, matching the backend default: `GET /api/chronicle` without
`includeArchived` returns only *active* prey (horizon not yet expired). Flipping the
toggle re-fetches via `useChronicleStore().load(true)`, which calls
`getChronicle(includeArchived=true)` and shows prey whose horizon has expired as well
(see `documentation/api.md`). The store's `includeArchived` ref is the single source of
truth — the toggle and the fetched data can never disagree, since toggling always
triggers a fresh load with the new flag rather than filtering client-side.

## Watchlist provenance badge

Every watchlist row shows a **provenance badge** (`WatchlistSourceBadge.vue`,
`data-testid="wl-source-badge"`, a muted `TagPill`) next to the ticker, reflecting the
item's `source` field from the backend (`watchlist_items.source`, see
`documentation/architecture.md`): `seed` (bootstrap-seeded row), `manual` (operator
`POST /api/watchlist`), `verdict` (added by reference to a verdict), or `agent:<name>`
(added by an autonomous agent, e.g. renfield proposals) — rendered as "hinzugefügt von
{name}" via an i18n placeholder rather than a fixed label per agent. Unknown values fall
back to the raw string. Labels live under `watchlist.source.*` in
`src/i18n/locales/{de,en}.ts`.

## Toast feedback

`useToast()` (`src/composables/useToast.ts`) is a module-level singleton
outlet rendered once by `AppToast.vue` in the app shell. Any view can call
`toast.show(message, { type: 'success' | 'error' })`; toasts stack and
auto-dismiss after `TOAST_DISMISS_MS` (4s). Used for the manual hunt trigger
(Strigoi Detail, Settings agent-config row) and other fire-and-forget
mutations to confirm success or surface a backend error message without a
blocking dialog.

## Live alert panel

A top-bar indicator (🔔 with a connection dot + unread badge) opens a dedicated
live-alert panel. The panel subscribes to `GET /api/events` over SSE and lists
incoming Daywalker alerts (severity, symbol, trigger, thesis) newest-first, with
a connection status. Active only against a real backend (disabled in mock mode).

The unread indicator (`useLiveAlertsStore`, `data-testid="live-unread"`) only
renders while `unread > 0`: a plain dot for exactly one unread alert, a
numeric badge for two or more. Opening the panel (`markRead()`) resets
`unread` to zero and clears the indicator — it is purely a "something arrived
since you last opened the panel" signal, not a persistent count tied to
alert-read state in the backend.

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
  (7 destinations: Chronicle, Watchlist, Depots, Report, Pattern Library, Backtest, Settings),
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

## Instrument overlay

Nearly every rendered ticker across Chronicle is clickable and opens a
shared, global **instrument overlay** with a live quote and info panel for
that symbol — without navigating away from the current view. Two spots
intentionally stay raw text instead of a `TickerButton`: `DepotPositionsTable`
rows (the row itself already `@click`-navigates to the position detail — a
nested ticker button would fight that navigation) and the `WatchlistView`
detail-pane header/verdict label (it is context for the already-selected row,
not a separate navigation target).

- **`TickerButton.vue`** (`src/components/instrument/`) is a small wrapper:
  it renders the symbol as an inline `<button>` (inherits the caller's
  `class`, so existing ticker styling like `.vc-ticker`/`.mono` is
  unchanged) and calls `useInstrumentOverlayStore().open(symbol)` on click
  or Enter/Space. It stops the event (`@click.stop`, `@keydown...stop.prevent`)
  so it never triggers a surrounding row/card's own click handler (e.g. a
  `VerdictCard`'s "open verdict" or a watchlist row's "select row").
- **`useInstrumentOverlayStore`** (`src/stores/instrumentOverlay.ts`) holds
  a single `openSymbol: string | null`; `open(symbol)` / `close()` toggle it.
- **`InstrumentOverlay.vue`** is mounted once in `App.vue` (present on every
  route) and renders as a `v-dialog` bound to `store.openSymbol != null`. It
  shows the symbol, a live header (name/price/change, emitted up from
  `InstrumentInfoPanel`), and — if the symbol is a current depot holding
  (`useDepotsStore().findHolding`) — a banner linking to that position's
  detail view; clicking the banner closes the overlay first. Escape/backdrop
  closes only this dialog.

`TickerButton` is wired into every ticker-bearing spot: `VerdictCard`,
`PreyCard`, `WatchlistView` rows, `WatchlistCompare` (all three buckets),
`MorningReportView`, `DepotSection` (order rows), `OrderTicketCard`,
`PatternCasesDialog` (case table), `LiveAlertPanel`, and the ticker headings
on `VerdictDetailView`, `PreyDetailView`, `ExitSignalDetailView`.

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

All depot and watchlist market values (entry price, current price, P&L
amounts) are shown in the operator's configured display currency.

- **Startup**: the app calls `GET /api/settings/currency` to load the active
  currency from the backend. On network failure it falls back to EUR.
- **Live switching**: Settings → "Währung" / "Currency" lets the operator
  choose a display currency without a page reload. The choice is persisted via
  `PUT /api/settings/currency` (accepted values: `EUR`, `USD`, `GBP`, `CHF`).
- **Default**: EUR.
- **FX conversion**: the backend converts stored USD prices to the target
  currency at the current FX rate (Yahoo Finance `/v8/finance/chart/{from}{to}=X`,
  result cached per session) and stamps each watchlist/depot item with its
  effective `currency` code. The frontend renders the value using
  `Intl.NumberFormat` with that code — it never applies a client-side conversion.
- **Native original price**: when a price was stored in a currency that differs
  from the display currency, the original pre-conversion value is shown in
  parentheses after the converted amount — e.g. `1.147,70 € (urspr. $1,247.50)`.
  The parenthetical is hidden when the native currency equals the display currency
  (no conversion needed). This applies to all converted-price surfaces: watchlist
  current price and verdict current price.
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
(items with `entryPrice == null`); held positions live in the **Depots** view now
(the manual Portfolio view was retired in Task A9), not here. Wired to the real API (`POST`, `PATCH`, `DELETE /api/watchlist/{id}`).
Master/detail layout (`.watch-grid`, 420px list pane / fluid detail pane). The view
scopes its data to `trackingItems = items.filter(i => i.entryPrice == null)`. Left pane:
search input (filters ticker/company; when the filter is empty and the query is a ticker
not already tracked, an "‚{symbol}' hinzufügen" CTA opens the add dialog prefilled with
that symbol), two tabs (**Alle** / **Aktuelle Alarme** — counts derived from tracking
items: all = `trackingItems.length`, alerts = `alerts.length > 0`), an add-to-watchlist
dialog (creates a `TRACKING` item; no tag choice), and `.watch-row` rows showing ticker,
company, price, day change (pos/neg color), and status dot (calm→positive, elevated→warning,
alert→danger). Rows are grouped by owner via `groupByOwner()` (`lib/watchlistDisplay.ts`):
own items render first and unlabelled, foreign items follow under a `.watch-owner-sep`
separator ("von {owner}" / "by {owner}", one per foreign owner, alphabetical) — no row
carries the raw owner e-mail anymore. Own rows have a delete control, foreign rows are
read-only. Right pane: header (mono ticker, company, tracked-since, live quote), the
Daywalker alert feed (the `AlertRow` atom, level elevated→warning / info→info /
neutral→neutral), and the linked-verdict card (when `verdictId` is set). Selected state is
a local `ref<string | null>` initialized to the first tracking item on load (auto-selection
suppressed on mobile so the list is the entry point; see the mobile drill-in under
"Responsive layout"). Held-position P&L lives entirely in the Depots view now
(see "Depots and exit signals" below) — the Watchlist is tracking-only.

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

##### Depots and exit signals (Portfolio retired, Task A9)

The manual "Portfolio" view is gone; `/portfolio` redirects to `/depots`. Held
positions are read live from `GET /api/depots` (depot-1) — see the "Depots" view
row above and `documentation/api.md` § Depots. The Watchlist remains the shared,
collaborative tracking board across all users, unaffected by this change (a
watchlist row's `entryPrice`/`shareCount` are legacy fields; new held positions
are not created through the watchlist anymore).

**Exit Signal Detail** (`/exit-signal/:id`): shows the fired signal's rationale,
rules, thesis status, and confidence, plus a **Position** section resolved by
**symbol** against every connected depot's positions (`ExitSignalDetailView.vue`
scans `GET /api/depots`'s `depots[].positions[]` for a `symbol` match) — not by
the legacy `watchlistItemId`, which gropar's depot-sourced signals never
populate. The back link returns to `/depots`.

**Calibration card** (`CalibrationCard.vue`, `data-testid="calibration-card"`):
sits below the depot section on the Depots view (moved here from the retired
Portfolio view in Task A9). Self-fetching (loads its own data on mount,
independent of the depot list's load lifecycle) from the read-only
operator-analytics endpoints added in the executor-sim-completion backend work:
`GET /api/executor/calibration` and `GET /api/executor/behavior`. Three blocks,
tables and stat chips only — no charts:

- **Brier score**: the executor's overall Brier score plus a per-hunter table
  (agent / n / Brier). A unit whose sample size is `insufficient` (`n < 30`)
  renders a muted `TagPill` ("insufficient data (n=X < 30)") in place of the
  score, both for the executor line and for individual hunter rows. Under the
  executor row, the reliability bucket table
  (`data-testid="calibration-buckets"`, columns range / n / Ø predicted /
  observed win rate) renders the executor's `buckets[]` — only when the sample
  is sufficient and at least one bucket is non-empty; per-hunter buckets are
  intentionally not rendered to keep the card compact.
- **Veto precision**: one row per veto `reason_code` — n, skipped count, mean
  hypothetical R at 20d/60d, and stopped-out % — followed by the three fixed
  `caveats` strings as a footnote list (`data-testid="calibration-caveats"`).
- **Behavior**: a stat-tile grid for hard-exit latency (max/p95), whipsaw
  counts (re-entry within 10d, roundtrip under 5d), and slippage (mean/worst).

When the hunters table or the veto-precision table has no rows, a single muted
placeholder row is rendered instead (`calibration-hunters-empty` /
`calibration-veto-empty`).

Wire types live in `api/types.ts` as `ExecutorCalibration` / `ExecutorBehavior`
and mirror the backend's snake_case JSON keys verbatim (e.g. `reason_code`,
`mean_hypothetical_r_20d`) rather than the camelCase convention used elsewhere
in `types.ts` — this endpoint pair is the one place the wire format itself is
snake_case (see `documentation/api.md`). All numeric fields are non-nullable:
the backend serializes Java primitives (an empty sample yields zeros plus
`insufficient: true`, never `null`).

**Exit Signal Detail** (`/exit-signal/:id`): the deep-read for one signal. It
renders the `action` badge, the full `rationale` prose, the `firedRules[]` as a
list with plain-text explanations (`exitSignal.rules.<rule>` i18n keys, falling
back to the raw rule key), the `thesisStatus` (INTACT / WEAKENING / INVALIDATED),
the `confidence` as a `ConfidenceBar`, and the position context (entry / size /
current / P&L) resolved by **symbol** against `GET /api/depots` (Task A9 — the
legacy `watchlistItemId`-keyed lookup and the verdict-link-through are gone,
since depot positions carry no `verdictId`). A back control (`exit-back`)
returns to `/depots`.

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

The seven top-level nav destinations (Chronicle, Watchlist, Depots, Report,
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

**Playwright E2E tests** (`chronicle/e2e/`): 22 spec files covering all views, navigation smoke tests, plus `responsive.spec.ts` (mobile shell + Watchlist drill-in, run at a 390×844 viewport via a file-level `test.use`) and five `responsive-*.spec.ts` regression specs (top-bar controls at narrow desktop, exit-signal/settings mobile layout, backtest breakpoint, detail-title gap — the Portfolio-specific mobile-layout spec was removed with the view in Task A9). Includes `report.spec.ts` (8 tests for the Morning Report view: container visibility, all three mock positions, order tickets, German read-only note, and per-position action pills) and `calibration.spec.ts` (8 tests for the Depots view's Calibration card, moved here from the retired Portfolio view in Task A9: container visibility, executor/hunter Brier scores, the reliability bucket table, the insufficient-data chip, veto-precision rows, the caveats footnote list, and behavior stat tiles). Tests run against `VITE_MOCK=true` (no backend required). Chromium only.

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

## Depots view (Task C2)

`DepotsView.vue` (`/depots`) is the operator's live-broker view of held
positions (the manual Portfolio view was retired in Task A9, and `/portfolio`
redirects here): one `DepotSection` per connected broker
(`useApi().getDepots()`), Trade-Republic-dense.

- **Summary bar** (always shown, even with a single depot): left side shows
  Σ equity across depots + total day change (colored, click to toggle);
  right corner shows total cash, `data-testid="depots-total-cash"`.
- **Per-depot section** (`DepotSection.vue`): header with id, provider chip,
  paper/live badge, probe status, and a relative "Stand: …" timestamp
  (`useRelativeTime`) that gets a `stale` CSS class once older than 15
  minutes (`isStale()` in `src/lib/depotDisplay.ts`); headline equity + day
  change + P&L; a cash/invested/buying-power `StatTile` row; a performance
  `LineChart` with 1T/1W/1M/1J/Max range buttons (default 1M, fetched via
  `getDepotChart`); a single stacked allocation bar built from each
  position's `weightPct`; `DepotPositionsTable.vue` (sortable columns:
  symbol, qty, avg entry, price, market value, P&L, day change, weight %);
  an orders list; and an inline error alert (`data-testid="depot-error"`)
  when `depot.error` is set — the rest of the section still renders
  whatever data the depot does have.
  - **Historie tab** (closed trades): a list of closed positions (Saxo) and historical Alpaca
    orders, fetched from `GET /api/depots/{connection}/history`. Each row shows the symbol,
    a status pill, realized P&L (when present, `CLOSED_POSITION` entries only), and a
    "broker-confirmed" badge. When Dracul can link an Alpaca order to an executor decision
    via `brokerOrderId`, a `why` block appears (Strigoi name, entry reasoning, exit reason,
    realized R) explicitly labeled as Dracul's non-authoritative interpretation, never as
    broker fact; entries with no link show a "not linkable" note instead. There is no
    client- or server-side sorting, and no entry-date/exit-date columns — the DTO carries no
    date fields. The response is always HTTP 200; a non-null `error` field (with `entries`
    empty) renders an inline alert explaining the broker fetch failure, replacing the
    empty-history text.
- **Abs/% toggle**: `useDisplayMode()` (`src/composables/useDisplayMode.ts`)
  is a module-level singleton ref persisted to
  `localStorage('dracul.depots.displayMode')`. Clicking *any* P&L/day-change
  number anywhere in the view (`data-testid="pnl-cell"`) flips the whole
  view between currency and percent display via the shared `fmtPl()`
  helper. Values with no quotes/no cost basis always render as an em dash
  (`—`) — never `0`.
- **Metric dropdown** above each positions table (`sinceBuy` vs `today`)
  highlights which of the P&L / day-change columns is the primary sort
  target; both columns stay visible regardless of the selection.
- Row click navigates to `depot-position-detail` (`/depots/:connection/:symbol`),
  the Trade-Republic-style instrument page described below (Task C3).
- **Known simplification**: the summary bar's Σ equity/cash sum raw numbers
  across depots even when depots use different account currencies (see
  `depotTotals()` in `src/lib/depotDisplay.ts`) — acceptable for the mock
  fixtures currently in play (one USD paper depot, one EUR live depot), but
  worth revisiting if/when true multi-currency consolidation is needed.

Nav: `useNavItems.ts` gained a `depots` entry (`ph-vault` icon). The `portfolio`
nav destination was removed in Task A9; `matchPrefixes` for `depots` now
includes `/portfolio` too, so the (redirected) legacy path still highlights the
Depots tab.

## Depot position detail view (Task C3)

`DepotPositionDetailView.vue` (`/depots/:connection/:symbol`, route name
`depot-position-detail`) is the Trade-Republic-style instrument page reached
by clicking a row in a `DepotSection`'s positions table.

- **Loading/error states**: a skeleton while `getDepotPosition` is in
  flight; a dedicated `data-testid="pd-notfound"` empty state when the
  backend returns 404 (`Position nicht gefunden`); a dedicated
  `data-testid="pd-brokerdown"` error state with a `data-testid="pd-retry"`
  button when it returns 503 — distinguished purely by matching on the
  thrown `Error`'s message (`"not found"` vs. anything else), same
  convention as `HttpApiClient`/`MockApiClient`'s `getDepotPosition`.
- **Header**: symbol, company name (via `displayName()`, hidden when the
  profile has no name beyond the ticker), current price (`position.price`),
  and the change **for the currently selected chart timeframe** — computed
  from the instrument chart's first→last point (not `position`'s own
  day-change field), colored and rendered through the shared `fmtPl()` +
  `useDisplayMode()` abs/%-toggle.
- **Timeframe chart**: 1T/1W/1M/1J/Max range buttons drive
  `getInstrumentChart(symbol, range)`, guarded by the same
  request-id-supersession pattern as `DepotSection`'s chart loader (a stale
  response from an abandoned range switch can never overwrite a newer one).
  `LineChart.vue` gained an optional `baseline?: number | null` prop — when
  set it draws a dotted reference `<line>` (`.baseline` in
  `src/styles/global.css`) at that y-value; this view passes the range's
  first chart value so the timeframe move reads visually against a fixed
  start point.
- **Stat tiles**: Position (market value), Performance seit Kauf
  (`unrealizedPl`/`unrealizedPlPct` via `fmtPl`), Stückzahl, Einstand (avg
  entry price), and a `data-testid="pd-asof"` "Stand: …" stamp that gets the
  `stale` class past 15 minutes (`isStale()`).
- **Open orders**: reuses the same row layout as `DepotSection`'s orders
  list, scoped to this symbol (`getDepotPosition`'s `orders` are already
  server-filtered).
- **Informationen**: the profile's `description` field (Finnhub-style
  passthrough object under `InstrumentInfo.profile`, shape otherwise
  `unknown`) — truncated at 240 chars with a "Mehr/Weniger anzeigen"
  toggle when longer. Absent when there's no description.
- **Horizontally scrollable info card rows** — a new generic
  `InfoCardRow.vue` (`title` prop + default slot,
  `overflow-x:auto`/`scroll-snap-type:x mandatory`, cards
  `scroll-snap-align:start`), instantiated four times:
  - **News**: headline/source/relative-publish-age, from `info.news.news[]`.
  - **Ereignisse**: one card per row in `info.earnings.earnings[]` (already
    server-filtered to this symbol), date badge + "in N Tagen"/"heute".
  - **Insights**: up to four cards — analyst consensus (majority vote across
    `buy`/`hold`/`sell`/`strongBuy`/`strongSell` in the latest
    `analystEstimates.recommendations[]` entry) with an optional price-target
    sub-line if the backend ever sends one; EPS estimate (latest
    `earningsEstimates.estimates[]` entry, `epsAvg` + low–high range);
    fundamental score (`fundamentalScore.score`); insider activity (buy/sell
    counts from `insiderActivity.transactions[]`, already server-filtered).
  - **Finanzen**: a mini key/value table from `fundamentals` (P/E, P/B,
    dividend yield) — only the fields actually present render a row; no
    YoY % since the mock/backend only ever sends a single snapshot, not a
    time series.
- **Defensive access to `unknown` sections**: every `InstrumentInfo` field
  beyond `earnings`/`insiderActivity`'s documented row shapes is typed
  `unknown`. The view never trusts the shape — small `asRecord`/`asArray`/
  `asNumber`/`asString` guards gate every field access, and **each section
  renders only when its computed data is non-empty**; a null, malformed, or
  failed section (including a rejected `getInstrumentInfo` call) simply
  disappears — it never throws or blanks the rest of the page.
- **Route param changes re-trigger every load**: a single
  `watch(() => [route.params.connection, route.params.symbol], …, { immediate: true })`
  drives `loadPosition()`/`loadChart()`/`loadInfo()` together, each guarded
  by its own monotonically-increasing request id — the known bug class from
  the 2026-07-10 review (a param-only route reuse not re-fetching) does not
  apply here.
