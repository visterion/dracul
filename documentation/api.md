# REST API

The Chronicle frontend is the primary consumer of this API. All paths
are relative to the context root of `dracul-app`.

## Prey

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/prey` | List recent prey; query params: `strigoi`, `anomaly`, `from`, `to`, `minConfidence`, `page`, `size` |
| GET | `/api/prey/{id}` | Single prey detail (signals, risks, `killCriteria` (string[]), thesis, outcome if assessed) |

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
| POST | `/api/strigoi/{name}/run` | Trigger manual one-off hunt (proxied to Vistierie); 202 `{"runId": "..."}`, 404 unknown strigoi, 409 `AGENT_PAUSED`, 422 `BUDGET_EXCEEDED` |

## Executor

Operator seam for the guarded paper-trading executor agent (slice 1). These
three endpoints sit behind Cloudflare Access like the rest of `/api/**` (no
bearer token) and are only registered when `dracul.executor.enabled=true`.
The tool + completion webhooks the agent itself calls are documented
separately under "Executor Webhooks" below.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/executor/signals` | Inject a signal (advice) for the executor to evaluate |
| GET | `/api/executor/signals` | List signals awaiting evaluation (status `PENDING`, up to 50) |
| POST | `/api/executor/run` | Trigger an ad-hoc Vistierie run of the executor agent |
| GET | `/api/executor/calibration` | Brier calibration — executor overall + per-hunter |
| GET | `/api/executor/behavior` | Veto precision, hard-exit latency, whipsaw, stop-basis comparison, slippage |
| GET | `/api/executor/metrics/versions` | Outcome metrics grouped by `(source_agent, agent_version, rule_version)`, with an insufficient-sample gate |

### `POST /api/executor/signals`

Request body (all fields optional except `symbol`/`direction`/`confidence`,
which the code-enforced `SCHEMA_INVALID` veto requires downstream):

```json
{
  "signal_id": "optional-caller-supplied-id",
  "source": "gropar",
  "agent_version": "1",
  "symbol": "ACME",
  "direction": "LONG",
  "confidence": 0.8,
  "mechanism": "insider cluster + PEAD",
  "kill_criteria": ["close below 20d low"],
  "horizon": "3m",
  "reference_price": 142.50
}
```

`signal_id` is generated (UUID) when absent. The signal is persisted with
status `PENDING`. Response (200):

```json
{ "signal_id": "...", "status": "PENDING" }
```

### `GET /api/executor/signals`

Returns up to 50 pending `ExecutorSignal` rows (same shape as the tool
webhook's `fetch-pending-signals`, see below).

### `POST /api/executor/run`

Triggers an ad-hoc Vistierie run of the `executor` agent (same mechanism as
`POST /api/strigoi/{name}/run`). Returns the Vistierie `VistierieRunDetail`.

### `GET /api/executor/calibration`

Read-only analytics over `decision_log` + `outcome_log` (Task 10, executor sim
completion): how well the executor's and each hunter's confidence scores
predicted realized outcomes (Brier score). No LLM calls, no writes.

- **Executor Brier**: `confidence_in_decision` of `ENTER` decision rows vs.
  `realized_r > 0` of the joined, completed `TRADE` outcome row.
- **Hunter Brier**: `inputs_snapshot.signal_confidence` of the `ENTER`/`REJECT`
  decision row vs. `outcome_log.hunter_label` (triple-barrier label), grouped
  by `outcome_log.source_agent`.
- Buckets are fixed predicted-confidence deciles `[0-0.5)`, `[0.5-0.6)`,
  `[0.6-0.7)`, `[0.7-0.8)`, `[0.8-0.9)`, `[0.9-1.0]`; only non-empty buckets
  are returned.
- `insufficient: true` when the sample size `n < 30` — the numbers are still
  returned, just flagged as low-confidence. Applies independently per unit
  (executor overall, and each hunter).
- An empty database returns a valid zeroed/`insufficient: true` response, not
  an error.

Response (200):

```json
{
  "executor": {"brier": 0.18, "n": 42, "insufficient": false,
               "buckets": [{"range": "0.6-0.7", "n": 10, "predicted": 0.65, "observed": 0.5}]},
  "hunters": [{"agent": "strigoi-echo", "brier": 0.21, "n": 17, "insufficient": true, "buckets": []}]
}
```

### `GET /api/executor/behavior`

Read-only behavioral analytics (Task 10): veto precision (what rejected
signals would have done), hard-exit latency, whipsaw (reentry/roundtrip),
stop-basis comparison (ATR vs. swing-low), and slippage vs. limit price.

- **`veto_precision`**: `COUNTERFACTUAL` outcome rows grouped by
  `reason_code` (the first failed veto check). `skipped` counts rows with a
  `hypothetical.skipped_reason` set (e.g. missing reference price); means
  (`mean_hypothetical_r_20d`, `mean_hypothetical_r_60d`, `stopped_out_pct`)
  are computed over the remaining, non-skipped rows only.
- **`caveats`**: three fixed strings, always present, calling out the
  optimistic-fill assumption, the opportunity-cost nature of
  `PACE_LIMIT`/`BUDGET` rejects, and that `reason_code` stats are conditional
  on earlier checks having passed.
- **`hard_exit_latency`**: `n`/`max_seconds`/`p95_seconds` over
  `decision_log.latency.trigger_to_order_seconds` of `HARD_TRIGGER` rows.
- **`whipsaw`**: counts of `TRADE` outcome rows with `reentry_within_10d` /
  `roundtrip_under_5d` set.
- **`stop_basis`**: `TRADE` rows grouped by the ENTER order's
  `order_json.stop_basis`, normalized by substring match — strings
  containing `swing_low` become `SWING_LOW`, strings containing `ATR` (and
  not already matched as swing-low) become `ATR`.
- **`slippage`**: `n`/`mean`/`worst` (most negative) over `TRADE` rows'
  `slippage_vs_limit`.
- An empty database returns a valid zeroed/empty-array response, not an
  error.

Response (200):

```json
{
  "veto_precision": [{"reason_code": "PACE_LIMIT", "n": 12, "skipped": 3, "mean_hypothetical_r_20d": 0.4,
                       "mean_hypothetical_r_60d": 1.1, "stopped_out_pct": 25.0}],
  "caveats": ["counterfactuals assume reference-price fills (optimistic)",
              "PACE_LIMIT/BUDGET rejects are opportunity-cost questions",
              "reason_code is the first failed check; stats are conditional on earlier checks passing"],
  "hard_exit_latency": {"n": 5, "max_seconds": 3, "p95_seconds": 2},
  "whipsaw": {"reentry_within_10d": 0, "roundtrip_under_5d": 1},
  "stop_basis": [{"basis": "ATR", "n": 8, "mean_realized_r": 0.9, "mean_mae_r": -0.5},
                 {"basis": "SWING_LOW", "n": 4, "mean_realized_r": 1.3, "mean_mae_r": -0.3}],
  "slippage": {"n": 12, "mean": -0.02, "worst": -0.15}
}
```

### `GET /api/executor/metrics/versions`

Read-only outcome metrics (Task 5, item 23) over completed `TRADE` rows in
`outcome_log`, joined to `decision_log` for timestamps, grouped by
`(source_agent, agent_version, rule_version)`. Lets an operator compare a
new agent/rule version's realized performance against the prior one once
enough data has accumulated. No LLM calls, no writes.

- **`avg_return`**: mean `outcome_log.realized_r` (quantity-weighted
  R-multiple over partial exits) of the group's completed `TRADE` rows.
- **`hit_rate`**: fraction of the group's rows with `realized_r > 0` — the
  same "won" definition used by `GET /api/executor/calibration`'s executor
  Brier score.
- **`insufficient_sample`**: `true` unless the group's decisions span at
  least 14 days (`first_at` to `last_at`, from the joined `decision_log`
  rows) **and** the group has at least 20 decisions — both thresholds must
  hold ("2 weeks or 20 decisions, whichever is later"). The row is still
  returned when insufficient, just flagged as low-confidence.
- An empty database returns `{"versions": []}`, not an error.

Response (200):

```json
{
  "versions": [
    {"agent": "gropar", "agent_version": "1", "rule_version": "v3", "decisions": 25,
     "first_at": "2026-06-01T00:00:00Z", "last_at": "2026-06-25T00:00:00Z",
     "avg_return": 0.31, "hit_rate": 0.56, "insufficient_sample": false},
    {"agent": "gropar", "agent_version": "2", "rule_version": "v4", "decisions": 6,
     "first_at": "2026-07-05T00:00:00Z", "last_at": "2026-07-10T00:00:00Z",
     "avg_return": -0.1, "hit_rate": 0.33, "insufficient_sample": true}
  ]
}
```

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

### `POST /api/watchlist`

Request body: `{ "symbol": "3750.HK", "tag": "HELD" | "TRACKING", "sourceVerdictId"?: "<uuid>" }`.

`symbol` must match `^[A-Z0-9][A-Z0-9.\-]{0,11}$` — uppercase, 1–12 chars, starting
with a letter or digit, then letters/digits/`.`/`-`. This admits exchange-suffixed
tickers (`3750.HK`, `300750.SZ`, `ABBN.SW`, `BRK.B`) alongside plain US symbols.
Adding an already-present ticker (same owner) merges instead of duplicating. On an
unknown symbol the market-data provider returns `422 MARKET_DATA_NOT_FOUND`.

### `PATCH /api/watchlist/{id}/position`

Records or clears the operator's position for a watchlist item. Both fields are
nullable — send `null` to remove the position.

Request body:
```json
{ "entryPrice": 142.50, "shareCount": 100, "entryDate": "2026-06-01" }
```

Constraints:
- `entryPrice`: `>= 0` or `null`
- `shareCount`: `>= 0` or `null`
- `entryDate`: ISO date string (`yyyy-MM-dd`), optional — the position's real purchase date used by gropar's MFE/giveback. Omitting it (or sending `null`) leaves the stored `entry_date` unchanged. New rows default `entry_date` to the creation date (`CURRENT_DATE`); existing rows were backfilled to `added_at`.
- Both `entryPrice` and `shareCount` `null` clears the position (P&L section reverts to the "add position" state)
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

#### Provenance field (`source`)

`GET /api/watchlist` items include a read-only `source` field (`string`, non-null).
`POST /api/watchlist` (the only current write path) always sets `"manual"`; rows
that existed before the V36 migration were backfilled to `"verdict"` or `"seed"`
where their history indicates it (see `watchlist_items.source` in `architecture.md`).
`source` is set once at insert time and never mutated afterwards.

> **Removed 2026-07-13 (depot-as-SSOT).** `GET /api/portfolio` (the manual,
> watchlist-HELD-based "portfolio") has been removed. Chronicle's Portfolio nav
> destination now redirects to `/depots`, which reads live positions from
> `GET /api/depots` (depot-1) — see "Depots" below. `GET /api/watchlist` and its
> `PATCH .../position` sub-resource are unchanged and still owned by Project B
> (the collaborative tracking board).

## Exit Signals

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/exit-signals` | Returns the latest exit signal per depot position for the **current user**; each entry includes `id`, `watchlistItemId` (legacy, always `null` for depot-sourced signals), `symbol`, `action` (SELL / TRIM / HOLD), `firedRules[]`, `gainLossPct`, `thesisStatus` (INTACT / WEAKENING / INVALIDATED / NONE), `rationale`, `confidence`, `vistierieRunId`, and `runAt` |

> Chronicle's exit-signal detail view resolves the underlying position by
> **symbol** against `GET /api/depots` (scanning every connected depot's
> positions), not by `watchlistItemId` — gropar's depot-sourced signals never
> populate that field (see `exit_signals` in `architecture.md`).

> **Note:** `GET /api/exit-signals` is scoped to the current user
> (`CurrentUserHolder.get()`). It was previously hardcoded to the user
> `"default"` — a bug that caused signals to be invisible after the legacy
> owner migration. Prod signals now correctly resolve against the primary user.

## Morning Report

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/morning-report` | Returns the current user's morning report: a projection over the live **depot-1** positions (⨝ `position_context`, via `HeldPositionService.openPositions`) combined with the latest exit signal per symbol |

Read-only; no market-data calls are made at request time (the depot ⨝ context join is the only data source). Scoped to the current user.

> **Repointed 2026-07-13 to the depot-as-SSOT model.** The report no longer reads HELD
> watchlist items or the V15 watchlist risk-snapshot columns (`active_stop` / `next_target_2r` /
> `current_close`) — it reads live depot-1 positions instead, joined by symbol to their
> (nullable) `position_context` row for `activeStop`. `currentClose` is derived from the
> depot's `marketValue / quantity`; there is no source yet for `nextTarget2r`, so it is
> always `null` (unchanged shape, just no value to fill). Exit signals are matched by
> **symbol**, not `watchlistItemId` — gropar's depot-sourced signals always write
> `watchlistItemId = null`, so keying by symbol is what makes gropar's exit actions actually
> appear here (see `exit_signals` in `architecture.md`).

### `GET /api/morning-report` response

```json
{
  "generatedAt": "2026-06-23T07:00:00Z",
  "sellCount": 1,
  "trimCount": 0,
  "holdCount": 2,
  "positions": [
    {
      "symbol": "ACME",
      "companyName": "Acme Corp",
      "shareCount": 50,
      "entryPrice": 100.0,
      "currentClose": 142.5,
      "activeStop": 128.0,
      "nextTarget2r": 160.0,
      "distanceToStopPct": -10.2,
      "action": "HOLD",
      "thesisStatus": "INTACT",
      "confidence": 0.75,
      "rationale": "Position above stop; MA cross bullish; no giveback.",
      "ticket": {
        "side": "SELL",
        "symbol": "ACME",
        "shares": 50,
        "limitReference": 142.5,
        "stop": 128.0,
        "target": 160.0
      }
    }
  ]
}
```

Top-level fields:

| Field | Type | Description |
|---|---|---|
| `generatedAt` | string (ISO instant) | Timestamp when the report was assembled |
| `sellCount` | integer | Number of positions with action = SELL |
| `trimCount` | integer | Number of positions with action = TRIM |
| `holdCount` | integer | Number of positions with action = HOLD |
| `positions` | array | One entry per open depot-1 position |

Per-position fields:

| Field | Type | Description |
|---|---|---|
| `symbol` | string | Ticker (also used as `companyName` -- the depot carries no company name) |
| `companyName` | string | Same value as `symbol` (depot positions carry no company name) |
| `shareCount` | number | Live depot quantity |
| `entryPrice` | number | Live depot average entry price |
| `currentClose` | `number \| null` | Derived from the depot's `marketValue / quantity`; null if either is missing or quantity is zero |
| `activeStop` | `number \| null` | From `position_context.active_stop`; null when the position has no open context row |
| `nextTarget2r` | `number \| null` | Always `null` for now -- no source for a 2R price target exists in `position_context` yet |
| `distanceToStopPct` | `number \| null` | `(currentClose − activeStop) / currentClose × 100`; negative means price is below stop |
| `action` | string | Latest gropar verdict: SELL / TRIM / HOLD |
| `thesisStatus` | string | INTACT / WEAKENING / INVALIDATED / NONE |
| `confidence` | number | Gropar confidence (0–1) |
| `rationale` | string | Gropar rationale text |
| `ticket` | object | Informational order ticket (see below); **Dracul places no orders** |

Order ticket fields (`ticket`):

| Field | Type | Description |
|---|---|---|
| `side` | string | BUY / SELL |
| `symbol` | string | Ticker |
| `shares` | number | Share count |
| `limitReference` | `number \| null` | Reference price for a limit order (last close) |
| `stop` | `number \| null` | Stop-loss reference level |
| `target` | `number \| null` | Profit-target reference level |

> **Important:** The order ticket is **informational only**. Dracul is a
> read-only research assistant. It surfaces suggested action parameters so the
> operator can decide whether and how to act — it does **not** route, submit, or
> manage orders with any broker or exchange.

## Depots

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/depots` | List depot connections and their positions/orders for the current user. Served from a per-connection display cache (TTL `dracul.depots.cache-ttl-seconds`, default 60s); `?refresh=true` bypasses it and re-fetches from the broker. |
| GET | `/api/depots/{connection}/positions/{symbol}` | One position's detail slice (owning depot's identity, the position, and only that symbol's orders). Fetches only the requested connection (cached), not all connections. |
| GET | `/api/depots/{connection}/history` | Broker-authoritative trade history (closed positions for Saxo, all orders for Alpaca) with optional Dracul "why" annotation (Strigoi/rationale per `broker_order_id` when linked). Returns `{ entries: [...], error }`. |
| GET | `/api/depots/chart` | Raw close-price series for one instrument (`symbol`, `range` query params) — pure market data, no live-gating |
| GET | `/api/depots/{connection}/chart` | Composed depot performance curve for one connection (`range` query param) |
| GET | `/api/depots/instrument/{symbol}` | Instrument info bundle (profile, news, earnings, analyst/earnings estimates, fundamental score, fundamentals, insider activity) for the GUI's instrument page — pure market data, no live-gating |

Both endpoints are user-scoped via `CurrentUserHolder.get()`. `GET
/api/depots` calls `DepotService.depots(userEmail, refresh)` (default
`refresh=false`, served from the per-connection display cache — see
`dracul.depots.cache-ttl-seconds` in configuration.md; `?refresh=true`
forces a fresh broker fetch). The position-slice and chart endpoints use
`DepotService.depot(connection, userEmail, false)` so they fetch (and
cache) only the requested connection rather than all of them. The method
lists Agora's configured broker connections and gates any
**live**-environment
connection behind an allow-listed set of user emails
(`dracul.depots.live-visible-emails`, default `viktor@ufelmann.de`);
**paper**-environment connections are visible to everyone. A connection
the calling user isn't allowed to see for the live gate is simply absent
from the response (not an error) — the position-slice endpoint 404s for
it the same way it 404s for an unknown connection or symbol.

If Agora is fully unreachable, `DepotService.depots()` propagates a
`DepotUnavailableException` instead of silently returning an empty
list; `GET /api/depots` catches it and turns it into the 200 +
top-level-`error` shape below, while `GET
/api/depots/{connection}/positions/{symbol}` catches it and returns
`503 SERVICE_UNAVAILABLE` (see that endpoint's section for the full
error-status matrix).

`asOf` (top-level per depot, and echoed in the position-slice response)
is the instant Agora's broker fetch (`get_account`/`get_positions`) was
taken — not a request/response timestamp.

Positions may carry a different currency than the depot account
(`account.currency()`). `DepotService.assemble` converts each
position's `marketValue`, `unrealizedPl`, `price`, and `avgEntryPrice`
into the account currency via `FxService.convert` before aggregating
and before building the position DTO (whose `currency` field is set to
the account currency, not the position's own); `weightPct` is a ratio
and unaffected. `FxService.convert` is cache-only and returns the
input unchanged when no rate is cached yet, so a freshly started
instance briefly serves unconverted values until the background
refresher warms the pair.

`name`, `assetType`, `valueDate` are Saxo-native fields passed straight
through from Agora's `get_positions` (`description`/`assetType`/
`valueDate`) — plain strings, never FX-converted; `null` for
Alpaca-backed connections (and for `valueDate`/`description` on
brokers that don't report them). `nativePrice`/`nativeCurrency` carry
the position's pre-conversion quote price and its native currency, so
the GUI can render e.g. `167,81 € (191,13 $)`; both are `null` when the
position currency equals the account currency (nothing to show in
parens).

### `GET /api/depots` response

```json
{
  "depots": [
    {
      "id": "alpaca-paper-1",
      "provider": "alpaca",
      "environment": "paper",
      "status": "connected",
      "probedAt": "2026-07-11T08:00:00Z",
      "error": null,
      "account": { "...": "..." },
      "aggregates": { "...": "..." },
      "positions": [
        {
          "symbol": "ACME",
          "qty": 10,
          "avgEntryPrice": 12.50,
          "marketValue": 145.00,
          "unrealizedPl": 20.00,
          "unrealizedPlPct": 16.00,
          "price": 14.50,
          "dayChangePercent": 0.85,
          "weightPct": 42.30,
          "currency": "USD",
          "name": "Acme Corp",
          "assetType": "Stock",
          "valueDate": "2026-06-01",
          "nativePrice": null,
          "nativeCurrency": null
        }
      ],
      "orders": [ { "...": "..." } ],
      "asOf": "2026-07-11T08:00:00Z"
    }
  ],
  "error": null
}
```

`error` is non-null (with `depots` an empty list) only when Agora is
unreachable for the whole read path (`DepotUnavailableException`) — HTTP
status stays 200; per-connection failures instead surface as a non-null
`error` on that one entry in `depots[]` (see `DepotDto`).

### `GET /api/depots/{connection}/positions/{symbol}` response

```json
{
  "depot": { "id": "alpaca-paper-1", "provider": "alpaca", "environment": "paper" },
  "position": {
    "symbol": "ACME",
    "qty": 10,
    "avgEntryPrice": 12.50,
    "marketValue": 145.00,
    "unrealizedPl": 20.00,
    "unrealizedPlPct": 16.00,
    "price": 14.50,
    "dayChangePercent": 0.85,
    "weightPct": 42.30,
    "currency": "USD",
    "name": "Acme Corp",
    "assetType": "Stock",
    "valueDate": "2026-06-01",
    "nativePrice": null,
    "nativeCurrency": null
  },
  "orders": [ { "brokerOrderId": "o1", "symbol": "ACME", "side": "buy", "qty": 10, "type": "market", "status": "filled", "role": "entry" } ],
  "asOf": "2026-07-11T08:00:00Z"
}
```

`orders` is filtered to only the orders for `{symbol}` within that
depot connection.

- **`404 NOT_FOUND`** — `{connection}` isn't visible to the current
  user (unknown, or a live connection outside the allow-list), or
  `{symbol}` isn't held in that (successfully-fetched) connection's
  positions.
- **`503 SERVICE_UNAVAILABLE`** — either the whole read path failed
  (Agora fully unreachable, `DepotUnavailableException` from
  `DepotService.depots()`), or `{connection}` was found but its own
  fetch failed (`DepotDto.error()` non-null / `positions()` null, the
  same per-connection failure that shows up as an `error` entry in
  `GET /api/depots`'s `depots[]`). In both cases the response body is
  the plain error message, not a JSON envelope.

### `GET /api/depots/{connection}/history` response

Broker-authoritative closed-position and order history with optional Dracul annotations.
For Saxo (and any non-Alpaca provider), fetches `get_closed_positions`; for Alpaca, fetches
all orders with `status=all` and filters to the terminal statuses `filled`, `canceled`,
`cancelled`, `expired`, `rejected` (`partially_filled` is deliberately excluded — an order
still in flight is not history). Each Alpaca entry includes an optional `why` annotation
(Strigoi name and rationale) when Dracul can link the trade to an executor decision via
`brokerOrderId`; Saxo closed positions never carry `why` (no order id / timestamp to link on).

This endpoint always returns **HTTP 200**, never 404/503: an unknown or invisible
`{connection}` yields `{ entries: [], error: null }`, and a broker fetch failure yields
`{ entries: [], error: "<message>" }`. The `error` field is the only place a fetch failure
is surfaced — the frontend must check it, not rely on a non-200 status.

```json
{
  "entries": [
    {
      "source": "ORDER",
      "symbol": "AAPL",
      "side": "buy",
      "qty": 10,
      "entryPrice": null,
      "exitPrice": null,
      "profitLoss": null,
      "status": "filled",
      "brokerOrderId": "o-1",
      "brokerConfirmed": true,
      "why": {
        "strigoi": "index-strigoi",
        "killCriteria": ["stop below 95"],
        "entryReasoning": "index inclusion drift",
        "draculExitReason": "TAKE_PROFIT",
        "draculRealizedR": 2.0
      }
    },
    {
      "source": "CLOSED_POSITION",
      "symbol": "SAP",
      "side": null,
      "qty": null,
      "entryPrice": 100,
      "exitPrice": 120,
      "profitLoss": 200,
      "status": "closed",
      "brokerOrderId": null,
      "brokerConfirmed": true,
      "why": null
    }
  ],
  "error": null
}
```

Per-entry fields (`DepotHistoryEntry`):

| Field | Type | Description |
|---|---|---|
| `source` | string | `ORDER` (Alpaca order) or `CLOSED_POSITION` (Saxo/default closed position) |
| `symbol` | string | Ticker |
| `side` | string \| null | `buy` / `sell` for orders; `null` for closed positions |
| `qty` | number \| null | Order quantity; `null` for closed positions |
| `entryPrice` | number \| null | Open price — only populated for `CLOSED_POSITION`; `null` for `ORDER` |
| `exitPrice` | number \| null | Close price — only populated for `CLOSED_POSITION`; `null` for `ORDER` |
| `profitLoss` | number \| null | Realized P&L — only populated for `CLOSED_POSITION`; `null` for `ORDER` |
| `status` | string | Broker order status for `ORDER` (e.g. `filled`); literal `"closed"` for `CLOSED_POSITION` |
| `brokerOrderId` | string \| null | Broker-native order id — present for `ORDER`, `null` for `CLOSED_POSITION`; also the key `why` enrichment links on |
| `brokerConfirmed` | boolean | Always `true` — every entry originates from a broker-authoritative source |
| `why` | object \| null | Optional Dracul annotation (present only for `ORDER` entries linked to an executor decision via `brokerOrderId`, and only when the executor repos are wired, i.e. `dracul.executor.enabled=true`); always `null` for `CLOSED_POSITION` |

`why` object fields (`DepotHistoryEntry.Why`, when present):

| Field | Type | Description |
|---|---|---|
| `strigoi` | string | Agent name that issued the entry decision (e.g. `index-strigoi`) |
| `killCriteria` | string[] | Kill criteria recorded on the executor position |
| `entryReasoning` | string \| null | Entry decision's rationale, from the matching `DecisionLog` (`action=ENTER`); `null` if no matching decision log |
| `draculExitReason` | string \| null | Executor's recorded exit reason (e.g. `TAKE_PROFIT`) — not authoritative for execution facts |
| `draculRealizedR` | number \| null | Executor's recorded realized R — not authoritative for execution facts |

All `why` fields are explicitly non-authoritative — the UI always marks them as Dracul's
interpretation, never as broker fact.

### `GET /api/depots/chart` response

Query params: `symbol` (instrument ticker), `range` (`1d` | `1w` | `1m` |
`1y` | `max`). Pure market data via `DepotChartService`/Agora — no
user/live-gating concern, unlike the depot-scoped endpoints above.

Range → Agora lookback mapping: `1d` calls `get_intraday`
(`interval=5m`, `range=1d`, `t` = ISO instant); `1w`/`1m`/`1y`/`max`
call `get_ohlc` with `days` = `7`/`31`/`365`/`1825` respectively (`t` =
ISO date). An unrecognized `range` is `400 BAD_REQUEST`.

```json
{
  "symbol": "ACME",
  "range": "1y",
  "points": [
    { "t": "2025-07-11", "value": 100.00 },
    { "t": "2025-07-14", "value": 103.50 }
  ]
}
```

### `GET /api/depots/{connection}/chart` response

Query param: `range` (same values/mapping as above). The connection is
resolved through `DepotService.depots(CurrentUserHolder.get())` — the
same call and live-visibility gate `GET /api/depots` uses — so this
endpoint never leaks a live depot to a user outside the allow-list; its
positions (already fetched/gated by `DepotService`) are then used to
compose the curve. Error-status matrix matches
`GET /api/depots/{connection}/positions/{symbol}`: **`404 NOT_FOUND`**
if `{connection}` isn't visible to the current user, **`503
SERVICE_UNAVAILABLE`** if the whole read path failed or that
connection's own fetch failed.

`value(t) = Σ qty_i × close_i(t) + cash` — the depot's current cash
plus each held position's quantity times that instrument's close price
at `t`. Per-symbol close series are fetched independently and aligned
by **intersection**: only timestamps present in every
successfully-fetched symbol's series appear in `points`/`relative`. A
symbol whose series can't be fetched (Agora failure) — or that Agora
fetches without error but returns zero bars for — is skipped; the
curve is still built from the rest — and sets `partial: true`.
`relative[].pct` is `(value/points[0].value − 1) × 100`, scale 2
HALF_UP (so the first point's `pct` is always `0.00`).

```json
{
  "connection": "alpaca-paper-1",
  "range": "1y",
  "points": [
    { "t": "2026-07-01", "value": 2250.00 },
    { "t": "2026-07-02", "value": 2300.00 }
  ],
  "relative": [
    { "t": "2026-07-01", "pct": 0.00 },
    { "t": "2026-07-02", "pct": 2.22 }
  ],
  "partial": false
}
```

### `GET /api/depots/instrument/{symbol}` response

Pure market data via `DepotInstrumentService`/Agora — no user/live-gating
concern, same as `GET /api/depots/chart`. Feeds the GUI's
Trade-Republic-style instrument page (profile, news, events, insights,
financials shown as independent sections).

Backed by eight Agora tool calls, made **sequentially**
(`AgoraClient.callTool` is `synchronized`, so parallelizing them buys
nothing) and each wrapped in its own try/catch: `get_company_profile`,
`get_company_news` (window: `from` = today − 14 days, `to` = today),
`get_earnings_window`, `get_analyst_estimates`, `get_earnings_estimates`,
`get_fundamental_score`, `get_fundamentals`, `get_form4_transactions`. Since
T1.4 each item may carry a `domain` field (new Agora; lowercase url host) and
Dracul drops sub-threshold-credibility items at the chokepoint before any
trigger/review consumer sees them; an older Agora without `domain` degrades to
source-string matching.
**Every section is independently nullable** — a single tool failing
(Agora error, timeout, etc.) sets only that section to `null`; the
other sections and the `200 OK` response shape are unaffected. If all
eight calls fail, the response is still `200` with every section
`null`.

`get_earnings_window` and `get_form4_transactions` take no `symbol`
input in Agora — they are market-wide, date-windowed queries (each
`earnings` row carries its own `symbol`, each `transactions` row its
own `ticker`) — so `DepotInstrumentService` filters their rows
server-side down to the requested `{symbol}` (case-insensitive) before
returning them; the `earnings` and `insiderActivity` sections here only
ever contain rows for `{symbol}`, with the rest of Agora's envelope
(e.g. `truncated`) preserved as-is.

```json
{
  "symbol": "ACME",
  "profile": { "symbol": "ACME", "name": "Acme Corp", "industry": "...", "exchange": "...", "marketCap": 123456789 },
  "news": { "symbol": "ACME", "news": [ { "...": "..." } ] },
  "earnings": { "earnings": [ { "symbol": "ACME", "...": "..." } ] },
  "analystEstimates": { "symbol": "ACME", "recommendations": [ { "...": "..." } ] },
  "earningsEstimates": { "symbol": "ACME", "estimates": [ { "...": "..." } ] },
  "fundamentalScore": { "symbol": "ACME", "score": 7 },
  "fundamentals": { "symbol": "ACME", "peRatio": 20.1 },
  "insiderActivity": { "transactions": [ { "ticker": "ACME", "...": "..." } ] }
}
```

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

### Pattern gates (T3.3)

`GET /api/patterns` response items now additionally include:

| Field | Type | Description |
|---|---|---|
| `gate` | `object \| null` | the pattern's stored machine-checkable predicate (see schema below); `null` = advisory-only, not enforced |
| `blockedCount` | number | distinct signals (`COUNT(DISTINCT signal_id)`) rejected with reason `PATTERN_GATE` attributed to this pattern, computed at read time (not persisted). With overlapping gates, a block attributes to the first matching gate in repository order — a later, also-matching gate can show `0` here despite matching the same signals. |

`PATCH /api/patterns/{id}` gains action `update_gate`:

- Request body: `{"action": "update_gate", "gate": {...} | null}`.
- `gate` field missing from the body → `400` (guards against accidentally
  wiping an armed gate on a malformed request).
- `gate: null` → clears the pattern's gate (goes back to advisory-only).
- `gate: {...}` → validated; invalid predicate → `400` with
  `{"errors": ["..."]}` (one message per invalid condition).
- Pattern status `REJECTED` → `400` (a rejected pattern's gate can no longer
  be edited).
- Unknown pattern id → `404`.
- On success: `204 No Content`. Replacing a gate deletes the pattern's
  machine-scored auto-evidence (`pattern_evidence` rows with `outcome_ref IS
  NOT NULL`) in the same transaction; the next weekly scorer rescan rebuilds
  it under the new predicate.

Gate schema:

```json
{
  "conditions": [
    {"field": "mechanism|symbol|sector|confidence|price", "op": "eq|ne|in|not_in|lt|lte|gt|gte", "value": "..."}
  ]
}
```

1–8 conditions, combined with AND semantics. `mechanism`/`symbol`/`sector`
are string fields (ops `eq`/`ne`/`in`/`not_in`; `in`/`not_in` take a
non-empty string array); `confidence`/`price` are numeric fields (ops
`lt`/`lte`/`gt`/`gte`; value must be a number).

`PATCH /api/patterns/{id}` action `approve`: only allowed from status
`PENDING` (activates the pattern and, if it carries a gate, arms enforcement
immediately); any other status → `400`.

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
The path runs through Cloudflare Access (the browser `EventSource` GET carries the
CF cookie / injected JWT), so the stream is **authenticated and scoped to the
connecting user** — each stream receives only that user's events. Multiple tabs of
one user are all served.

**v1 emits `alert.new`** — a new Daywalker alert:

```text
event: alert.new
data: {"symbol":"AAPL","trigger_type":"PRICE_SPIKE","severity":"CRITICAL","thesis":"…","ts":"2026-06-04T18:00:00Z"}
```

An `alert.new` event is delivered only to the owners for whom an alert row was
persisted (watchlist owner of the symbol, outside the per-`(owner, symbol,
trigger-type)` cooldown) — the same boundary as the persisted alert list.

**and `verdict.kill_criteria_breached`** — a contributing prey's kill criterion
on an open (non-DISMISSed), non-held verdict has newly evaluated as breached,
emitted by `VerdictKillCriteriaWatcher`:

```text
event: verdict.kill_criteria_breached
data: {"verdict_id":"…","symbol":"AAPL","breached":["Close below 90"],"ts":"2026-06-04T21:30:00Z"}
```

Delivered only to the verdict's owner. `breached` lists only the *newly*
breached criteria on this poll — criteria already persisted as breached on a
prior poll are not re-sent (though `markKillCriteriaBreached` still refreshes
`kill_criteria_checked_at` for them every poll).

The stream is generic; `strigoi.status` is planned and will attach to the same
stream once its source exists. No replay / Last-Event-ID in v1 — clients
receive events from connect time; `EventSource` auto-reconnects.

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
| `GET /api/chronicle` | `ChronicleData` | Morning feed: prey[], verdicts[], pendingPatterns[], alerts[]. Query params: `includeDismissed` (default false, verdicts), `includeArchived` (default false, prey — hides prey whose horizon has expired unless set true) |
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
        "filers": [
          {
            "name": "...", "role": "Chief Executive Officer",
            "classification": "OPPORTUNISTIC",
            "sharesOwnedFollowing": 9000,
            "purchaseAsPctOfHoldings": 0.18,
            "planned10b5_1": false
          }
        ],
        "windowStart": "2026-05-15", "windowEnd": "2026-05-25",
        "totalDollarValue": 1234567,
        "totalShares": 1000,
        "concurrentInsiderSells": 0,
        "netInsiderDollar": 1234567,
        "marketCap": 850.0,
        "adv": 10000000,
        "metricsAvailable": true,
        "analystCoverage": 4,
        "coverageAvailable": true,
        "ytdReturn": -0.43,
        "ytdReturnAvailable": true,
        "nextEarningsDate": "2026-07-20",
        "daysToEarnings": 8,
        "earningsDateAvailable": true,
        "opportunisticShare": 0.67,
        "classifiedFilers": 3,
        "unknownFilers": 0,
        "classificationAvailable": true
      }
    ]
  }
}
```

`concurrentInsiderSells` (integer) is the count of distinct insiders who sold within the same window as the buy cluster, and `netInsiderDollar` (number) is `totalDollarValue` minus the dollar value of those concurrent sales. Both are advisory signal-strength inputs for the LLM — no cluster is dropped by the deterministic screener because of them.

Each `filers[]` entry is an object: `name`, `role` (free-text Form-4 officer title, empty for non-officers), plus the routine/opportunistic classification (Cohen, Malloy & Pomorski 2012, derived from Agora `get_form4_owner_history`): `classification` (`OPPORTUNISTIC` / `ROUTINE` / `UNKNOWN`), `sharesOwnedFollowing` (shares held after the filer's most recent purchase in the window), `purchaseAsPctOfHoldings` (window purchase shares ÷ `sharesOwnedFollowing`, relative conviction), and `planned10b5_1` (tri-state Rule 10b5-1(c) plan flag — `true`/`false`/`null`, where `null` means unknown, not false). The cluster-level rollup is `opportunisticShare` (opportunistic ÷ classifiable filers, `null` when none classifiable), `classifiedFilers` (routine + opportunistic), `unknownFilers`, and `classificationAvailable` (`false` when the owner-history source was down/skipped — then every filer is `UNKNOWN` and `opportunisticShare` is `null`).

The remaining fields are deterministic context enrichment (`InsiderEnrichmentService`, fail-soft — a lookup failure nulls that group and sets its availability flag to `false`, never aborts the hunt): `marketCap` (provider units, USD millions) and `adv` (20-trading-day average daily dollar volume) with `metricsAvailable`; `analystCoverage` (analyst count from the latest recommendation-trend period) with `coverageAvailable`; `ytdReturn` (calendar-year-to-date return as a decimal fraction) with `ytdReturnAvailable`; `nextEarningsDate` / `daysToEarnings` with `earningsDateAvailable` (informational only, no gate). Individual fields may be null even when their group flag is true (the group's lookups are independent — e.g. `adv` present, `marketCap` null). Enrichment is bounded for latency: clusters are sorted by `totalDollarValue` descending and capped at 25; the routine/opportunistic classification costs ONE `get_form4_owner_history` call per cluster (the tool returns every reporting owner of the company at once, so an N-filer cluster is still a single call); a source failing with an availability error is skipped for the rest of the batch, and with two or more sources down the remaining clusters are returned unenriched (all flags `false`).

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

Each candidate also carries `analystCoverage` (integer, nullable — analyst count from the
latest recommendation trend) and `coverageAvailable` (boolean).

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
window. Runs deterministic detection over the live **depot-1** positions
(`HeldPositionService.openPositions`, no LLM — repointed 2026-07-13, A6) and
returns trigger events, each carrying a `position_id` (the symbol). Each event
becomes the payload of one child run.

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

Returns 204. If `status != "succeeded"` or the symbol/trigger_type is missing,
the endpoint acknowledges (204) without persisting and logs the run-id. Since
`/api/daywalker/events` is now depot-sourced, every completed run carries a
`position_id` (the symbol) and is routed straight to the single
`dracul.primary-user-email` owner; the legacy "symbol not on watchlist" skip
path only still applies to a completion with no `position_id` at all, which
the depot-sourced event source never produces.

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
    "filingDate": "2026-05-20", "filingUrl": "https://www.sec.gov/Archives/...",
    "termSheet": "The distribution ratio is one share of SpinCo for every four shares...",
    "termSheetAvailable": true,
    "distributionRatio": "1:4", "recordDate": "2026-06-10", "distributionDate": "2026-06-24" }
] } }
```

`termSheet` / `termSheetAvailable` are advisory annotations — the cleaned
summary-term-sheet text of the filing (via Agora's `get_filing_text`, fetched
by `AgoraFilings.filingText(filingUrl)`) and whether it was available.
`distributionRatio` / `recordDate` / `distributionDate` are deterministically
parsed from `termSheet` by `SpinTermsParser` (`null` when the pattern isn't
found in the text — the LLM still extracts parent/ratio/record-date/size from
the raw `termSheet` as a fallback); fail-soft (`termSheetAvailable = false`,
empty text) on any Agora failure — no candidate is dropped for it.
`output_schema` (the final verdict) is unchanged.

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
    "filingDate": "2026-05-28", "filingUrl": "https://www.sec.gov/Archives/...",
    "termSheet": "...Agreement and Plan of Merger, dated as of March 15, 2026... each share of common stock will be converted into the right to receive $58.00 in cash... expected to close in the fourth quarter of 2026...",
    "termSheetAvailable": true, "lastPrice": 54.10, "priceAvailable": true,
    "offerPrice": 58.00, "considerationType": "cash", "exchangeRatio": null,
    "breakFee": "$120 million", "spreadPercent": 7.21,
    "agreementDate": "2026-03-15", "expectedCloseDate": "2026-12-31", "outsideDate": null,
    "unaffectedPrice": 41.90, "unaffectedPriceAvailable": true, "daysToClose": 217,
    "annualizedSpreadPercent": 12.13, "breakDownsidePercent": 22.55 }
] } }
```

`termSheet` / `termSheetAvailable` and `lastPrice` / `priceAvailable` are
advisory annotations — the cleaned summary-term-sheet text of the filing (via
Agora's `get_filing_text`, fetched by `AgoraFilings.filingText(filingUrl)`)
plus the current quote, for the LLM to interpret. `offerPrice` /
`considerationType` (`cash`/`stock`/`mixed`) / `exchangeRatio` / `breakFee` are
deterministically parsed from `termSheet` by `DealTermsParser` (fields are
`null` when not found in the text); `spreadPercent` is server-computed from
`offerPrice` and `lastPrice` when both are available (`(offerPrice - lastPrice)
/ lastPrice * 100`). `DealTermsParser` also extracts the deal time-axis dates —
`agreementDate` (announcement anchor), `expectedCloseDate` (quarter/half
estimates mapped to the period end), and a separate `outsideDate` (End Date,
never used as the expected close). `MergerEnrichmentService` then adds the
expected-value inputs: `unaffectedPrice` / `unaffectedPriceAvailable` (close of
the last trading day before `agreementDate`, from one ~400-day daily-OHLC query
per candidate; `false` both when no `agreementDate` was parsed and when the
price is out of reach), `daysToClose` (today → `expectedCloseDate`),
`annualizedSpreadPercent` (`spreadPercent × 365 / daysToClose`, only when
`daysToClose ≥ 1`), and `breakDownsidePercent` (`(lastPrice − unaffectedPrice)
/ lastPrice × 100`, the deal-break cliff). The LLM still extracts
offer/consideration/conditions/termination-fee from `termSheet` itself as a
fallback and interprets the spread; fail-soft (`*Available = false`, numeric
fields `null`) on any Agora failure — no candidate is dropped for it.
`output_schema` (the final verdict) is unchanged.

### `POST /api/strigoi-merger/complete`

Completion webhook. Headers: `Authorization: Bearer ...`, `X-Vistierie-Run-Id: ...`.
On success (`status` = `done`) persists each `output.prey[]` entry as Prey with
`anomalyType=MERGER_ARB`, `discoveredBy=strigoi-merger`. Prey without a `symbol` are
skipped. Returns 204; non-success / empty prey acknowledged without persisting.

## Gropar Webhooks

Called by Vistierie during a `gropar` agent run (exit timing for open depot-1
positions). Both require `Authorization: Bearer <DRACUL_GROPAR_WEBHOOK_TOKEN>`.
They are only registered when `DRACUL_GROPAR_ENABLED=true`.

### `POST /api/gropar/tools/fetch-held-positions`

Tool webhook — invoked mid-run by the LLM via Vistierie's tool dispatcher. Returns
every open position on the live depot (`depot-1`), joined by symbol to its
`position_context` row (`HeldPositionService`), with entry price, share count, and
current price. `positionId`/`symbol`/`companyName` are all the depot symbol — a
depot position carries no separate company name or watchlist-item id.

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
    },
    "thesis": {
      "summary": "...", "signals": ["..."], "risks": ["..."],
      "anomalyTypes": ["PEAD"], "horizon": "3m",
      "killCriteria": ["EPS miss next quarter", "Price closes below $120 for 3 sessions"]
    }
  }
] } }
```

`thesis` is only present when the position has an open `position_context` row with a
stored thesis snapshot (captured once, at the point the position was opened/backfilled);
a position with no context row (e.g. opened by the executor before a verdict link
existed) degrades to TA-only — `indicators`/`risk` are still populated, `thesis` is
`null`, and the position is never dropped from the response. `thesis.killCriteria` is
the deduped union of `kill_criteria` across the verdict's contributing prey (resolved at
context-write time, not live), omitted entirely (no empty array) when none of them
declared any.

### `POST /api/gropar/complete`

Completion webhook — invoked by Vistierie's `CompletionWebhookDispatcher` when the
agent run finishes. Persists exit signals to `dracul.exit_signals`.

Headers: `Authorization: Bearer ...`, `X-Vistierie-Run-Id: <run-id>`.

Request body:
```json
{ "run_id": "...", "status": "succeeded",
  "output": { "signals": [
    { "position_id": "...", "symbol": "ACME", "action": "EXIT",
      "thesis_status": "INVALIDATED", "rationale": "...", "confidence": 0.8,
      "gain_loss_pct": -8.4, "fired_rules": ["INITIAL_STOP"],
      "violated_kill_criteria": ["Price closes below $120 for 3 sessions"] }
  ] } }
```

`position_id` is the depot symbol echoed back from `fetch-held-positions`; a
signal whose `position_id` no longer matches an open depot-1 position (closed
since the tool call, or hallucinated) is skipped rather than persisted. Since
depot positions carry no watchlist-item id, `exit_signals.watchlist_item_id`
is always `null` for gropar-sourced signals now — `symbol` is the position's
identity. `violated_kill_criteria` is prompt-enforced (`prompts/gropar.md`):
the LLM must name at least one violated entry whenever `thesis_status` =
`INVALIDATED`, verbatim from the position's `thesis.killCriteria`; omitted
for `INTACT`/`WEAKENING`/`NONE`. When present it is appended to the persisted
`rationale` (`"[Verletzt: ...]"`). Returns 204. If `status != "succeeded"` or
the `output.signals` array is absent, the endpoint acknowledges (204) without
persisting and logs the run-id. Any non-`HOLD` `action` also triggers a
best-effort Telegram push.

## Voievod Webhooks

Called by Vistierie during a `voievod` agent run (consensus synthesizer).
Both require `Authorization: Bearer <VOIEVOD_TOKEN>`; only registered when
`VOIEVOD_ENABLED=true`.

### `POST /api/voievod/tools/fetch-candidates`

Tool webhook — invoked mid-run by the LLM via Vistierie's tool dispatcher.
Returns the current consensus clusters: symbols flagged by ≥2 distinct Strigoi
whose prey are still open (`discoveredAt + horizon ≥ today`).

Request: `{ "run_id": "...", "tool_name": "fetch_consensus_clusters", "input": {} }`

Each cluster is deterministically annotated with a Dracul-domain payoff
taxonomy (never Agora market data) that the Voievod prompt uses to judge
corroboration: `crossFamily` (bool — the contributing prey span more than one
payoff family, a contradiction warning), `payoffFamilies` (the distinct
payoff families present among the cluster's prey: `DRIFT` / `EVENT` /
`UNKNOWN`), and `discoverySpreadDays` (days between the earliest and latest
`discoveredAt` in the cluster). Each prey also carries its own
`payoffFamily` (`DRIFT` / `EVENT` / `UNKNOWN`, derived from `anomalyType`).
This is advisory annotation only — it never drops a cluster; the LLM alone
decides whether to endorse or drop.

Response:
```json
{
  "output": {
    "clusters": [
      {
        "symbol": "ACME",
        "companyName": "Acme Corp",
        "crossFamily": false,
        "payoffFamilies": ["DRIFT"],
        "discoverySpreadDays": 2,
        "prey": [
          {
            "discoveredBy": "strigoi-insider",
            "anomalyType": "INSIDER_CLUSTER",
            "payoffFamily": "DRIFT",
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

## Voievod-Outcome Webhooks

Called by Vistierie during a `voievod-outcome` agent run (elapsed-hunt pattern
reviewer — a separate agent from `voievod` above). Requires `Authorization: Bearer
<DRACUL_VOIEVOD_OUTCOME_TOKEN>`; only registered when
`DRACUL_VOIEVOD_OUTCOME_ENABLED=true`. Runs weekly (default cron `0 0 7 * * 6`, UTC).

### `POST /api/voievod-outcome/tools/fetch-elapsed-prey`

Tool webhook. Returns prey whose horizon elapsed more than 30 days ago
(`!Horizons.isOpen(discoveredAt, horizon, today.minusDays(30))`) and that has not yet
been reviewed, oldest-discovered first, capped at 25 prey per run.

Request: `{ "run_id": "...", "tool_name": "fetch_elapsed_prey", "input": { "lookback_days": 90 } }`

`input.lookback_days` is optional — when present it additionally bounds the scan to
prey discovered within that many days of now.

Response:
```json
{
  "output": {
    "prey": [
      {
        "symbol": "ACME",
        "anomalyType": "SPINOFF",
        "thesis": "...",
        "killCriteria": ["Close below 42.50"],
        "discoveredAt": "2026-01-15T08:00:00Z",
        "horizon": "3m",
        "ohlc": { "firstClose": 40.10, "lastClose": 51.30, "minClose": 38.20, "maxClose": 53.00 }
      }
    ],
    "cap": 25,
    "capped": false
  }
}
```

`ohlc` is the daily close history since discovery (`AgoraMarketData.dailyOhlcHistory`,
window sized from `discoveredAt` to today, capped at 730 days), condensed server-side
to first/last/min/max closes so `firstClose` reflects the discovery-time price — the full
daily series is never shipped (token budget). When Agora is unavailable for a symbol,
`ohlc` degrades to `{}` and the prey is still returned (fail-soft). Every prey returned
in the response is marked reviewed at fetch time (`prey.outcome_reviewed_at`) so a
re-run never re-surfaces it.

### `POST /api/voievod-outcome/complete`

Completion webhook — persists the agent's proposed lessons as PENDING `patterns`
rows. Requires `status: "done"` or `"succeeded"`; any other status is acknowledged
(204) without persisting.

Request (per the agent's output schema, `schemas/voievod-outcome.json`):
```json
{
  "status": "done",
  "output": {
    "patterns": [
      { "applies_to_strigoi": "strigoi-spin",
        "statement": "Tech spin-offs outperform industrial spin-offs",
        "evidence_symbols": ["GEHC", "KVUE", "SOLV"] }
    ]
  }
}
```

For each entry, a `patterns` row is inserted with `status = 'PENDING'`,
`evidence_count = evidence_symbols.length`, and `user_id = 'default'` (single-user
system, same default used by the fetch endpoint). A proposal is skipped when a
PENDING pattern with an identical `statement` already exists for the user
(dedupe on repeated/overlapping runs). Returns 204 either way; the existing
Chronicle pattern-review UI (approve/reject) picks up PENDING patterns
automatically — no frontend changes needed.

## Strigoi-Index Webhooks

Called by Vistierie during a `strigoi-index` agent run (index-inclusion drift).
Both require `Authorization: Bearer <STRIGOI_INDEX_TOKEN>`; only registered when
`STRIGOI_INDEX_ENABLED=true`.

### `POST /api/strigoi-index/tools/fetch-candidates`

Tool webhook (`fetch_index_reconstitution_events`). Returns tracked index-reconstitution
events as DB-backed lifecycle rows (`index_event`, V27) — not the live constituents list.
Each hunt runs four phases on the webhook cron: **INGEST** announced changes for S&P 500 +
Russell 1000/2000 (idempotent upsert on the natural key), **RECONCILE** pure-calendar stage
transitions (ANNOUNCED → EFFECTIVE → POST → CLOSED/ABANDONED; zero Agora calls), **ENRICH**
per-stage snapshots over a bounded, fail-soft work queue, **RESPOND** with the unpromoted
{ANNOUNCED, EFFECTIVE, POST} rows. The thesis is forced index-fund demand inside the
announcement→effective window (the logic-flip: judge `today → effectiveDate`, not a past
`dateAdded`).

Request: `{ "run_id": "...", "tool_name": "fetch_index_reconstitution_events", "input": { "lookback_days": 30 } }`

Input `lookback_days` range: 1–90; default 30.

Response:
```json
{ "output": { "candidates": [
  { "symbol": "ACME", "companyName": "Acme Corp", "index": "sp500",
    "action": "add", "source": "sp_press",
    "announcementDate": "2026-05-15", "effectiveDate": "2026-05-22", "status": "ANNOUNCED",
    "adv": 48250000.00, "marketCap": 12500000000.0, "avgVolume20d": 950000,
    "idiosyncraticVol": 0.018, "freeFloatProxyMillions": 8200.0,
    "demandToAdvRatioEstimate": 3.4, "confounders": [],
    "runUpPct": null, "postEffectivePct": null, "reversalObserved": null,
    "daysSinceEffective": null }
] } }
```

Fields are **stage-gated** and additive — a row progresses ANNOUNCED → EFFECTIVE → POST and
each stage fills its own block, so a `null` field means "that stage's data isn't available
yet", never a judgement. Base/identity (`symbol`, `companyName`, `index`, `action` add/remove,
`source` `sp_press`/`russell_reconstitution`, `announcementDate`, `effectiveDate`, `status`)
replaces the old single `dateAdded` anchor. ANNOUNCED-stage demand proxies — `adv` (20d avg
daily dollar volume, close × volume), `marketCap`, `avgVolume20d` (20d avg daily share
volume), `idiosyncraticVol`, `freeFloatProxyMillions`, `demandToAdvRatioEstimate`,
`confounders` — and EFFECTIVE/POST-stage drift fields — `runUpPct` (announcement→effective),
`postEffectivePct` (effective→latest), `reversalObserved`, `daysSinceEffective` — are all
coarse proxies/observations (so named), used as prompt-side confidence signals, not gates. Any
may be `null` per-row on a data-source failure (fail-soft); no event is dropped for it.

### `POST /api/strigoi-index/complete`

Completion webhook. Headers: `Authorization: Bearer ...`, `X-Vistierie-Run-Id: ...`.
On success (`status` = `done`) persists each `output.prey[]` entry as Prey with
`anomalyType=INDEX_INCLUSION`, `discoveredBy=strigoi-index`. Prey without a `symbol` are
skipped. Returns 204; non-success / empty prey acknowledged without persisting.

## Executor Webhooks

Called by Vistierie during an `executor` agent run (guarded broker execution,
see `documentation/strigoi.md`). All nine (8 tool webhooks + completion) require
`Authorization: Bearer <DRACUL_EXECUTOR_WEBHOOK_TOKEN>` (verified with
constant-time comparison via `BearerTokenVerifier`); a missing/wrong token
returns 401. Only registered when `dracul.executor.enabled=true`. Unlike the
operator endpoints above, these sit **outside** Cloudflare Access — they are
machine-to-machine, called in-cluster by Vistierie's tool dispatcher, the
same pattern as `/api/strigoi-*`, `/api/voievod`, `/api/gropar`.

### `POST /api/executor/tools/fetch-pending-signals`

Tool webhook. Returns up to 50 `PENDING` `ExecutorSignal` rows, **ranked**
by `SignalRanker` rather than in raw insertion/creation order. The agent
works the queue top-down and stops once it fills its position budget for
the run, so this order directly decides which candidates ever get a look.
Priority (highest first):

1. Mechanism diversity — signals whose `mechanism` is not already
   represented among the currently open positions rank above ones that
   are (avoid piling into the same anomaly type).
2. `confidence`, descending.
3. `createdAt`, descending (freshest first — most remaining runway for
   time-decaying anomalies such as PEAD or index-inclusion drift).

Each signal is enriched server-side with `atr`/`swing_low` (via
`ExecutorIndicators`, periods `dracul.executor.atr-period`/`dracul.executor.
swing-period`, default 22/20) and `reference_price`. When indicators are
unavailable (e.g. insufficient price history or Agora unreachable), `atr`
and `swing_low` are `null` and `reference_price` falls back to the signal's
stored value. The LLM is not expected to call any market-data tool itself
for this context — it is fetched once per signal by the controller.

Response:
```json
{ "output": { "signals": [
  { "signal_id": "...", "symbol": "ACME", "direction": "LONG", "confidence": 0.8,
    "mechanism": "...", "kill_criteria": ["..."], "horizon": "3m",
    "atr": 4.2, "swing_low": 138.00, "reference_price": 142.50 }
] } }
```

### `POST /api/executor/tools/get-account`

Tool webhook. Input: `{ "connection": "depot-1" }` (optional; defaults to
`dracul.executor.connection`). Proxies Agora's `get_account` trading tool via
`AgoraTrading`. Response: `{ "output": <account snapshot> }`, or
`{ "output": { "available": false, "error": "..." } }` if Agora/the broker
call fails.

### `POST /api/executor/tools/list-positions`

Tool webhook. Same input/connection resolution as `get-account`. Proxies
Agora's `list_positions` trading tool. Response: `{ "output": <positions
snapshot> }`, or the same `available:false` error shape on failure.

### `POST /api/executor/tools/place-entry`

Tool webhook — the safety-critical core. The LLM only *requests* an entry;
code decides. Input:

```json
{
  "signal_id": "...",
  "symbol": "ACME",
  "side": "BUY",
  "limit_price": 142.50,
  "stop_price": 138.00,
  "take_profit": 150.00
}
```

No `qty` field — sizing is entirely server-side (`PositionSizer`): the
tranche notional (`dracul.executor.total-budget` / `dracul.executor.
tranche-count`, FX-converted to instrument currency) divided by the order
price, floored to whole shares. `limit_price` and `take_profit` are
optional; `stop_price` is required and is code-checked against a stop
window before the order reaches `OrderGuard` — BUY: between
`price - 3×ATR - 0.25×ATR` and `min(price - 2.5×ATR, swing_low)`; SELL
mirrored on the upside. A `stop_price` outside the window fails as `NO_STOP`
regardless of what the LLM proposed; the LLM is expected to choose *inside*
the window (typically 2.5–3× ATR, or the recent swing low), never at its
edges. `take_profit` is genuinely optional to the LLM: if omitted, the
controller synthesizes a wide 3R target from `stop_price`/order price so
the bracket is always valid (Agora's `place_bracket` requires a take-profit
leg) — the strategy's real exits are the trailing chandelier / giveback
stops, not this fixed target, so it is intentionally wide and rarely fills.

The **order-price basis** used throughout (sizing, stop-window check,
take-profit synthesis, position booking) is a single value: `limit_price`
when the LLM supplies one, otherwise the freshly assembled current close
(`EntryContext.price()`) — never the signal's original, potentially stale,
`reference_price`.

Pipeline: signal lookup → `EntryContextAssembler` (single I/O layer: Agora
indicators/company-profile, FX, account, repos) → `VetoService` (15-veto
catalog, preceded by the `DATA_UNAVAILABLE` pre-veto) → `PositionSizer` →
`OrderGuard` → `AgoraTrading` (only on pass). Every step short-circuits
before the broker call. On success:

```json
{ "output": { "placed": true, "broker_order_id": "...", "position_id": 42 } }
```

On rejection: `{ "output": { "placed": false, "reason": "<REASON>", "veto_trace"?: [...] } }`.
`veto_trace` is present for veto rejections (one entry per catalog check, in
catalog order, e.g.
`["SCHEMA_INVALID:PASS","LOW_CONFIDENCE:PASS",...,"MAX_POSITIONS:FAIL",...]`)
and for order-guard rejections it is the veto trace plus an
`ORDER_GUARD:<reason>` entry. `reason` is one of:

| Reason | Where enforced | Meaning |
|---|---|---|
| `DATA_UNAVAILABLE` | `VetoService` (pre-veto) | Mandatory upstream data (account, price, ATR, ADV20 notional, sector, signal age/reference) was missing at `EntryContext` assembly time — short-circuits every other veto; the executor never trades blind |
| `SCHEMA_INVALID` | `VetoService` / `OrderGuard` | Signal not found; missing `symbol`/`direction`/`confidence`/`kill_criteria`/`mechanism`/`agent_version`; or malformed `side` |
| `LOW_CONFIDENCE` | `VetoService` | Signal `confidence` below `dracul.executor.min-confidence` (default `0.65`) |
| `COOLDOWN` | `VetoService` | Any active `cooldown` row matches the symbol — a hard block in v1 with no fresh-setup exception (the cooldown's originating mechanism isn't stored, so no rule can safely distinguish "same setup" from "genuinely new"; see `documentation/architecture.md`) |
| `MAX_POSITIONS` | `VetoService` | Open-position count ≥ `dracul.executor.max-positions` |
| `BUDGET` | `VetoService` | Remaining cash or remaining total-budget headroom can't cover one tranche (`dracul.executor.total-budget` / `tranche-count`) |
| `HEAT_LIMIT` | `VetoService` | Open heat (sum of `qty × (entry − active stop)`, account ccy) plus this trade's risk would exceed `dracul.executor.heat-pct` × total budget |
| `CONCENTRATION` | `VetoService` | Open positions in the candidate's sector (via Agora company-profile lookup, case-insensitive) already ≥ `dracul.executor.max-per-sector` |
| `CORRELATED` | `VetoService` | An open position exists in the same sector (case-insensitive) AND with the same `mechanism` (anomaly type) as the candidate signal — blocks doubling up on the same anomaly within a sector even below the `CONCENTRATION` cap; null sector or mechanism passes (fail-soft) |
| `CONTRADICTION` | `VetoService` | A `MERGER_ARB` signal/position and a `PEAD`/`SPINOFF`/`INSIDER_CLUSTER`/`INDEX_INCLUSION`/`QUALITY_52W_LOW` signal/position collide on the same symbol (checked against other pending signals and open-position mechanisms); both pending signals in a contradicting pair are rejected, and the audit row for the other signal notes the pairing |
| `REDUNDANCY` | `VetoService` | An open position on the same symbol already originates from the same `mechanism` |
| `LIQUIDITY` | `VetoService` | Price below `dracul.executor.min-price` (USD-equivalent), or ADV20 notional below `dracul.executor.adv-multiple` × the tranche amount |
| `SIGNAL_EXPIRED` | `VetoService` | Signal age (trading days since `createdAt`) exceeds `dracul.executor.max-signal-age-days` |
| `CHASED_AWAY` | `VetoService` | Current price has moved more than `dracul.executor.chase-atr-mult` × ATR beyond the signal's reference price |
| `BELOW_ANCHOR` | `VetoService` | The effective order price is on the invalidating side of the signal's reference-price anchor — drift mechanisms (`PEAD`/`INDEX_INCLUSION`) use `dracul.executor.drift-anchor-atr-mult` (default `0.0`×ATR, i.e. no adverse move tolerated), value mechanisms use `dracul.executor.value-anchor-atr-mult` (default `3.0`×ATR) |
| `PACE_LIMIT` | `VetoService` | New positions entered this ISO calendar week already ≥ `dracul.executor.pace-per-week` |
| `TRANCHE_TOO_SMALL` | `ExecutorWebhookController` | `PositionSizer` computed a zero quantity (tranche amount doesn't buy even one share at the order price) |
| `NO_STOP` | `OrderGuard` | `stop_price` missing/non-positive, on the wrong side of the order price for `side`, or outside the sizer-computed stop window |
| `NON_SIM_CONNECTION` | `OrderGuard` | The configured connection is not the allowed (paper) connection — not reachable through this controller today since `place-entry` always trades on the server-fixed `dracul.executor.connection`, but enforced defensively |
| `DUPLICATE` | `ExecutorWebhookController` | The signal is no longer `PENDING` (already `ACCEPTED`/`REJECTED`/`SKIPPED`) — idempotency guard, checked before vetos/order guard; no broker call, no signal-status change |
| `BROKER_ERROR` | `AgoraTrading` call | The Agora trading webhook call failed or returned `available:false` |
| `UNKNOWN_VERSION` | `PreySignalEmitter` (intake, not a `VetoService` entry) | The mapped signal's `agent_version` is neither a `PromptRegistry`-known prompt-body hash nor the emitting agent's current live DB version (`AgentVersionResolver.versionFor`) — the signal is dropped before it is ever inserted as `executor_signal`, so it produces no audit row and no `veto_trace` entry; `operator`-sourced (manual) signals are exempt since they carry no prompt hash |

Every outcome (accepted or rejected) writes one `executor_decision` audit
row; accepted entries also insert an `executor_position` row and mark the
source signal `ACCEPTED` (rejections mark it `REJECTED`).

### `POST /api/executor/tools/submit-decision`

Tool webhook. Records SKIP decisions the LLM made for signals it chose not
to act on (ENTER decisions are recorded implicitly via `place-entry`).
Input:

```json
{ "decisions": [
  { "action": "SKIP", "signal_id": "...", "symbol": "ACME", "rationale": "..." }
] }
```

Non-`SKIP` actions in the array are ignored. Each recorded `SKIP` writes an
`executor_decision` row (`accepted=false`, no `reject_reason`) and marks the
signal `SKIPPED`. Response: `{ "output": { "recorded": <count> } }`.

### `POST /api/executor/tools/fetch-open-positions`

Tool webhook (slice 2). Before returning positions, runs the full exit
lifecycle server-side for the configured connection: `ReconcileService`
(sync broker fills, retire closed positions, apply cooldown), then
`EntryExpiryService` (cancel — never re-price — unfilled GTD entries past
their expiry, see below), then `HardTriggerService` (stop-breach / giveback
hard exits — always enforced, never the LLM's call), then
`StopRatchetService` (ratchet the active stop up to the chandelier level).
Only after that does it read back the still-open `executor_position` rows
and enrich each with price/ATR/chandelier/R/MFE via the maintenance
pipeline. Response:

```json
{ "output": { "positions": [
  { "symbol": "ACME", "signal_id": "sig-123", "side": "BUY", "qty": 10, "entry_filled": true,
    "entry_price": 142.50,
    "active_stop": 138.90, "current_price": 151.20, "atr": 4.2,
    "chandelier_level": 138.90, "r_current": 1.98, "mfe_r": 2.30,
    "days_held": 6, "kill_criteria": ["..."],
    "trim_count": 0, "suggested_fraction": 0.33,
    "soft_trigger": { "chandelier_breach": false, "ma_break": false, "confirm_count": 1,
      "kill_criteria_breached": [] },
    "tranche2": { "eligible": true, "reason": "R_CONFIRMED" } }
] } }
```

`signal_id` is the position's source signal id (`ExecutorPosition.sourceSignalId()`,
null-safe — `null` when the position has none), so the LLM can copy it verbatim
into a Tranche 2 `ADD_TRANCHE`/`HOLD` decision record without a separate lookup.

`entry_filled` is false while the position's GTD limit entry has no confirmed
fill at the broker (no holdings yet): hard exits, stop ratcheting and
soft-confirm accumulation are all suspended for it, and `exit-position`
rejects it with `NOT_FILLED` — the position is awaiting its fill or the GTD
expiry.

`trim_count` and `suggested_fraction` feed `exit-position`'s scale-out trim
ladder (see below): `suggested_fraction` is the code-computed ladder floor
for the position's current `trim_count` (0 → 0.33, 1 → 0.5, ≥2 → 1.0) — the
minimum `fraction` `exit-position` will accept for a partial exit right now.

`soft_trigger.confirm_count` is the number of consecutive runs a soft-exit
condition (`chandelier_breach` or `ma_break`) has held; the LLM is expected
to act once it reaches `dracul.executor.soft-confirm-min`.

`soft_trigger.kill_criteria_breached` is the subset of `kill_criteria` that
`KillCriteriaEvaluator` deterministically matched against the current close
— v1 recognizes only absolute price-level criteria ("close below 90",
"rises above 120"); percent thresholds and qualitative criteria are left
unparsed for the LLM to judge from the raw `kill_criteria` list.

`tranche2` (`Tranche2Detector`, pure decision logic, no I/O) reports whether
this tranche-1 position is eligible for a second tranche via `add-tranche`.
`eligible` is `false` whenever price has ever moved against entry (BUY:
below entry; SELL: above entry — no averaging down, ever, regardless of any
other condition). Once past that gate, `reason` is the first of three
conditions to match:

| Reason | Condition |
|---|---|
| `R_CONFIRMED` | Price has moved ≥ 1R (initial per-share risk) in the position's favor |
| `NEW_HIGH` | Price has extended past the entry-day extreme (`entry_day_high`) |
| `REINFORCING_SIGNAL` | A pending signal for the same symbol/direction originates from a mechanism different from the one that opened the position (unavailable if the position's own mechanism is unknown) |

`reason` is `null` when `eligible` is `false`.

**Entry GTD expiry (`EntryExpiryService`).** Every `place-entry` sets
`entry_expires_at` to `dracul.executor.entry-gtd-days` trading days out (see
`documentation/configuration.md`). On each maintenance pass,
`EntryExpiryService` (wired into the pipeline right after `ReconcileService`,
so it sees freshly-reconciled fill state) looks up any `OPEN` position past
its `entry_expires_at` and reads the entry order's status from the same
broker source `ReconcileService` uses (`ExecutionGateway.orders`):

| Entry order status | Effect |
|---|---|
| `WORKING` (0 filled) | Cancels the order (`ExecutionGateway.cancelOrder`), marks the position `CANCELLED` (`ExecutorPositionRepository.markCancelled`), marks the source signal `EXPIRED` (skipped if the position has no `source_signal_id`) |
| `PARTIALLY_FILLED` | Cancels only the unfilled remainder; the position **stays OPEN** with its (reconciled) quantity — this service only ever cancels, never re-prices or re-sizes |
| `FILLED`, or the order can no longer be found (status unavailable) | No action this run |

Both the full-cancel and partial-cancel paths write one `decision_log` row
(`trigger_type=MAINTENANCE`, `action=CANCEL_EXPIRED`,
`reason_code=SIGNAL_EXPIRED`, `order_json={partial: <bool>}`) — `partial` is
`true` only for the `PARTIALLY_FILLED` case. On `BrokerUnavailableException`
the book is left untouched and a `trigger_type=MAINTENANCE`,
`action=ESCALATE`, `reason_code=BROKER_UNAVAILABLE` row is written instead,
mirroring `ReconcileService`'s/`HardTriggerService`'s broker-outage idiom.

Both cancel paths also clear `entry_expires_at` afterwards, making the
expiry one-shot by construction (the expiry query filters on
`entry_expires_at IS NOT NULL`) — a partially-filled entry whose remainder
was already cancelled is never re-cancelled or re-logged, even if the broker
keeps reporting the order as `PARTIALLY_FILLED`. Positions fully cancelled
by the expiry step are dropped from the pipeline's in-memory survivor list
before `HardTriggerService` runs, so a just-cancelled unfilled position can
never be hard-triggered/flattened in the same pass. Conversely,
`ReconcileService` recognizes a book row whose ENTRY order is still
`WORKING`/`PARTIALLY_FILLED` at the broker (no broker position yet) and
keeps it OPEN instead of closing it as `RECONCILE_GONE` — the GTD expiry
service owns that lifecycle.

### `POST /api/executor/tools/exit-position`

Tool webhook (slice 2) — the LLM's soft-judgment exit; unlike `place-entry`,
exits are **always permitted** (no veto/order-guard gate). Supports both a
full close and a partial scale-out. Input:

```json
{ "symbol": "ACME", "reason": "soft trigger confirmed", "confidence": 0.75,
  "reasoning": "...", "fraction": 0.33 }
```

`fraction` is optional; omitted or `1.0` means a full close (the
pre-scale-out behavior, unchanged). Only `0.33`, `0.5`, and `1.0` (or `null`)
are valid per the tool's input schema — any other value is rejected without
a broker call.

**Scale-out / trim ladder.** A partial `fraction` (`0.33` or `0.5`) is
code-gated by an escalation ladder keyed on the position's persisted
`trim_count` (surfaced by `fetch-open-positions`' `trim_count` /
`suggested_fraction` fields):

| `trim_count` | Ladder floor |
|---|---|
| 0 | 0.33 |
| 1 | 0.5 |
| ≥ 2 | 1.0 (must fully flatten) |

The LLM may always exit **more** aggressively than the floor (e.g. skip
straight to `1.0`), but never less — a `fraction` below the current floor is
rejected with `{ "output": { "exited": false, "reason": "SCHEMA_INVALID",
"reasoning": "ladder floor is <floor> (trim_count=<n>); fraction <f> would
undercut it" } }`, no broker call.

For a valid partial trim: looks up the open `executor_position` for `symbol`
on the configured connection, flattens `fraction` of it via `AgoraTrading`'s
broker gateway, computes the remaining quantity as `qty × (1 − fraction)`
floored to whole shares, and persists it via
`ExecutorPositionRepository.recordTrim` (bumps `trim_count`, resets
`soft_confirm_count` to 0). The position **stays OPEN** — no cooldown row is
added. Writes one `decision_log` row (`trigger_type=SOFT_TRIGGER`,
`action=TRIM`, `reason_code=null`, `order_json={fraction, qty_closed,
qty_remaining}`). Response:

```json
{ "output": { "exited": false, "trimmed": true, "fraction": 0.33,
  "qty_closed": 3, "qty_remaining": 6 } }
```

If the computed remaining quantity would drop below one whole share, the
trim is treated as a full exit instead (fraction-1.0 semantics below) —
there is no such thing as a sub-1-share open position.

For a full exit (`fraction` absent or `1.0`): computes realized R, closes
the position row, and adds a `cooldown` entry (`dracul.executor.cooldown-days`,
"fresh setup only"). Writes one `decision_log` row (`action=EXIT_FULL`).
Response:

```json
{ "output": { "exited": true, "exit_reason": "soft trigger confirmed" } }
```

or on failure: `{ "output": { "exited": false, "reason": "NO_OPEN_POSITION" } }`
(no open position for `symbol`), `{ "output": { "exited": false, "reason": "NOT_FILLED" } }`
(the position's GTD entry has no confirmed fill — nothing to flatten; a
`decision_log` REJECT/NOT_FILLED row is written), or
`{ "output": { "exited": false, "reason": "BROKER_ERROR" } }`
(the broker flatten call failed/unreachable).

### `POST /api/executor/tools/add-tranche`

Tool webhook — adds a code-verified second tranche to an open tranche-1
position. Like `place-entry`, the LLM only *requests* the add; the server
re-verifies eligibility, sizing, heat and budget from scratch (it never
trusts the `tranche2` block a prior `fetch-open-positions` call may have
shown the LLM). Input:

```json
{ "symbol": "ACME", "reason": "R_CONFIRMED" }
```

Pipeline: open-position lookup → tranche-cap guard (`position.tranche() >=
dracul.executor.max-tranche` rejects with `MAX_TRANCHE` before any further
I/O) → `EntryContextAssembler.assembleForSymbol`
(same I/O as `place-entry`, but `signal_reference`/`signal_age` are not
mandatory here — there is no pending signal to check freshness against) →
`Tranche2Detector.detect` (re-derives eligibility; does not trust the
caller-supplied `reason`) → `PositionSizer.size` (reusing the position's
**existing active stop**, not a freshly recomputed one — the ATR/swing
levels have moved since tranche 1, but the stop is a single, per-position
line the ratchet already tracks) → heat check (mirrors `HEAT_LIMIT`) →
budget check (mirrors `BUDGET`) → `AgoraTrading`. On success, the bracket
reuses the active stop, and the position row is updated in place
(`tranche=2`, quantity summed, entry price re-weighted to the
qty-weighted average of both tranches, `tranche2_order_id`/
`tranche2_stop_order_id` recorded so the stop ratchet moves both legs):

```json
{ "output": { "placed": true, "qty": 5, "reason": "R_CONFIRMED" } }
```

On rejection: `{ "output": { "placed": false, "reason": "<REASON>" } }`, where
`reason` is one of:

| Reason | Meaning |
|---|---|
| `NO_POSITION` | No open tranche-1 position for `symbol` on the configured connection |
| `MAX_TRANCHE` | Position's current `tranche` count is already at or above `dracul.executor.max-tranche` |
| `DATA_UNAVAILABLE` | Mandatory upstream data missing at assembly time |
| `NOT_ELIGIBLE` | `Tranche2Detector` re-derived ineligibility (e.g. price has moved against entry, or none of `R_CONFIRMED`/`NEW_HIGH`/`REINFORCING_SIGNAL` currently holds) |
| `TRANCHE_TOO_SMALL` | Sizer computed less than one whole share for the second tranche |
| `HEAT_LIMIT` | Adding this tranche's risk would exceed the heat cap |
| `BUDGET` | Remaining cash or budget headroom can't cover the tranche |
| `BROKER_ERROR` | The Agora trading webhook call failed |

Every outcome writes one `executor_decision` audit row (no `submit-decision`
call needed for tranche-2 adds).

### `POST /api/executor/complete`

Completion webhook — invoked by Vistierie's `CompletionWebhookDispatcher`
when the agent run finishes. Slice 1 only verifies the bearer token and
acknowledges (204); the run's actual actions were already persisted
incrementally by the tool webhooks above (`place-entry` / `submit-decision`),
so there is nothing left to write here.
