# Configuration

All properties are set via `application.yml` or environment variables.

## Database

| Variable | Purpose |
|---|---|
| `DRACUL_DB_URL` | Postgres JDBC URL (e.g. `jdbc:postgresql://localhost:5432/dracul`) |
| `DRACUL_DB_USER` | Postgres username |
| `DRACUL_DB_PASSWORD` | Postgres password |

Schema is `dracul`. Flyway migrations run on startup.

## Vistierie connection

| Variable | Purpose |
|---|---|
| `VISTIERIE_URL` | Vistierie base URL (e.g. `http://vistierie:8090`) |
| `VISTIERIE_TENANT_TOKEN` | Bearer token for the `dracul` tenant (tenant endpoints: `/agents`, `/runs`) |
| `VISTIERIE_ADMIN_TOKEN` | Vistierie admin bearer token (admin endpoints: `/admin/*`) |
| `VISTIERIE_CACHE_TTL_SECONDS` | TTL (seconds) for the aggregated `/api/vistierie` cost panel; `0` disables caching (default `30`) |

## Authentication

| Variable | Purpose |
|---|---|
| `DRACUL_API_TOKEN` | Single bearer token for all Chronicle API requests (Phase 1) |

## Cloudflare Access (Zero Trust)

Chronicle sits behind **Cloudflare Access**. The edge enforces an identity login
and injects a signed `Cf-Access-Jwt-Assertion` header; `CloudflareAccessFilter`
verifies it against the team JWKS and the expected audience, then stores the email
in `CurrentUserHolder`.

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_CLOUDFLARE_TEAM_DOMAIN` (`dracul.cloudflare.team-domain`) | _(blank)_ | Full team-domain URL, e.g. `https://<team>.cloudflareaccess.com`. JWKS is fetched from `<team-domain>/cdn-cgi/access/certs`. **Required outside the `dev`/`test` profiles — the app refuses to start if blank.** |
| `DRACUL_CLOUDFLARE_AUD` (`dracul.cloudflare.aud`) | _(blank)_ | Application Audience (AUD) tag of the Cloudflare Access app protecting this host. A token is rejected unless its `aud` claim matches. **Required outside `dev`/`test`.** |
| `DRACUL_CORS_ALLOWED_ORIGINS` (`dracul.cors.allowed-origins`) | `http://localhost:5173` | Comma-separated browser origin(s) allowed to call `/api/**`. Must be the **public SPA origin** in prod (e.g. `https://dracul.ufelmann.com`), NOT `dracul.public-url` (that is the internal `http://dracul:8080` webhook URL). Browsers send an `Origin` header on state-changing methods (POST/PUT/PATCH/DELETE) even same-origin, so an unlisted origin makes Spring reject every write with `403 "Invalid CORS request"` while GET (no Origin header) still works. |
| `DRACUL_LOCAL_ACCESS_ENABLED` (`dracul.local-access.enabled`) | `false` | Master toggle for the local-access Cloudflare-bypass path. Must be `true` **and** `DRACUL_LOCAL_ACCESS_TOKEN` non-blank for local access to be active. |
| `DRACUL_LOCAL_ACCESS_TOKEN` (`dracul.local-access.token`) | _(blank)_ | Shared secret accepted via `X-Local-Access-Token` header, `DRACUL_LAT` cookie, or `?lat=` query param. A blank value keeps local access disabled even when `enabled=true` (fail-safe). A request authenticated this way acts as `dracul.primary-user-email`. |

When **both** values are blank **and** the active profile is `dev` or `test`, the
filter runs in **bypass mode**: it honors an `X-Dev-User` header (falling back to
`default`) instead of verifying a JWT. Machine webhook paths (`/api/strigoi-*`,
`/api/voievod`, `/api/daywalker`, `/api/daywalker-deep`) and `/actuator/health` are always excluded — they
authenticate with their own bearer tokens and are reached in-cluster, bypassing
Cloudflare.

## Market-data adapters

As of slice 7c, hunting fetch (filings, news, recommendations, fundamentals,
earnings, index constituents, intraday) no longer uses any direct-provider
adapter — it routes through Agora (see "Agora (hunting fetch + prices/OHLC)"
below). The rows below now feed **only** the Settings → Data-Sources health
probe (`HttpDataSourceHealthService`), which still pings these providers
directly for a health signal; this probe is flagged stale and slated for
realignment to probe Agora in 7d.

| Variable | Purpose |
|---|---|
| `EDGAR_USER_AGENT` / `DRACUL_EDGAR_USER_AGENT` | User-Agent for the health probe's direct EDGAR ping only. Hunting fetch no longer reads it. |
| `FINNHUB_API_KEY` | Auth for the health probe's direct Finnhub ping only. Hunting fetch no longer reads it. |

## Agora (hunting fetch + prices/OHLC provider)

Dracul no longer talks to market-data or hunting-ground providers directly.
All quotes, daily OHLC history, filings, news, recommendations, fundamentals,
earnings, index constituents, and intraday candles come from **Agora** — a
co-located service Dracul consumes over Agora's MCP front-door
(Streamable-HTTP + Bearer):

- Prices/OHLC: the generic `AgoraClient` and the `AgoraMarketData` facade
  (`get_quote` / `get_ohlc`).
- Hunting fetch: the five domain facades in `de.visterion.dracul.hunting.agora`
  (`AgoraFilings`, `AgoraCompanyData`, `AgoraEarnings`, `AgoraReference`,
  `AgoraIntraday`).

Agora performs provider fallback (EDGAR / Finnhub / Twelve Data / Yahoo /
Wikipedia) internally, so there is no Dracul-side adapter chain any more.

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_AGORA_BASE_URL` (`dracul.agora.base-url`) | `http://agora:8080` | Base URL of Agora's MCP front-door (in-cluster). |
| `DRACUL_AGORA_TOKEN` (`dracul.agora.token`) | _(blank)_ | Bearer token sent on every Agora MCP request. |
| `DRACUL_AGORA_TIMEOUT_MS` (`dracul.agora.timeout-ms`) | `8000` | Request timeout (ms) on the Agora MCP client. |

**Deploy-ordering prerequisite:** Agora must be up **before** Dracul so the
first `get_quote` / `get_ohlc` calls resolve. If Agora is unreachable,
`quotes(...)` returns an empty map (the watchlist keeps its stored prices) and
`resolve` / `dailyOhlcHistory` throw `MarketDataException(UNAVAILABLE)` — the
same degradation contract as the old adapter chain, so scheduled refreshes never
crash.

## Depots (positions view)

Chronicle's `/depots` view and `/api/depots` read Agora's broker-connection
snapshot (account, positions, orders) via a **dedicated, read-only** Agora
client — separate from both the research `AgoraClient` (`dracul.agora.*`)
and the executor's trading client (`dracul.executor.*`). It never places or
modifies an order.

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_DEPOTS_AGORA_BASE_URL` (`dracul.depots.agora-base-url`) | `http://agora:8080` | Base URL of Agora's MCP front-door used for the depot read path (`AgoraDepotClient`). |
| `DRACUL_DEPOTS_AGORA_READONLY_TOKEN` (`dracul.depots.agora-readonly-token`) | _(blank)_ | Bearer token sent on depot read calls. Must be one of Agora's `AGORA_TRADING_LIVE_TOKENS_READONLY` (or a paper-scoped trading token) — a token that can list connections/account/positions/orders but **cannot** place or modify an order. Never reuse a full live trading token here. |
| `DRACUL_DEPOTS_AGORA_TIMEOUT_MS` (`dracul.depots.agora-timeout-ms`) | `8000` | Connect+read timeout (ms) on the depot Agora client. |
| `DRACUL_DEPOTS_LIVE_VISIBLE_EMAILS` (`dracul.depots.live-visible-emails`) | `viktor@ufelmann.de` | Comma-separated, case-insensitive allow-list of Cloudflare-Access emails permitted to see **live**-environment connections (`DepotConnection.environment() == "live"`). This is a server-side gate in `DepotService.isLiveVisible`: paper/sim connections are visible to every authenticated user regardless of this list, but a live connection is filtered out of `GET /api/depots` entirely for any email not on the list (not just hidden in the UI) — an unlisted caller never receives the live depot's account/positions/orders payload. An unauthenticated call (no email resolved) is treated as not-visible for any live connection. |

### `PositionReconciler` (position-context sync)

Keeps `position_context` in sync with the live depot so the depot -- not the
research pipeline -- is the source of truth for "what's currently open".
Runs on a schedule: backfills a context row (verdict-linked when a matching
verdict exists, a minimal `source="none"` row otherwise) for depot positions
that have none yet, and closes context rows whose symbol has left the depot.
Fail-soft: an unreachable depot makes the whole pass a no-op, and a single
symbol's lookup/backfill/close failure is skipped without aborting the rest.

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_POSITION_ENABLED` (`dracul.position.enabled`) | `true` | Master switch for the `PositionReconciler` scheduled job, mirroring the other Strigoi/peer components' `enabled` gate. |
| `DRACUL_POSITION_CONNECTION` (`dracul.position.connection`) | `depot-1` | The depot connection reconciled against (same identifier space as `dracul.executor.connection`). |
| `DRACUL_POSITION_RECONCILE_CRON` (`dracul.position.reconcile-cron`) | `0 0 12 * * *` | Spring cron (server-local zone) for the reconcile pass. Default: once daily at 12:00. |

See `documentation/api.md` for the `/api/depots` and instrument-bundle
endpoint shapes, and `documentation/operations.md` for the required
Agora-side deploy step (`AGORA_TRADING_LIVE_TOKENS_READONLY` +
`saxo-sim`→`depot-1` connection-key rename) that must land alongside these
Dracul env vars.

## Yahoo Finance (FX adapter)

Yahoo is no longer a price/OHLC provider (Agora hosts those), and as of
slice 7c is no longer used for intraday or earnings-calendar fetch either
(both now route through Agora's `AgoraIntraday` / `AgoraEarnings` facades).
The `yahooRestClient` bean and the keys below remain in use for FX only
(out of scope for slice 7c — deferred, see spec §9).

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_MARKETDATA_YAHOO_BASE_URL` (`dracul.marketdata.yahoo.base-url`) | `https://query1.finance.yahoo.com` | Base URL for FX requests. Override for tests. |
| `DRACUL_MARKETDATA_YAHOO_USER_AGENT` (`dracul.marketdata.yahoo.user-agent`) | `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36` | User-Agent sent on FX requests. Yahoo returns HTTP 429 to a **Linux**-Chrome UA (`X11; Linux x86_64`) even though it is browser-like, so the default is a **Windows**-Chrome UA; both return 200. Override via env only if Yahoo changes its heuristics. |
| `DRACUL_MARKETDATA_YAHOO_TIMEOUT_MS` (`dracul.marketdata.yahoo.timeout-ms`) | `5000` | Connect + read timeout (ms) on the Yahoo client so a slow Yahoo can't stall a request. |
| `DRACUL_MARKETDATA_FX_REFRESH_ENABLED` (`dracul.marketdata.fx-refresh.enabled`) | `true` | Background FX-rate warm-up. Watchlist/depot currency conversion is served from this warmed cache and never does a live fetch in the request path. |
| `DRACUL_MARKETDATA_FX_REFRESH_INITIAL_DELAY_MS` (`dracul.marketdata.fx-refresh.initial-delay-ms`) | `0` | Delay (ms) before the first FX warm-up run after startup. |
| `DRACUL_MARKETDATA_FX_REFRESH_FIXED_DELAY_MS` (`dracul.marketdata.fx-refresh.fixed-delay-ms`) | `1800000` | Interval (ms) between FX warm-up runs (30 min). |

## Notifications

| Variable | Default | Purpose |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | _(blank)_ | Telegram bot token for Daywalker push alerts. Blank disables push. |
| `TELEGRAM_CHAT_ID` | _(blank)_ | Target chat / user id. Blank disables push. |
| `TELEGRAM_BASE_URL` | `https://api.telegram.org` | Override for tests. |
| `DRACUL_DAYWALKER_NOTIFY_LEVEL` | `CRITICAL` | Minimum alert severity that triggers a Telegram push (INFO / WARNING / CRITICAL). |

Push is best-effort: a failed or disabled send never blocks alert persistence; the
outcome is recorded in `daywalker_alerts.notification_sent`.

## Daywalker

| Env var | Default | Purpose |
|---|---|---|
| `DRACUL_DAYWALKER_ENABLED` | `false` | Register the agent + activate the webhook controller (`@ConditionalOnProperty`) |
| `DRACUL_DAYWALKER_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for the event-source + completion webhooks. **Change in production.** |
| `DRACUL_DAYWALKER_SESSION_CRON` | `0 30 13 * * 1-5` | StreamingBee session-open cron (sec min hour dom mon dow), UTC. Default ≈ US market open (EDT). |
| `DRACUL_DAYWALKER_SESSION_DURATION` | `23400` | Session window length in seconds (6.5 h) |
| `DRACUL_DAYWALKER_POLL_INTERVAL` | `300` | Event-source poll cadence in seconds (5 min) |
| `DRACUL_DAYWALKER_PRICE_SPIKE` | `0.03` | PRICE_SPIKE threshold (fraction) |
| `DRACUL_DAYWALKER_VOLUME_MULT` | `3.0` | VOLUME_SPIKE multiple of rolling average |
| `DRACUL_DAYWALKER_COOLDOWN` | `3600` | Per-`(symbol, trigger_type)` suppression window in seconds (60 min) |
| `DRACUL_DAYWALKER_ESCALATION_ENABLED` | `true` | Master toggle for the `daywalker-deep` reasoning-tier second-opinion escalation (see `documentation/strigoi.md`). Escalation only actually fires when `DRACUL_DAYWALKER_DEEP_ENABLED` is **also** `true` — the gate checks both flags. |
| `DRACUL_DAYWALKER_ESCALATION_CONFIDENCE` | `0.6` | A CRITICAL assessment escalates only when its `confidence` is strictly below this threshold. |

Daywalker reuses `DRACUL_PUBLIC_URL` (webhook callback base URL).

**DST caveat:** the session cron is a fixed UTC expression, so it drifts ~1h
against US market open across the EST/EDT boundary. A calendar-aware open is
deferred.

## Daywalker-Deep (reasoning-tier escalation agent)

| Env var | Default | Purpose |
|---|---|---|
| `DRACUL_DAYWALKER_DEEP_ENABLED` | `false` | Register the agent + activate `/api/daywalker-deep/complete` (`@ConditionalOnProperty`) |
| `DRACUL_DAYWALKER_DEEP_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for the completion webhook. **Change in production.** |

Trigger-only agent (`schedule=null`) — see the escalation-flow config above
(`DRACUL_DAYWALKER_ESCALATION_ENABLED` / `DRACUL_DAYWALKER_ESCALATION_CONFIDENCE`)
for when it fires. **`DaywalkerCompletionService` gates escalation on both
`DRACUL_DAYWALKER_ESCALATION_ENABLED` and `DRACUL_DAYWALKER_DEEP_ENABLED`** — since
the latter defaults to `false`, escalation is a no-op out of the box even though
its own toggle defaults to `true`; enable both to actually trigger `daywalker-deep`
runs. Like every Vistierie agent it also needs a budget set once via the admin
endpoint before it can run — see `documentation/operations.md`'s Agent budget guard
section.

## Watchlist price refresh

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_WATCHLIST_PRICE_REFRESH_ENABLED` (`dracul.watchlist.price-refresh.enabled`) | `true` | Enables the background scheduler that refreshes watchlist prices into the DB. |
| `DRACUL_WATCHLIST_PRICE_REFRESH_CRON` (`dracul.watchlist.price-refresh.cron`) | `0 * 13-20 * * MON-FRI` | Spring cron (UTC) for the refresh. Default = every minute during the US session (EDT 13:30–20:00 UTC; widen to `13-21` for EST winter). |

## Finnhub

Daywalker's news + recommendation triggers now fetch via Agora
(`AgoraCompanyData.news` / `recommendations`), not Finnhub directly. The
variables below feed **only** the Settings → Data-Sources health probe
(flagged stale, slated for 7d realignment to probe Agora).

| Variable | Default | Purpose |
|---|---|---|
| `FINNHUB_API_KEY` | _(blank)_ | Auth for the health probe's direct Finnhub ping only. |
| `FINNHUB_BASE_URL` | `https://finnhub.io/api/v1` | Health-probe Finnhub base URL override (tests). |

## Strigoi schedules

Each Strigoi has a cron expression. Default: US business days at 22:00 EST
(04:00 MEZ next day).

```yaml
dracul:
  strigoi:
    spin:
      cron: "0 0 4 * * MON-FRI"   # 04:00 MEZ on business days
    insider:
      cron: "0 0 4 * * MON-FRI"
    echo:
      cron: "0 0 4 * * MON-FRI"
    lazarus:
      cron: "0 0 4 * * MON-FRI"
    index:
      cron: "0 0 4 * * MON-FRI"
    merger:
      cron: "0 0 4 * * MON-FRI"
  voievod:
    cron: "0 0 8 * * 1-5"         # 08:00 UTC on weekdays (synthesizer)
```

## Strigoi Insider

| Env var | Default | Purpose |
|---|---|---|
| `STRIGOI_INSIDER_ENABLED` | `false` | Enable agent registration on Dracul startup (controller + registrar `@ConditionalOnProperty`) |
| `STRIGOI_INSIDER_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for tool + completion webhooks. **Change in production.** |
| `DRACUL_INSIDER_SCHEDULE` | `0 0 21 * * 1-5` | Spring cron (sec min hour dom month dow). Default: 21:00 UTC weekdays. |
| `DRACUL_PUBLIC_URL` | `http://localhost:8080` | Base URL Vistierie uses to call back into Dracul (tool + completion webhooks). Must be reachable from the Vistierie container. |

Insider reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and fetches via
Agora (`DRACUL_AGORA_BASE_URL` / `DRACUL_AGORA_TOKEN`); no direct provider key
needed.

## Strigoi Echo

| Env var | Default | Purpose |
|---|---|---|
| `STRIGOI_ECHO_ENABLED` | `false` | Enable agent registration on Dracul startup (controller + registrar `@ConditionalOnProperty`) |
| `STRIGOI_ECHO_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for tool + completion webhooks. **Change in production.** |
| `DRACUL_ECHO_SCHEDULE` | `0 0 22 * * 1-5` | Spring cron (sec min hour dom month dow). Default: 22:00 UTC weekdays, after US close. |
| `ECHO_MIN_SURPRISE` | `5.0` | Minimum positive earnings-surprise percent for the pre-screen. |
| `ECHO_MIN_PRICE` | `5.0` | Minimum current share price (USD) liquidity floor for the pre-screen. |
| `ECHO_OHLC_HISTORY_DAYS` (`dracul.strigoi.echo.ohlc-history-days`) | `320` | Trading days of daily OHLC fetched per symbol/proxy for SP2 CAR, momentum and ADV. |
| `ECHO_CAR_PROXY` (`dracul.strigoi.echo.car.market-proxy`) | `SPY` | Market proxy symbol used as the CAR market-adjustment benchmark. |
| `dracul.strigoi.echo.gate.max-accrual-ratio` | `ECHO_MAX_ACCRUAL` | `0.10` | Sloan accrual ratio above which an earnings beat is treated as accrual-driven and the candidate is dropped. |
| `dracul.strigoi.echo.gate.min-days-to-next-earnings` | `ECHO_MIN_DAYS_NEXT` | `10` | Drop a candidate whose next earnings report is fewer than this many days away. |

Echo reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and fetches via
Agora (`DRACUL_AGORA_BASE_URL` / `DRACUL_AGORA_TOKEN`); no direct provider key
needed. Its v2 signal data (academic PEAD signals: time-series SUE deciles,
revenue-surprise / double-beat, consecutive beats) is computed Dracul-side
from Agora's earnings-window and EPS-history facade output.

## Strigoi Spin

| Env var | Default | Purpose |
|---|---|---|
| `STRIGOI_SPIN_ENABLED` | `false` | Register the agent + activate the webhook controller (`@ConditionalOnProperty`) |
| `STRIGOI_SPIN_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for tool + completion webhooks. **Change in production.** |
| `DRACUL_SPIN_SCHEDULE` | `0 0 4 * * 1-5` | Spring cron (sec min hour dom month dow). Default: 04:00 UTC weekdays. |
| `SPIN_LOOKBACK_DAYS` | `60` | Default Form-10-12B lookback window (days) for the pre-screen. |
| `SPIN_ABANDON_AFTER_DAYS` (`dracul.strigoi.spin.abandon-after-days`) | `180` | Lifecycle reconciler: days a tracked spin-co may sit non-distributed (`REGISTERED`/`WHEN_ISSUED`) since `discovered_at` before it is transitioned to the terminal `ABANDONED` state (kept for audit, never re-checked). See `documentation/strigoi.md`, "Strigoi-Spin: lifecycle persistence". |
| `SPIN_PROMOTION_WINDOW_DAYS` (`dracul.strigoi.spin.promotion-window-days`) | `90` | Prey-promotion gate: a `DISTRIBUTED` candidate is only promoted to prey while `daysSinceDistribution` is within this forced-selling window (days since distribution). Together with a resolved `spincoMarketCapMillions` this is the promotion condition; `sizeRatio` is deliberately **not** a hard gate (see `documentation/strigoi.md`). |

Spin reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and fetches via
Agora (`DRACUL_AGORA_BASE_URL` / `DRACUL_AGORA_TOKEN`); no direct provider key
needed. As of 2026-07-12 it persists every registration to the `spin_candidate`
table (V26) and tracks it through a REGISTERED → WHEN_ISSUED → DISTRIBUTED →
SETTLED/ABANDONED lifecycle across hunts (see `documentation/strigoi.md` and
`documentation/architecture.md`).

## Strigoi Lazarus

| Env var | Default | Purpose |
|---|---|---|
| `STRIGOI_LAZARUS_ENABLED` | `false` | Register the agent + activate the webhook controller (`@ConditionalOnProperty`) |
| `STRIGOI_LAZARUS_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for tool + completion webhooks. **Change in production.** |
| `DRACUL_LAZARUS_SCHEDULE` | `0 0 6 * * 1-5` | Spring cron (sec min hour dom month dow). Default: 06:00 UTC weekdays. |
| `LAZARUS_MAX_ABOVE_LOW` | `0.10` | Maximum fraction above the 52-week low to pass the price-proximity screen (default: within 10%). |
| `LAZARUS_MAX_DEBT_EQUITY` | `3.0` | Leverage cap for the solvency gate; candidates above this ratio are excluded. |
| `dracul.strigoi.lazarus.max-price-to-book` | `2.0` | Max price-to-book ratio for the cheapness (valuation) gate; a candidate must be cheap by P/B or P/FCF to pass. |
| `dracul.strigoi.lazarus.max-p-fcf` | `20` | Max price / free-cash-flow-per-share ratio for the cheapness (valuation) gate. |

Lazarus reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and fetches via
Agora (`DRACUL_AGORA_BASE_URL` / `DRACUL_AGORA_TOKEN`); no direct provider key
needed. An Agora failure degrades gracefully — symbols without fundamentals are
skipped by the screener. Lazarus computes a real Piotroski F-score via Agora's
`get_fundamental_score` tool (strict scoring + coverage count), gated by the
price-proximity/solvency/cheapness checks above plus a hard accruals drop, and
ranks candidates by `fScore`, dampened when `fScoreCriteriaAvailable` is thin.

## Strigoi Merger

| Env var | Default | Purpose |
|---|---|---|
| `STRIGOI_MERGER_ENABLED` | `false` | Register the agent + activate the webhook controller (`@ConditionalOnProperty`) |
| `STRIGOI_MERGER_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for tool + completion webhooks. **Change in production.** |
| `DRACUL_MERGER_SCHEDULE` | `0 0 5 * * 1-5` | Spring cron (sec min hour dom month dow). Default: 05:00 UTC weekdays. |
| `MERGER_LOOKBACK_DAYS` | `45` | Default DEFM14A / SC TO-T lookback window (days) for the pre-screen (1–120). |

Merger reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and fetches via
Agora (`DRACUL_AGORA_BASE_URL` / `DRACUL_AGORA_TOKEN`); no direct provider key
needed.

## Strigoi Index

| Env var | Default | Purpose |
|---|---|---|
| `STRIGOI_INDEX_ENABLED` | `false` | Register the agent + activate the webhook controller (`@ConditionalOnProperty`) |
| `STRIGOI_INDEX_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for tool + completion webhooks. **Change in production.** |
| `DRACUL_INDEX_SCHEDULE` | `0 0 7 * * 1-5` | Spring cron (sec min hour dom month dow). Default: 07:00 UTC weekdays. |
| `INDEX_LOOKBACK_DAYS` | `30` | Default lookback window (days) for the announced-constituent-change ingest — only changes **announced** within this many days are ingested (1–90). |
| `INDEX_OBSERVATION_WINDOW_DAYS` (`dracul.strigoi.index.observation-window-days`) | `30` | Lifecycle reconciler: days past the `effective_date` after which a `POST` row transitions to the terminal `CLOSED` state (the run-up/reversal observation window). Pure calendar; see `documentation/strigoi.md`, "Strigoi-Index: announcement-anchored lifecycle". |
| `INDEX_ABANDON_AFTER_DAYS` (`dracul.strigoi.index.abandon-after-days`) | `45` | Lifecycle reconciler safety-valve: days an `ANNOUNCED` row may sit past its `announcement_date` while its `effective_date` is still in the future before it is transitioned to the terminal `ABANDONED` state (a source/data anomaly, not the normal ANNOUNCED → EFFECTIVE path). Kept for audit, never re-checked. |
| `INDEX_PROMOTION_WINDOW_DAYS_SP` (`dracul.strigoi.index.promotion-window-days-sp`) | `5` | Prey-promotion gate for `sp_press`-sourced events: an `ANNOUNCED` event is only promoted while `effective_date` is in the future and no more than this many days away (S&P's forced-buy window is a few trading days). EFFECTIVE/POST rows never promote. |
| `INDEX_PROMOTION_WINDOW_DAYS_RUSSELL` (`dracul.strigoi.index.promotion-window-days-russell`) | `20` | Prey-promotion gate for `russell_reconstitution`-sourced events: the wider window (days-to-effective) matching Russell's multi-week preliminary→final reconstitution. |
| `INDEX_MARKET_PROXY` (`dracul.strigoi.index.market-proxy`) | `SPY` | Market-proxy symbol for the idiosyncratic-vol residual regression in `IndexDemandSnapshotter` (reuses echo's shared residual machinery). |
| `INDEX_IDIO_VOL_LOOKBACK_DAYS` (`dracul.strigoi.index.idio-vol-lookback-days`) | `90` | Number of trailing daily residual returns whose sample stddev is the `idiosyncraticVol` demand-snapshot field. |
| `INDEX_PASSIVE_AUM_SP500_BILLIONS` / `INDEX_PASSIVE_AUM_RUSSELL1000_BILLIONS` / `INDEX_PASSIVE_AUM_RUSSELL2000_BILLIONS` (`dracul.strigoi.index.passive-aum-{sp500,russell1000,russell2000}-billions`) | `11500` / `700` / `350` | **Coarse per-index config constants (rough estimates, NOT live feeds)**: assets tracking each index, in USD billions. An input to `demandToAdvRatioEstimate`. |
| `INDEX_MKTCAP_SP500_BILLIONS` / `INDEX_MKTCAP_RUSSELL1000_BILLIONS` / `INDEX_MKTCAP_RUSSELL2000_BILLIONS` (`dracul.strigoi.index.index-market-cap-{sp500,russell1000,russell2000}-billions`) | `50000` / `57000` / `3500` | **Coarse per-index config constants (rough estimates, NOT live feeds)**: each index's total market cap, in USD billions. The other input to `demandToAdvRatioEstimate`. Every value the estimate produces is proxy/estimate-labelled and judged qualitatively by the prompt — never quoted as precise. |

Index reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and fetches via
Agora (`DRACUL_AGORA_BASE_URL` / `DRACUL_AGORA_TOKEN`); no direct provider key
needed. As of 2026-07-12 it ingests announced constituent changes from Agora's
`get_index_constituent_changes` (S&P press-release RSS + Russell reconstitution)
and tracks each through an `ANNOUNCED → EFFECTIVE → POST → CLOSED/ABANDONED`
lifecycle in the `index_event` table (V27); see `documentation/strigoi.md` and
`documentation/architecture.md`.

## Voievod (consensus synthesizer)

| Env var | Default | Purpose |
|---|---|---|
| `DRACUL_VOIEVOD_ENABLED` | `false` | Enable the consensus synthesizer agent + webhooks (`@ConditionalOnProperty`). |
| `DRACUL_VOIEVOD_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for the tool + completion webhooks. **Change in production.** |
| `DRACUL_VOIEVOD_SCHEDULE` | `0 0 8 * * 1-5` | Spring cron (sec min hour dom month dow). Default: 08:00 UTC weekdays. |

Voievod reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and the shared
price adapter (graceful on failure). The `dracul.voievod.*` properties correspond
to these env vars via Spring's relaxed-binding rules.

## Gropar (exit-timing agent)

Disabled by default (`enabled=false`). Enable by setting `DRACUL_GROPAR_ENABLED=true` and providing a `DRACUL_GROPAR_WEBHOOK_TOKEN`.

| Env var | Default | Purpose |
|---|---|---|
| `DRACUL_GROPAR_ENABLED` | `false` | Register the gropar agent + activate the webhook controller (`@ConditionalOnProperty`). |
| `DRACUL_GROPAR_WEBHOOK_TOKEN` | _(blank)_ | Bearer token shared with Vistierie for the tool + completion webhooks. **Required when enabled; set in production.** |
| `DRACUL_GROPAR_SCHEDULE` | `0 0 22 * * 1-5` | Spring cron (sec min hour dom month dow) for the daily exit-signal run. Default: 22:00 UTC on weekdays (after US close). |
| `DRACUL_GROPAR_HISTORY_DAYS` | `260` | Days of daily OHLC history fetched per position for indicator calculation (≈ 1 trading year). |
| `DRACUL_GROPAR_FETCH_THROTTLE_MS` | `250` | Pause (ms) between per-ticker OHLC fetches in the gropar held-positions tool, to avoid bursting the market-data provider and triggering HTTP 429 rate-limits. `0` disables the pause (used in tests). |
| `DRACUL_GROPAR_ATR_PERIOD` | `22` | ATR look-back period for the Chandelier Exit stop (trading days). |
| `DRACUL_GROPAR_ATR_MULTIPLE` | `3.0` | ATR multiple for the Chandelier Exit stop level. |
| `DRACUL_GROPAR_MA_FAST` | `50` | Fast simple moving-average period (days) for the MA-cross indicator. |
| `DRACUL_GROPAR_MA_SLOW` | `200` | Slow simple moving-average period (days) for the MA-cross indicator. |
| `DRACUL_GROPAR_PROFIT_TARGET_PCT` | `40` | Unrealised-gain threshold (%) above which the gain indicator fires. |
| `DRACUL_GROPAR_STOP_LOSS_PCT` | `15` | Unrealised-loss threshold (%) below which the loss indicator fires. |
| `DRACUL_GROPAR_INITIAL_STOP_ATR_MULTIPLE` | `3.0` | k in `entry − k·ATR` for the **frozen initial stop** (computed once at entry, never updated). Lower values tighten the stop; higher values give the position more room. |
| `DRACUL_GROPAR_GIVEBACK_ACTIVATION_R` | `1.5` | Minimum peak gain in R (multiples of the initial risk unit) before the giveback rule can fire. Prevents premature exits while a position is still building. |
| `DRACUL_GROPAR_GIVEBACK_THRESHOLD_PCT` | `35` | Fraction (percent) of the peak gain given back that fires the `GIVEBACK` rule (e.g. 35 means a 35% retracement of peak unrealised gain triggers an exit signal). |
| `DRACUL_GROPAR_GIVEBACK_ATR_MULTIPLE` | `2.0` | Alternative giveback trigger: drawdown from the peak in ATR multiples. Whichever of the two giveback conditions fires first (`threshold-pct` or `atr-multiple`) triggers the `GIVEBACK` rule. |

All exit-rule thresholds (`atr-multiple`, `ma-fast`, `ma-slow`, `profit-target-pct`, `stop-loss-pct`, `history-days`, `initial-stop-atr-multiple`, `giveback-activation-r`, `giveback-threshold-pct`, `giveback-atr-multiple`) are operator-tunable via env var without a code change.

**Overextension threshold (prompt-only, not runtime-tunable):** gropar also treats a
position as *überdehnt* (a mean-reversion TRIM hint) when its distance to the MA200
(`indicators.distToMa200InAtr`) exceeds roughly **4 ATR** while the position is in
profit. This ~4-ATR richtwert is **baked into the prompt** (`prompts/gropar.md`) — it is
**not** a Spring/`@Value`-bound property and has **no env var**, so there is no
`DRACUL_GROPAR_*` knob for it. Changing the threshold means editing the prompt (and
propagating it via `definition/reset`), not setting an env var.

Gropar reuses `DRACUL_PUBLIC_URL` (webhook callback base URL).

## Executor (guarded broker-execution agent, slices 1+2)

Disabled by default (`enabled=false`) — existing deploys are unaffected until
an operator opts in. The executor consumes signals (from Strigoi/gropar or a
human) as **advice only**; code — not the LLM — enforces every veto and the
final order guard before any broker call, and (slice 2) owns the full exit
lifecycle (reconcile, hard exits, stop-ratchet) with the LLM only making the
soft-exit judgment call. The agent itself is **venue-agnostic**: whether
`dracul.executor.connection` points at a paper or live connection is an
operator/config decision the LLM has no visibility into. See
`documentation/strigoi.md` for the agent's role and `documentation/
architecture.md` for the doctrine note on why this is the one exception to
Dracul's read-only design.

| Env var | Property | Default | Purpose |
|---|---|---|---|
| `DRACUL_EXECUTOR_ENABLED` | `dracul.executor.enabled` | `false` | Register the agent + activate the operator and tool-webhook controllers (`@ConditionalOnProperty`). Also activates the `PreySignalEmitter`: when enabled, each hunter's `/complete` webhook auto-emits pending `executor_signal` rows from the prey it persists (`Prey → ExecutorSignal`, skipping already-open/already-pending symbols). Disabled → no emitter is wired and hunts complete unchanged. |
| `DRACUL_EXECUTOR_CONNECTION` | `dracul.executor.connection` | `depot-1` | The Agora trading connection the executor trades on. Paper vs live is entirely an operator/config choice — the LLM prompt does not name or distinguish connections. Renamed from `saxo-sim` (V25 migration) to a neutral id so the connection string itself can't leak broker/paper-vs-live information; the Agora-side connection key rename (`saxo-sim` → `depot-1`) must land in the same deploy, or reconciliation/`OrderGuard` will stop matching `executor_position.connection` against the live Agora connection. |
| `DRACUL_EXECUTOR_AGORA_BASE_URL` | `dracul.executor.agora-base-url` | `http://agora:8080` | Base URL of Agora's webhook trading tools (`AgoraTrading`), separate from the read-only research `AgoraClient`. |
| `DRACUL_EXECUTOR_AGORA_TRADING_TOKEN` | `dracul.executor.agora-trading-token` | _(blank)_ | Bearer token sent to Agora's trading webhooks, scoped to whichever connection(s) it is authorized for. Set in production. |
| `DRACUL_EXECUTOR_AGORA_TIMEOUT_MS` | `dracul.executor.agora-timeout-ms` | `8000` | Connect+read timeout (ms) for broker-write calls to Agora; timeouts surface as BROKER_ERROR. |
| `DRACUL_EXECUTOR_WEBHOOK_TOKEN` | `dracul.executor.webhook-token` | _(blank)_ | Bearer token shared with Vistierie for the 8 tool webhooks + completion webhook. **Required when the executor is enabled; set in production.** Unlike the other agents' webhook tokens, this one gates broker-**write** paths (`place-entry`, `exit-position`), so it deliberately defaults to blank (fail-loud when unset) rather than a guessable checked-in default, mirroring gropar's precedent. |
| `DRACUL_EXECUTOR_SCHEDULE` | `dracul.executor.schedule` | _(blank)_ | Spring cron (sec min hour dom month dow) for a scheduled executor run. Blank = manual-only (trigger via `POST /api/executor/run`). |
| `DRACUL_EXECUTOR_MIN_CONFIDENCE` | `dracul.executor.min-confidence` | `0.65` | `VetoService` rejects (`LOW_CONFIDENCE`) any signal whose `confidence` is below this threshold. Raised from `0.6` as part of the entry-completeness work; `application.yaml`'s `${DRACUL_EXECUTOR_MIN_CONFIDENCE:0.65}` fallback and the `@Value` default in `ExecutorWebhookController` are now aligned at `0.65`, so the value actually seen at runtime absent an explicit env var is `0.65`. Set the env var to override. |
| `DRACUL_EXECUTOR_MAX_POSITIONS` | `dracul.executor.max-positions` | `5` | `VetoService` rejects (`MAX_POSITIONS`) a new entry once `executor_position` has this many `OPEN` rows. |
| `DRACUL_EXECUTOR_ATR_PERIOD` | `dracul.executor.atr-period` | `22` | ATR look-back period (trading days) for `ExecutorIndicators`/`EntryContextAssembler`, used for entry-stop guidance, the stop window, and (slice 2) as the basis of the chandelier offset (`chandelier-mult` × ATR). |
| `DRACUL_EXECUTOR_SWING_PERIOD` | `dracul.executor.swing-period` | `20` | Swing-low look-back period (trading days) for `ExecutorIndicators`/`EntryContextAssembler`, used for entry-stop guidance (part of the stop window's anchor/floor calculation). |
| `DRACUL_EXECUTOR_RULE_VERSION` | `dracul.executor.rule-version` | `exec-v0.4` | The active rule-version tag (`RuleVersionProvider`) stamped onto every `decision_log` row, so a later change in prompt/thresholds is traceable in the audit trail. `RuleVersionProvider.seed()` is insert-if-absent, so bumping this default automatically seeds a new `rule_versions` row on next boot — no manual migration needed. `exec-v0.4` is the sim-completion stamp (hybrid kill hard trigger, scale-out ladder, entry GTD) and also fixes a `confidence_min` drift in the seeded `rule_versions.params` audit blob (`0.6` → `0.65`, matching the `min-confidence` runtime value above, which was already `0.65`); `exec-v0.3` stays in the table as history — see `documentation/operations.md` for the versioning-change discipline. |
| `DRACUL_EXECUTOR_CHANDELIER_MULT` | `dracul.executor.chandelier-mult` | `3.0` | ATR multiple used by `StopRatchetService`/`MaintenancePipeline` to compute the chandelier stop level (highest price reached minus `chandelier-mult` × ATR for longs, mirrored for shorts). The active stop only ever ratchets up (in the position's favor), never down; when a tranche 2 exists, the ratchet moves both the tranche-1 and tranche-2 stop legs. |
| `DRACUL_EXECUTOR_GIVEBACK_PCT` | `dracul.executor.giveback-pct` | `0.35` | Fraction of peak favorable excursion (MFE, in R) `HardTriggerService` allows to be given back before force-closing the position — a hard exit, not the LLM's call. |
| `DRACUL_EXECUTOR_GIVEBACK_ACTIVE_FROM_R` | `dracul.executor.giveback-active-from-r` | `1.5` | Giveback protection only arms once a position's MFE has reached this many R; below it, only the hard stop-breach exit applies. |
| `DRACUL_EXECUTOR_SOFT_CONFIRM_MIN` | `dracul.executor.soft-confirm-min` | `2` | Minimum consecutive-run `soft_trigger.confirm_count` (from `fetch-open-positions`) before the LLM is expected to act on a soft trigger (`chandelier_breach`/`ma_break`) via `exit-position`. |
| `DRACUL_EXECUTOR_COOLDOWN_DAYS` | `dracul.executor.cooldown-days` | `10` | Days a symbol is kept in `cooldown` ("fresh setup only") after any exit (hard or soft), preventing an immediate re-entry on the same setup. The `COOLDOWN` veto is a hard block in v1 with no fresh-setup exception (see `documentation/architecture.md`). |
| `DRACUL_EXECUTOR_TOTAL_BUDGET` | `dracul.executor.total-budget` | `10000` | Total capital (account currency) the executor is allowed to deploy across all open positions. Divided by `tranche-count` for the per-tranche notional; the `BUDGET`/`HEAT_LIMIT` vetos and `PositionSizer` all measure against this. |
| `DRACUL_EXECUTOR_TRANCHE_COUNT` | `dracul.executor.tranche-count` | `10` | Number of equal-sized tranches `total-budget` is divided into; each `place-entry`/`add-tranche` sizes one tranche (`total-budget / tranche-count`, FX-converted to instrument currency). |
| `DRACUL_EXECUTOR_HEAT_PCT` | `dracul.executor.heat-pct` | `0.06` | Fraction of `total-budget` the sum of all open positions' risk (`qty × (entry − active stop)`, account ccy) may not exceed; enforced by the `HEAT_LIMIT` veto (and mirrored in `add-tranche`'s heat check). |
| `DRACUL_EXECUTOR_MAX_PER_SECTOR` | `dracul.executor.max-per-sector` | `2` | Maximum open positions per sector (case-insensitive match on the Agora company-profile lookup); enforced by the `CONCENTRATION` veto. |
| `DRACUL_EXECUTOR_MIN_PRICE` | `dracul.executor.min-price` | `5` | Minimum instrument price (USD-equivalent, v1: instrument ccy IS USD) for the `LIQUIDITY` veto. |
| `DRACUL_EXECUTOR_ADV_MULTIPLE` | `dracul.executor.adv-multiple` | `200` | The `LIQUIDITY` veto requires ADV20 notional (20-day average daily volume × price) to be at least this many multiples of the tranche amount. |
| `DRACUL_EXECUTOR_MAX_SIGNAL_AGE_DAYS` | `dracul.executor.max-signal-age-days` | `5` | Maximum signal age, in trading days since `createdAt`, before the `SIGNAL_EXPIRED` veto rejects it. |
| `DRACUL_EXECUTOR_CHASE_ATR_MULT` | `dracul.executor.chase-atr-mult` | `1.0` | The `CHASED_AWAY` veto rejects an entry once price has moved more than this many ATRs beyond the signal's reference price. |
| `DRACUL_EXECUTOR_PACE_PER_WEEK` | `dracul.executor.pace-per-week` | `2` | Maximum new positions (tranche-1 entries) per ISO calendar week; enforced by the `PACE_LIMIT` veto. |
| `DRACUL_EXECUTOR_MAX_TRANCHE` | `dracul.executor.max-tranche` | `2` | Hard cap on tranches per position; `add-tranche` rejects with `MAX_TRANCHE` once `position.tranche() >= max-tranche`. |
| `DRACUL_EXECUTOR_ENTRY_GTD_DAYS` | `dracul.executor.entry-gtd-days` | `2` | Good-till-date window for a `place-entry` limit bracket: `ExecutorPositionRepository.setEntryExpiresAt` is set to this many trading days after placement (calendar days added, then rolled forward to the next Monday if the result lands on a Saturday or Sunday — a documented approximation, no exchange-holiday calendar in v1). `EntryExpiryService` cancels (never re-prices) any entry still `WORKING`/`PARTIALLY_FILLED` once `entry_expires_at` has passed; see `documentation/api.md`'s `CANCEL_EXPIRED` decision-log action. |
| `DRACUL_EXECUTOR_INSTRUMENT_CURRENCY` | `dracul.executor.instrument-currency` | `USD` | The currency instrument-side prices/ATR/tranche amounts are assumed to be in (v1: always USD). Used as the `EntryContextAssembler`'s FX-conversion basis and as the fallback account currency when the broker account snapshot is unavailable. |
| `DRACUL_OUTCOME_ENABLED` | `dracul.outcome.enabled` | `true` | Activates `OutcomeBatchJob`, the deterministic nightly `outcome_log` batch (Task 9) — code only, no LLM. Also requires `dracul.executor.enabled=true` (the job reads `decision_log`/`executor_position`, whose repository beans only exist when the executor is enabled); with the executor off, the job stays inactive regardless of this flag, so a fresh default install never fails to start for lack of those beans. |
| `DRACUL_OUTCOME_CRON` | `dracul.outcome.cron` | `0 30 22 * * 2-6` | Spring cron (sec min hour dom month dow), UTC, for `OutcomeBatchJob`. Default runs Tue–Sat 22:30 UTC, after the executor's evening cycle. |

**Safety notes:**
- `place-entry` and `exit-position` are the only write paths to the broker;
  every other tool (`fetch-pending-signals`, `fetch-open-positions`,
  `get-account`, `list-positions`, `submit-decision`) is read-only or
  advisory. The LLM cannot place an order or close a position directly —
  it can only request one, and code (`VetoService` + `OrderGuard` for
  entries; always-permitted for exits) decides.
- `exit-position` has no veto/order-guard gate by design — closing a
  position is never something the code needs to protect against.
- Like gropar, a scheduled executor agent needs a Vistierie budget set via the
  admin `PATCH .../agents/executor/budget` endpoint (mirroring voievod) or
  every pause/unpause toggle will 500 with `BudgetException`. See
  `documentation/vistierie-integration.md`.

Executor reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and fetches
research indicators via the existing read-only `AgoraClient`
(`DRACUL_AGORA_BASE_URL` / `DRACUL_AGORA_TOKEN`); the trading-specific
base URL/token above are additive and separate.

## Stop-Proximity Watcher

Deterministic intraday watcher that checks every live **depot-1** position's price (`HeldPositionService.openPositions`, joined to `position_context`) against its `active_stop` every ~15 minutes during the US session. `position_context` carries no ATR, so the watcher evaluates with ATR pinned to zero — `STOP_BREACHED` still fires normally, but the `STOP_PROXIMITY` warning band is effectively unavailable until ATR is added to the context model. Emits `STOP_PROXIMITY` (WARNING) and `STOP_BREACHED` (CRITICAL) alerts via the daywalker alert store (keyed by symbol, no `watchlist_item_id`), SSE panel, and Telegram. Gated off by default; enabling it requires Telegram bot-token and chat-id already configured (same `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` as gropar / morning report). This is a **Dracul-internal cron** — it does **not** register a Vistierie agent and requires no Vistierie budget change or `definition/reset`.

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_STOPGUARD_ENABLED` (`dracul.stopguard.enabled`) | `false` | Enables the `StopProximityWatcher` scheduled poll. Set to `true` to activate. Requires Telegram bot-token + chat-id. |
| `DRACUL_STOPGUARD_CRON` (`dracul.stopguard.cron`) | `0 */15 9-16 * * 1-5` | Spring cron (zone: America/New_York) for the intraday poll. Default: every 15 min from 09:00–16:59 NY time on weekdays. |
| `DRACUL_STOPGUARD_ATR_MULTIPLE` (`dracul.stopguard.atr-multiple`) | `0.5` | Width of the proximity zone as a fraction of ATR. A position is in the proximity zone when `active_stop < price ≤ active_stop + atr-multiple × ATR`. Since `position_context` carries no ATR, the watcher currently evaluates with ATR = 0, so the proximity band is empty in practice (`STOP_BREACHED` is unaffected). |
| `DRACUL_STOPGUARD_COOLDOWN` (`dracul.stopguard.cooldown`) | `82800` | Per-`(owner, symbol, zone)` re-alert suppression window in seconds. Default: 82800 s ≈ 23 h (≈ once per trading day). `STOP_PROXIMITY` and `STOP_BREACHED` have independent cooldowns so a breach escalates immediately even if a proximity alert was recently sent. |
| `DRACUL_STOPGUARD_NOTIFY_LEVEL` (`dracul.stopguard.notify-level`) | `WARNING` | Minimum alert severity that triggers a Telegram push (`WARNING` or `CRITICAL`). Default `WARNING` sends both proximity and breach alerts. |

Reuses `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` / `TELEGRAM_BASE_URL` — no additional Telegram config is needed.

## Verdict Kill-Criteria Watcher

Deterministic (no-LLM) watcher: for every open verdict (not yet DISMISSed) whose symbol is **not** a held watchlist position, evaluates the contributing prey's `kill_criteria` against the current quote and persists any breach on the verdict (`kill_criteria_breached` / `kill_criteria_checked_at`). Newly breached criteria are published as a `verdict.kill_criteria_breached` SSE event (see `documentation/api.md`) and rendered as a `KILL: <criterion>` badge on the verdict detail page. On by default; a **Dracul-internal cron** — it does **not** register a Vistierie agent and requires no Vistierie budget change or `definition/reset`.

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_VERDICT_KILLWATCH_ENABLED` (`dracul.verdict-killwatch.enabled`) | `true` | Enables the `VerdictKillCriteriaWatcher` scheduled poll. Set to `false` to disable. |
| `DRACUL_VERDICT_KILLWATCH_CRON` (`dracul.verdict-killwatch.cron`) | `0 30 21 * * 1-5` | Spring cron (zone: UTC) for the poll. Default: 21:30 UTC on weekdays — after US market close, before gropar's exit-signal run. |

## Morning Report (daily digest)

A daily Telegram digest of the morning report. Gated off by default; enabling it requires Telegram bot-token and chat-id already configured for the gropar notifications (i.e. `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` set). This is a **Dracul-internal cron** — it does **not** register a Vistierie agent and requires no Vistierie budget change.

The digest only sends on days with at least one **actionable** position (`SELL` or `TRIM`): when every held position is `HOLD` the push is skipped entirely, and on action days the digest body lists only the actionable positions (the `GET /api/morning-report` endpoint and the `/report` view still show all held positions, including HOLD).

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_REPORT_MORNING_ENABLED` (`dracul.report.morning.enabled`) | `false` | Enables the scheduled morning-report Telegram digest. Set to `true` to activate. Requires Telegram bot-token + chat-id to be configured. |
| `DRACUL_REPORT_MORNING_CRON` (`dracul.report.morning.cron`) | `0 0 7 * * 1-5` | Spring cron (zone: Europe/Berlin) for the digest send. Default: 07:00 Berlin time on weekdays. |

## Wikipedia

Strigoi-Index resolves announced constituent changes via Agora
(`AgoraReference.indexChanges` / `get_index_constituent_changes`), not Wikipedia
directly — and, as of the 2026-07-12 lifecycle rebuild, no longer via the old
`AgoraReference.constituents` / `get_index_constituents` route either. The
variables below feed **only** the Settings → Data-Sources health probe (flagged
stale, slated for 7d realignment to probe Agora).

| Env var | Default | Purpose |
|---|---|---|
| `WIKIPEDIA_BASE_URL` | `https://en.wikipedia.org` | Health-probe Wikipedia base URL. |
| `WIKIPEDIA_USER_AGENT` | `Dracul/1.0 (research; contact via repo)` | `User-Agent` header sent on the health probe's Wikipedia ping. MediaWiki policy requires a descriptive UA. |

## Language / i18n

| Setting | Storage | Default | Allowed values | How to change |
|---|---|---|---|---|
| `language` | DB — table `app_settings`, key `language` | `de` | `de`, `en` | `PUT /api/settings/language` with body `{"language":"en"}` |

## Display currency

| Setting | Storage | Default | Allowed values | How to change |
|---|---|---|---|---|
| `display_currency` | DB — table `app_settings`, key `display_currency` | `EUR` | `EUR`, `USD`, `GBP`, `CHF` | `PUT /api/settings/currency` with body `{"currency":"USD"}` |

**REST endpoints:**

| Endpoint | Purpose |
|---|---|
| `GET /api/settings/currency` | Returns `{"currency":"EUR"}` (or current value) |
| `PUT /api/settings/currency` | Persists new currency; returns `{"currency":"<value>"}` |

**Data model columns affected:**

| Column | Table | Notes |
|---|---|---|
| `currency` | `watchlist_items` | Effective display currency stamped on each item by the read path |
| `entry_currency` | `watchlist_items` | Original currency in which the entry price was recorded (always the native ticker currency, currently USD for US equities) |

**FX source:** Yahoo Finance `/v8/finance/chart/{from}{to}=X` (e.g. `USDEUR=X`
for USD→EUR). The rate is fetched once per conversion pair per request cycle and
cached for the session. If the FX request fails the stored price is returned
unconverted and the `currency` field reflects the fallback value.

**LLM cost budgets** remain denominated in USD regardless of the display-currency
setting (Vistierie's cost ledger is USD-only).

The language setting controls the language directive appended to every agent's
`system_prompt` at registration time. Changing it via the API publishes a
`LanguageChangedEvent`, which causes all registrars (all Strigoi, Voievod, and
Daywalker) to re-register immediately with the updated prompt. No restart is
required.

## Runtime-editable agent definitions

An agent's **prompt, schedule, model purpose (`routine`/`reasoning`), enabled flag, turn limit, run timeout, and per-tool descriptions** are stored in the `agent_definition` / `agent_tool_binding` DB tables (V10) and can be changed at runtime via:

- `GET /api/settings/agents/{name}/definition` — read current definition
- `PUT /api/settings/agents/{name}/definition` — update; re-registers with Vistierie immediately
- `POST /api/settings/agents/{name}/definition/reset` — restore code default from the `AgentDefaultProvider` bean

Code defaults are seeded from `prompts/<agent-name>.md` (prompt) and the `AgentDefaultProvider` bean (schedule, model purpose, tools) by `AgentDefinitionBootstrap` on startup using insert-if-absent. Manual edits survive redeployment.

**No new configuration keys** were added for this mechanism. Webhook tokens are still read from the existing per-agent environment variables (e.g. `STRIGOI_ECHO_TOKEN`, `DRACUL_DAYWALKER_TOKEN`) — these are not editable at runtime.

The following remain **code-bound** and require a redeploy to change:
- Output schema (JSON structure the agent's completion webhook expects)
- Tool routing (which webhook path handles which tool call)
- The agent roster itself (which `AgentDefaultProvider` beans exist)

## Prompt file header format

Each bundled prompt file under `java-server/src/main/resources/prompts/` starts
with a machine-readable `agent-meta` header, prepended to the file and followed
by exactly one blank line:

```
<!-- agent-meta
agent: strigoi-spin
version: 1.0.0
-->

<unchanged prompt body>
```

`PromptDocument` (`de.visterion.dracul.agent.PromptDocument`) parses this header
out. The **body** — everything after the header block and its following blank
line — is what gets stored in `agent_definition.prompt_text`, hashed into
`agent_version` (`AgentVersionResolver`), and sent to Vistierie; the header
itself never affects that hash. `*Defaults` providers load prompts via
`PromptDocument.bodyFromClasspath("prompts/<agent-name>.md")` instead of the
raw `AgentResources.classpath(...)` helper. A prompt file without a header
still parses fine (`agent`/`version` come back `null`, body = the raw file) —
the header is optional metadata, not a hard requirement.

## Agent tool-fetch cache

Results of the agent `/tools/fetch-*` webhooks are cached (keyed by tool + request
params) so repeated tool calls within a run — or quick re-triggers — do not re-hit
Agora (hunting fetch + market-data).

| Env var | Default | Purpose |
|---|---|---|
| `DRACUL_AGENT_TOOL_FETCH_CACHE_TTL_SECONDS` (`dracul.agent.tool-fetch.cache-ttl-seconds`) | `300` | Global default TTL (seconds) for cached tool-fetch results. `0` disables caching globally. |

Per-tool overrides are **code-bound** on each `ToolCatalogEntry`: a tool may set
`cacheable=false` (never cache — for freshness-critical tools) or its own
`cacheTtlSeconds` (overrides the global default). All existing tools default to
`cacheable=true` at the global TTL.

## Budget limits

Budget enforcement is delegated to Vistierie. Set tier budgets in the
Vistierie routing-rule config for the `dracul` tenant, not here. The
`/api/cost` endpoint proxies the current usage from Vistierie.
