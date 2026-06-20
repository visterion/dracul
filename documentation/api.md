# REST API

The Chronicle frontend is the primary consumer of this API. All paths
are relative to the context root of `dracul-app`.

## Prey

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/prey` | List recent prey; query params: `strigoi`, `anomaly`, `from`, `to`, `minConfidence`, `page`, `size` |
| GET | `/api/prey/{id}` | Single prey detail (signals, risks, thesis, outcome if assessed) |

## Verdicts

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/verdicts` | List verdicts; query params: `from`, `to`, `minConsensus`, `page`, `size`, `includeDismissed` (default false) |
| GET | `/api/verdicts/{id}` | Single verdict with all contributing prey |
| PUT | `/api/verdict/{id}/decision` | Set/clear verdict decision (TRACK / INTERESTING / DISMISS / ACTED / null) |

### `VerdictDetail` response (SP-2 additions)

`GET /api/verdicts/{id}` returns the verdict with its contributing prey. `currentPrice`
and `currency` are the **converted display values**. Three additive native-currency
fields are included alongside them:

| Field | Type | Description |
|---|---|---|
| `currency` | `string` | Display currency code (ISO 4217) — the converted value's currency |
| `nativeCurrentPrice` | `number \| null` | Current price in the instrument's native currency (always sent when a price exists; equals `currentPrice` when native == display) |
| `nativeCurrency` | `string` | ISO 4217 code of the native currency (e.g. `"USD"`); equals `currency` when native == display. The UI hides the native parenthetical when this equals `currency` |

Conversion happens at read time in `VerdictController` via `VerdictCurrencyMapper`,
mirroring the watchlist path's `WatchlistCurrencyMapper`.
| POST | `/api/verdict/{id}/notes` | Append a note to a verdict timeline |
| GET | `/api/verdict/{id}/notes` | List notes for a verdict (DESC by createdAt) |

## Strigoi

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/strigoi` | Roster + last-run status (proxied from Vistierie) |
| GET | `/api/strigoi/{name}` | Single Strigoi: runs, stats, configuration |
| POST | `/api/strigoi/{name}/hunt` | Trigger manual one-off hunt (proxied to Vistierie) |

## Authentication

User-facing endpoints sit behind **Cloudflare Access**. Dracul verifies the
`Cf-Access-Jwt-Assertion` JWT (signature against the team JWKS + audience) and
derives the current user (email). Webhook endpoints (`/api/strigoi-*`,
`/api/voievod`, `/api/daywalker`) keep their machine bearer-token auth and are
exempt. In bypass mode (no Cloudflare config — local/dev) an `X-Dev-User` header
sets the user, else `default`.

### `GET /api/me`
Returns `{ "email": "<current user>" }`.

## Watchlist

### Watchlist (collaborative)
`GET /api/watchlist` returns **all users' items**, each with an `owner` field.
`POST` creates an item owned by the current user. `PATCH`/`DELETE`/`PATCH …/position`
require ownership — editing another user's item returns **403**.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/watchlist` | All active watchlist items for the current user |
| POST | `/api/watchlist` | Add ticker to watchlist; resolves market data synchronously |
| PATCH | `/api/watchlist/{id}` | Update tag (HELD/TRACKING) |
| PATCH | `/api/watchlist/{id}/position` | Set or clear the operator position (entry price + share count) |
| DELETE | `/api/watchlist/{id}` | Remove watchlist item |

### `PATCH /api/watchlist/{id}/position`

Records or clears the operator's position for a watchlist item. Both fields are
nullable — send `null` to remove the position.

Request body:
```json
{ "entryPrice": 142.50, "shareCount": 100 }
```

Constraints:
- `entryPrice`: `>= 0` or `null`
- `shareCount`: `>= 0` or `null`
- Both `null` clears the position (P&L section reverts to the "add position" state)
- 400 on negative values; 404 if the item id is unknown

Response (200): the updated `WatchlistItem`.

### `WatchlistItem` read model

`GET /api/watchlist` items include two nullable position fields:

| Field | Type | Description |
|---|---|---|
| `entryPrice` | `number \| null` | Operator-recorded entry price; null until a position is set |
| `shareCount` | `number \| null` | Operator-recorded share count (fractional shares supported); null until set |

Client-side P&L is derived as `(currentPrice − entryPrice) × shareCount`; the
backend stores only the raw inputs.

#### Display-currency and native-price fields (SP-2)

`currentPrice`, `entryPrice`, and `currency` are the **converted display values**
(in the operator's configured display currency). Four additive native-currency
fields are present alongside them (the UI hides each native parenthetical when the
native code equals the display `currency`):

| Field | Type | Description |
|---|---|---|
| `nativeCurrentPrice` | `number` | Current price in the instrument's native currency (equals `currentPrice` when native == display) |
| `nativeCurrency` | `string` | ISO 4217 code of the native currency (e.g. `"USD"`); equals `currency` when native == display |
| `nativeEntryPrice` | `number \| null` | Entry price in its native currency; null until a position is set (equals `entryPrice` when native == display) |
| `entryCurrency` | `string` | ISO 4217 code of the entry price's native currency; equals `currency` when native == display |

These fields are purely additive — existing consumers that ignore them are unaffected.

### `GET /api/portfolio`

Returns the current user's **held positions** — watchlist items that have both an
`entryPrice` and a `shareCount` set — with all prices currency-converted to the
operator's configured display currency. This endpoint is user-scoped: it returns
only the calling user's positions, not those of other users.

This is distinct from `GET /api/watchlist`, which returns the collaborative
all-users board (tracking candidates, all owners).

Response: `WatchlistItem[]` (same shape as `/api/watchlist`, filtered to held
positions of the current user).

## Exit Signals

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/exit-signals` | Returns the latest exit signal per HELD watchlist position for the **current user**; each entry includes `id`, `watchlistItemId`, `symbol`, `action` (SELL / TRIM / HOLD), `firedRules[]`, `gainLossPct`, `thesisStatus` (INTACT / WEAKENING / INVALIDATED), `rationale`, `confidence`, `vistierieRunId`, and `runAt` |

> **Note:** `GET /api/exit-signals` is scoped to the current user
> (`CurrentUserHolder.get()`). It was previously hardcoded to the user
> `"default"` — a bug that caused signals to be invisible after the legacy
> owner migration. Prod signals now correctly resolve against the primary user.

## Daywalker Alerts

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/alerts` | Recent alerts; query params: `instrument`, `severity`, `from`, `size` |
| POST | `/api/alerts/{id}/dismiss` | Mark alert as user-dismissed |

## Pattern Library

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/patterns` | All patterns; query params: `strigoi`, `status` |
| GET | `/api/patterns/{id}/cases` | Supporting cases (evidence) for one pattern, newest first |
| POST | `/api/patterns/{id}/approve` | Activate a `PENDING` pattern |
| POST | `/api/patterns/{id}/reject` | Reject with optional `reason` body |
| POST | `/api/patterns/{id}/defer` | Move back to pending for later review |
| DELETE | `/api/patterns/{id}` | Deactivate an `ACTIVE` pattern |

### `GET /api/patterns/{id}/cases`

Returns the supporting cases (historical anomaly occurrences) backing one
pattern, ordered by `occurredAt` DESC. `404` if the pattern is not the current
user's.

Response (200): `PatternCase[]`

| Field | Type | Description |
|---|---|---|
| `symbol` | string | ticker of the case instrument |
| `companyName` | string | company name |
| `anomalyType` | string | anomaly type the case exhibited |
| `occurredAt` | string | ISO instant the case occurred |
| `supported` | boolean | whether the outcome supported the pattern |
| `returnPercent` | `number \| null` | realized return of the case; null if unknown |
| `note` | `string \| null` | optional free-text annotation |

## Cost / Vistierie Proxy

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/cost` | Cost rollup view (proxied from Vistierie) |
| GET | `/api/cost/runs` | Paginated run history (proxied from Vistierie) |

## Backtest

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/backtest` | Submit a backtest job (`strigoi`, `from`, `to`, `universe`) |
| GET | `/api/backtest/{id}` | Poll backtest status and results |
| GET | `/api/backtest` | List recent backtest runs |

## Settings — Language

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/settings/language` | Return the current UI language |
| PUT | `/api/settings/language` | Update the UI language; returns new value, 400 on unsupported locale |

`GET /api/settings/language` response:
```json
{ "language": "de" }
```

`PUT /api/settings/language` request body:
```json
{ "language": "en" }
```

Response (200):
```json
{ "language": "en" }
```

Supported locales: `de`, `en`. Any other value returns HTTP 400 with an `error` field. On success, a `LanguageChangedEvent` is published internally so agent registrars can react.

## Settings — Agents

### `GET /api/settings/agents`

Returns one row per Dracul agent, aggregated from Vistierie:

| field | type | notes |
|---|---|---|
| `name` | string | agent name (e.g. `strigoi-merger`) |
| `role` | string | anomaly type for hunters; `reviewer` / `daywalker`; `hunter` fallback |
| `state` | string | `resting` / `hunting` / `running` / `paused` / `budget-hit` |
| `paused` | boolean | runtime pause state |
| `tier` | string\|null | model purpose; null if detail unavailable |
| `schedule` | string\|null | cron expression |
| `nextRunAt` | string\|null | ISO instant |
| `dailyUsedUsd` / `dailyBudgetUsd` | number | today's spend / cap (0 = uncapped) |
| `primaryProvider` | string\|null | e.g. `anthropic` |

### `PATCH /api/settings/agents/{name}`

Body `{ "paused": true|false }`. Pauses or resumes the agent's scheduled runs
via Vistierie and returns the updated row. `404` if the agent is unknown.
Pause/resume is **durable** — the Dracul registrars do not re-assert `paused`
on startup, so a paused agent stays paused across deploys.

### `GET /api/settings/agents/{name}/definition`

Returns the current (DB-stored) definition for a single agent.

| field | type | notes |
|---|---|---|
| `name` | string | agent name (e.g. `strigoi-echo`) |
| `prompt` | string | full system-prompt text |
| `schedule` | string | cron expression |
| `modelPurpose` | string | `routine` or `reasoning` |
| `enabled` | boolean | whether the agent is registered with Vistierie |
| `maxTurns` | integer | max LLM turns per run (positive, required) |
| `maxRunSeconds` | integer | run timeout in seconds (positive, required) |
| `tools` | array | list of `{ toolName, description }` tool bindings |

Returns `404` if the agent name is unknown.

### `PUT /api/settings/agents/{name}/definition`

Updates the stored definition for an agent. On success, re-registers the agent with Vistierie immediately (no restart required).

Editable fields: `prompt`, `schedule`, `modelPurpose`, `enabled`, `maxTurns`, `maxRunSeconds`, and the `tools` array (each entry: `toolName`, `description`).

Validation:
- `prompt` must be non-empty
- `schedule` must be a valid cron expression
- `modelPurpose` must be `routine` or `reasoning`
- `maxTurns` and `maxRunSeconds` must be positive integers within acceptable ranges
- every `toolName` in the `tools` array must exist in the `AgentToolCatalog`

Returns `400` on any validation failure (with an `error` field describing the problem), `404` if the agent name is unknown, `200` with the updated definition on success.

### `POST /api/settings/agents/{name}/definition/reset`

Discards any runtime edits and restores the agent's definition to the code default (as declared by its `AgentDefaultProvider` bean). The DB row is overwritten and the agent is immediately re-registered with Vistierie.

Returns `404` if the agent name is unknown, `200` with the restored definition on success.

### `GET /api/settings/agents/tools`

Returns the available tool catalog (every tool any agent could be bound to),
sorted by `toolName`. Feeds the agent-config edit UI's tool checklist.

| field | type | notes |
|---|---|---|
| `toolName` | string | catalog tool id (e.g. `fetch_recent_merger_candidates`) |
| `defaultDescription` | string | the tool's default description (used when a binding sets no override) |

## Settings — Data Sources

### `GET /api/settings/data-sources`

Query: `refresh` (boolean, default `false`). Actively probes each market-data
source (EDGAR, Yahoo, Finnhub, Wikipedia) with one minimal request and returns
their live health. Results are cached ~60s server-side; `refresh=true` bypasses
the cache. Each entry: `id`, `label`, `configured`, `status` (`ok` /
`rate_limited` / `error` / `not_configured` / `timeout`), `httpStatus`, `detail`,
`latencyMs`, `usedBy` (Strigoi names), `rateLimitNote`, `checkedAt`.

## Admin

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/admin/kill` | Flip the Vistierie kill switch for the `dracul` tenant |

## Live Updates (SSE)

`GET /api/events` is a Server-Sent-Events stream (`text/event-stream`). The
Chronicle frontend connects via `EventSource` and updates live without polling.
Unauthenticated, consistent with the read API (a browser `EventSource` cannot
send an `Authorization` header).

**v1 emits only `alert.new`** — a new Daywalker alert:

```text
event: alert.new
data: {"symbol":"AAPL","trigger_type":"PRICE_SPIKE","severity":"CRITICAL","thesis":"…","ts":"2026-06-04T18:00:00Z"}
```

The stream is generic; `verdict.new` and `strigoi.status` are planned and will
attach to the same stream once their sources exist. No replay / Last-Event-ID in
v1 — clients receive events from connect time; `EventSource` auto-reconnects.

## Error Responses

All error responses follow a uniform shape:

```json
{
  "error": "ERROR_CODE",
  "message": "human-readable description",
  "field": "fieldName (optional, for validation errors)"
}
```

Error codes and HTTP status:

| Error Code | Status | Description |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Input validation failed (e.g., invalid enum value) |
| `NOT_FOUND` | 404 | Requested resource does not exist |
| `MARKET_DATA_NOT_FOUND` | 422 | Symbol unknown to market data provider |
| `MARKET_DATA_UNAVAILABLE` | 502 | Market data provider unreachable |

## REST API (java-server, port 8080)

All endpoints are read-only (GET). No authentication in Stufe 3.

| Endpoint | Response | Description |
|---|---|---|
| `GET /api/chronicle` | `ChronicleData` | Morning feed: prey[], verdicts[], pendingPatterns[], alerts[] |
| `GET /api/status` | `SystemStatus` | Strigoi states, last verdict timestamp, daily cost |
| `GET /api/verdict/{id}` | `VerdictDetail` | Full verdict detail; 404 if not found |
| `GET /api/strigoi/{name}` | `StrigoiDetail` | Strigoi detail from Vistierie + recent prey from DB; 404 if unknown |
| `GET /api/watchlist` | `WatchlistItem[]` | All watchlist items with nested alerts and 30d price history |
| `GET /api/patterns` | `Pattern[]` | All patterns (PENDING + ACTIVE + REJECTED) |
| `GET /api/providers` | `LlmProvider[]` | LLM provider configurations from VistierieClient |

CORS: `http://localhost:5173` allowed on all `/api/*` paths.

## Strigoi-Insider Webhooks

These endpoints are called by Vistierie during a `strigoi-insider` agent run. Both require `Authorization: Bearer <STRIGOI_INSIDER_TOKEN>`. They are only registered when `STRIGOI_INSIDER_ENABLED=true`.

### `POST /api/strigoi-insider/tools/fetch-clusters`

Tool webhook — invoked mid-run by the LLM via Vistierie's tool dispatcher. Returns insider-buying clusters detected by Dracul's deterministic screener (≥3 distinct filers, 30-day window, total > $500k, Purchase transactions only).

Request body:
```json
{
  "run_id": "...",
  "tool_name": "fetch_recent_clusters",
  "input": { "lookback_days": 7 }
}
```

Response:
```json
{
  "output": {
    "clusters": [
      {
        "ticker": "...", "companyName": "...",
        "filers": ["..."],
        "windowStart": "2026-05-15", "windowEnd": "2026-05-25",
        "totalDollarValue": 1234567,
        "totalShares": 1000
      }
    ]
  }
}
```

### `POST /api/strigoi-insider/complete`

Completion webhook — invoked by Vistierie's `CompletionWebhookDispatcher` when the agent run finishes. Persists Prey when the run succeeded.

Headers: `Authorization: Bearer ...`, `X-Vistierie-Run-Id: <run-id>`.

Request body (shape per Vistierie's completion-webhook contract):
```json
{
  "run_id": "...",
  "agent_version": 1,
  "status": "succeeded",
  "started_at": "...",
  "finished_at": "...",
  "output": {
    "prey": [
      {
        "symbol": "...",
        "companyName": "...",
        "anomalyType": "INSIDER_CLUSTER",
        "confidence": 0.7,
        "thesis": "...",
        "signals": [],
        "risks": [],
        "horizon": "3m"
      }
    ]
  }
}
```

Returns 204 on success. If `status != "succeeded"` or no `output.prey` array, the endpoint acknowledges (204) without persisting and logs the run-id.

## Strigoi-Echo Webhooks

These endpoints are called by Vistierie during a `strigoi-echo` agent run (Post-Earnings-Announcement-Drift). Both require `Authorization: Bearer <STRIGOI_ECHO_TOKEN>`. They are only registered when `STRIGOI_ECHO_ENABLED=true`.

### `POST /api/strigoi-echo/tools/fetch-candidates`

Tool webhook — invoked mid-run by the LLM via Vistierie's tool dispatcher. Returns positive-surprise PEAD candidates from Dracul's deterministic screener (positive earnings surprise only, surprise ≥ 5%, current price ≥ $5).

Request body:
```json
{
  "run_id": "...",
  "tool_name": "fetch_recent_pead_candidates",
  "input": { "lookback_days": 7 }
}
```

Response:
```json
{
  "output": {
    "candidates": [
      {
        "symbol": "...", "companyName": "...",
        "reportDate": "2026-05-20",
        "epsActual": 1.65, "epsEstimate": 1.50,
        "surprisePercent": 10.0,
        "currentPrice": 190.5
      }
    ]
  }
}
```

### `POST /api/strigoi-echo/complete`

Completion webhook — invoked by Vistierie's `CompletionWebhookDispatcher` when the agent run finishes. Persists Prey when the run succeeded.

Headers: `Authorization: Bearer ...`, `X-Vistierie-Run-Id: <run-id>`.

Request body (shape per Vistierie's completion-webhook contract):
```json
{
  "run_id": "...",
  "agent_version": 1,
  "status": "succeeded",
  "started_at": "...",
  "finished_at": "...",
  "output": {
    "prey": [
      {
        "symbol": "...",
        "companyName": "...",
        "anomalyType": "PEAD",
        "confidence": 0.7,
        "thesis": "...",
        "signals": [],
        "risks": [],
        "horizon": "3m"
      }
    ]
  }
}
```

Returns 204 on success. If `status != "succeeded"` or no `output.prey` array, the endpoint acknowledges (204) without persisting and logs the run-id.

## Strigoi-Lazarus Webhooks

Called by Vistierie during a `strigoi-lazarus` agent run (Quality-at-52w-Low).
Both require `Authorization: Bearer <STRIGOI_LAZARUS_TOKEN>`; only registered when
`STRIGOI_LAZARUS_ENABLED=true`.

### `POST /api/strigoi-lazarus/tools/fetch-candidates`

Tool webhook. Returns watchlist names that are trading within `max-above-low`
(default 10%) of their 52-week low, together with Finnhub fundamentals. No input
args required.

Request: `{ "run_id": "...", "tool_name": "fetch_quality_at_low_candidates", "input": {} }`

Response:
```json
{ "output": { "candidates": [
  { "symbol": "ACME", "companyName": "Acme Corp",
    "currentPrice": 42.10, "week52Low": 39.50, "week52High": 78.00,
    "pctAboveLow": 0.066, "roaTtm": 4.0, "currentRatio": 1.8,
    "debtToEquity": 1.2, "grossMargin": 35.0, "netMargin": 8.0,
    "revenueGrowthYoy": 4.0, "epsGrowthYoy": 3.0, "priceToBook": 1.2,
    "peTtm": 11.0, "fcfPerShare": 2.10 }
] } }
```

### `POST /api/strigoi-lazarus/complete`

Completion webhook. Headers: `Authorization: Bearer ...`, `X-Vistierie-Run-Id: ...`.
On success (`status` = `done`) persists each `output.prey[]` entry as Prey with
`anomalyType=QUALITY_52W_LOW`, `discoveredBy=strigoi-lazarus`. Prey without a
`symbol` are skipped. Returns 204; non-success / empty prey acknowledged without
persisting.

## Daywalker Webhooks

These endpoints are called by Vistierie for the `daywalker` StreamingBee. Both
require `Authorization: Bearer <DRACUL_DAYWALKER_TOKEN>`. They are only registered
when `DRACUL_DAYWALKER_ENABLED=true`.

### `POST /api/daywalker/events`

Event-source webhook — polled by Vistierie on a cadence within the session
window. Runs deterministic detection over the active watchlist (no LLM) and
returns trigger events. Each event becomes the payload of one child run.

Request body (Vistierie → Dracul):
```json
{ "session_id": "<uuid>", "agent": "daywalker",
  "since": "2026-06-03T17:00:00Z", "now": "2026-06-03T18:00:00Z" }
```

Response:
```json
{ "events": [
  { "symbol": "ACME", "company_name": "Acme Corp",
    "trigger_type": "PRICE_SPIKE", "current_price": 106.0,
    "detail": { "price_change_pct": 0.06, "from_price": 100, "to_price": 106 } }
] }
```

Empty `events` → nothing spawned. A `(symbol, trigger_type)` within its cooldown
window is suppressed.

### `POST /api/daywalker/complete`

Completion webhook — invoked when a child run finishes. Persists an enriched
`daywalker_alerts` row when the run succeeded.

Headers: `Authorization: Bearer ...`, `X-Vistierie-Run-Id: <run-id>`.

Request body:
```json
{ "run_id": "...", "status": "succeeded",
  "output": { "symbol": "ACME", "trigger_type": "PRICE_SPIKE",
              "severity": "WARNING", "thesis": "...", "confidence": 0.6 } }
```

Returns 204. If `status != "succeeded"`, the symbol/trigger_type is missing, or
the symbol is not on the watchlist, the endpoint acknowledges (204) without
persisting and logs the run-id.

## Strigoi-Spin Webhooks

Called by Vistierie during a `strigoi-spin` agent run (spin-off forced-selling).
Both require `Authorization: Bearer <STRIGOI_SPIN_TOKEN>`; only registered when
`STRIGOI_SPIN_ENABLED=true`.

### `POST /api/strigoi-spin/tools/fetch-candidates`

Tool webhook. Returns recent Form-10-12B spin-off registrations (deterministic).

Request: `{ "run_id": "...", "tool_name": "fetch_recent_spinoff_candidates", "input": { "lookback_days": 60 } }`

Response:
```json
{ "output": { "candidates": [
  { "symbol": "SPN", "companyName": "Acme Spinco Inc", "formType": "10-12B",
    "filingDate": "2026-05-20", "filingUrl": "https://www.sec.gov/Archives/..." }
] } }
```

### `POST /api/strigoi-spin/complete`

Completion webhook. Headers: `Authorization: Bearer ...`, `X-Vistierie-Run-Id: ...`.
On success (`status` = `done`) persists each `output.prey[]` entry as Prey with
`anomalyType=SPINOFF`, `discoveredBy=strigoi-spin`. Prey without a `symbol` are
skipped. Returns 204; non-success / empty prey acknowledged without persisting.

## Strigoi-Merger Webhooks

Called by Vistierie during a `strigoi-merger` agent run (M&A arbitrage).
Both require `Authorization: Bearer <STRIGOI_MERGER_TOKEN>`; only registered when
`STRIGOI_MERGER_ENABLED=true`.

### `POST /api/strigoi-merger/tools/fetch-candidates`

Tool webhook. Returns recent DEFM14A / SC TO-T deal filings (deterministic, metadata-only).

Request: `{ "run_id": "...", "tool_name": "fetch_recent_merger_candidates", "input": { "lookback_days": 45 } }`

Input `lookback_days` range: 1–120; default 45.

Response:
```json
{ "output": { "candidates": [
  { "symbol": "TGT", "companyName": "Target Co Inc", "formType": "DEFM14A",
    "filingDate": "2026-05-28", "filingUrl": "https://www.sec.gov/Archives/..." }
] } }
```

### `POST /api/strigoi-merger/complete`

Completion webhook. Headers: `Authorization: Bearer ...`, `X-Vistierie-Run-Id: ...`.
On success (`status` = `done`) persists each `output.prey[]` entry as Prey with
`anomalyType=MERGER_ARB`, `discoveredBy=strigoi-merger`. Prey without a `symbol` are
skipped. Returns 204; non-success / empty prey acknowledged without persisting.

## Gropar Webhooks

Called by Vistierie during a `gropar` agent run (exit timing for HELD positions).
Both require `Authorization: Bearer <DRACUL_GROPAR_WEBHOOK_TOKEN>`. They are only
registered when `DRACUL_GROPAR_ENABLED=true`.

### `POST /api/gropar/tools/fetch-held-positions`

Tool webhook — invoked mid-run by the LLM via Vistierie's tool dispatcher. Returns
all HELD watchlist items with their entry price, share count, and current price.

Request: `{ "run_id": "...", "tool_name": "fetch_held_positions", "input": {} }`

Response:
```json
{ "output": { "positions": [
  { "symbol": "ACME", "companyName": "Acme Corp",
    "entryPrice": 100.0, "shareCount": 50, "currentPrice": 142.5,
    "unrealisedGainPct": 42.5,
    "indicators": {
      "chandelier_stop": 128.0,
      "ma_fast": 135.0, "ma_slow": 121.0, "ma_cross": "BULLISH",
      "week52Low": 89.0, "week52High": 155.0,
      "atr22": 4.2
    }
  }
] } }
```

### `POST /api/gropar/complete`

Completion webhook — invoked by Vistierie's `CompletionWebhookDispatcher` when the
agent run finishes. Persists exit signals to `dracul.exit_signals`.

Headers: `Authorization: Bearer ...`, `X-Vistierie-Run-Id: <run-id>`.

Request body:
```json
{ "run_id": "...", "status": "succeeded",
  "output": { "signals": [
    { "symbol": "ACME", "verdict": "SELL", "rationale": "...", "confidence": 0.8 }
  ] } }
```

Returns 204. If `status != "succeeded"` or the `output.signals` array is absent,
the endpoint acknowledges (204) without persisting and logs the run-id. SELL or
TRIM verdicts also trigger a best-effort Telegram push.

## Voievod Webhooks

Called by Vistierie during a `voievod` agent run (consensus synthesizer).
Both require `Authorization: Bearer <VOIEVOD_TOKEN>`; only registered when
`VOIEVOD_ENABLED=true`.

### `POST /api/voievod/tools/fetch-candidates`

Tool webhook — invoked mid-run by the LLM via Vistierie's tool dispatcher.
Returns the current consensus clusters: symbols flagged by ≥2 distinct Strigoi
whose prey are still open (`discoveredAt + horizon ≥ today`).

Request: `{ "run_id": "...", "tool_name": "fetch_consensus_clusters", "input": {} }`

Response:
```json
{
  "output": {
    "clusters": [
      {
        "symbol": "ACME",
        "companyName": "Acme Corp",
        "prey": [
          {
            "discoveredBy": "strigoi-insider",
            "anomalyType": "INSIDER_CLUSTER",
            "confidence": 0.75,
            "thesis": "...",
            "signals": [],
            "risks": [],
            "horizon": "3m",
            "discoveredAt": "2026-06-01T08:00:00Z"
          }
        ]
      }
    ]
  }
}
```

### `POST /api/voievod/complete`

Completion webhook — invoked by Vistierie's `CompletionWebhookDispatcher` when
the agent run finishes. Accepts agent output (`status` = `done` / `succeeded`).

For each emitted symbol Dracul re-derives the cluster from the database
(source of truth), computes the consensus score (noisy-OR:
`1 − ∏(1 − confidenceᵢ)`), and upserts the verdict. A verdict the user has
already decided (`decision` is set) is not overwritten. The current price is
fetched from the market-data adapter (failure is non-fatal). Returns 204.

Headers: `Authorization: Bearer ...`, `X-Vistierie-Run-Id: <run-id>`.

Request body:
```json
{
  "run_id": "...",
  "agent_version": 1,
  "status": "done",
  "started_at": "...",
  "finished_at": "...",
  "output": {
    "verdicts": [
      {
        "symbol": "ACME",
        "summary": "Two independent Strigoi flagged insider buying and PEAD drift..."
      }
    ]
  }
}
```

Returns 204. If `status` is not `done` / `succeeded`, or the `output.verdicts`
array is absent, the endpoint acknowledges (204) without persisting and logs the
run-id. Symbols in the output that no longer form a valid cluster (e.g. prey
expired between tool-call and completion) are silently skipped.

## Strigoi-Index Webhooks

Called by Vistierie during a `strigoi-index` agent run (index-inclusion drift).
Both require `Authorization: Bearer <STRIGOI_INDEX_TOKEN>`; only registered when
`STRIGOI_INDEX_ENABLED=true`.

### `POST /api/strigoi-index/tools/fetch-candidates`

Tool webhook. Returns recently-added S&P 500 constituents (deterministic, metadata-only).

Request: `{ "run_id": "...", "tool_name": "fetch_recent_index_additions", "input": { "lookback_days": 30 } }`

Input `lookback_days` range: 1–90; default 30.

Response:
```json
{ "output": { "candidates": [
  { "symbol": "ACME", "companyName": "Acme Corp", "dateAdded": "2026-05-15" }
] } }
```

### `POST /api/strigoi-index/complete`

Completion webhook. Headers: `Authorization: Bearer ...`, `X-Vistierie-Run-Id: ...`.
On success (`status` = `done`) persists each `output.prey[]` entry as Prey with
`anomalyType=INDEX_INCLUSION`, `discoveredBy=strigoi-index`. Prey without a `symbol` are
skipped. Returns 204; non-success / empty prey acknowledged without persisting.
