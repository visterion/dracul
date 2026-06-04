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
| POST | `/api/verdict/{id}/notes` | Append a note to a verdict timeline |
| GET | `/api/verdict/{id}/notes` | List notes for a verdict (DESC by createdAt) |

## Strigoi

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/strigoi` | Roster + last-run status (proxied from Vistierie) |
| GET | `/api/strigoi/{name}` | Single Strigoi: runs, stats, configuration |
| POST | `/api/strigoi/{name}/hunt` | Trigger manual one-off hunt (proxied to Vistierie) |

## Watchlist

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/watchlist` | All active watchlist items for the current user |
| POST | `/api/watchlist` | Add ticker to watchlist; resolves market data synchronously |
| PATCH | `/api/watchlist/{id}` | Update tag (HELD/TRACKING) |
| DELETE | `/api/watchlist/{id}` | Remove watchlist item |

## Daywalker Alerts

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/alerts` | Recent alerts; query params: `instrument`, `severity`, `from`, `size` |
| POST | `/api/alerts/{id}/dismiss` | Mark alert as user-dismissed |

## Pattern Library

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/patterns` | All patterns; query params: `strigoi`, `status` |
| POST | `/api/patterns/{id}/approve` | Activate a `PENDING` pattern |
| POST | `/api/patterns/{id}/reject` | Reject with optional `reason` body |
| POST | `/api/patterns/{id}/defer` | Move back to pending for later review |
| DELETE | `/api/patterns/{id}` | Deactivate an `ACTIVE` pattern |

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

## Admin

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/admin/kill` | Flip the Vistierie kill switch for the `dracul` tenant |

## Live Updates (SSE)

| Path | Events pushed |
|---|---|
| `/api/events` | `prey.new`, `verdict.new`, `alert.new`, `strigoi.status`, `cost.update` |

The Chronicle frontend subscribes to this endpoint and updates in real
time without polling.

## Authentication

Phase 1: single bearer token (`DRACUL_API_TOKEN` env var). All endpoints
require `Authorization: Bearer <token>`. Phase 2 will replace this with
per-user tokens via RBAC.

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
