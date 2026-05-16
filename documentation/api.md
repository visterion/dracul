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
| GET | `/api/verdicts` | List verdicts; query params: `from`, `to`, `minConsensus`, `page`, `size` |
| GET | `/api/verdicts/{id}` | Single verdict with all contributing prey |

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
| POST | `/api/watchlist` | Add an instrument to the watchlist |
| PATCH | `/api/watchlist/{id}` | Update position details or notes |
| DELETE | `/api/watchlist/{id}` | Remove from watchlist (sets `removed_at`) |

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
