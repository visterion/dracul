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
| `DRACUL_CORS_ALLOWED_ORIGINS` (`dracul.cors.allowed-origins`) | `http://localhost:5173` | Comma-separated browser origin(s) allowed to call `/api/**`. Must be the **public SPA origin** in prod (e.g. `https://dracul.example.com`), NOT `dracul.public-url` (that is the internal `http://dracul:8080` webhook URL). Browsers send an `Origin` header on state-changing methods (POST/PUT/PATCH/DELETE) even same-origin, so an unlisted origin makes Spring reject every write with `403 "Invalid CORS request"` while GET (no Origin header) still works. |
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
| `DRACUL_AGORA_TIMEOUT_MS` (`dracul.agora.timeout-ms`) | `25000` | Request timeout (ms) on the Agora MCP client, for every tool without a per-tool override. |
| `DRACUL_AGORA_CONNECT_TIMEOUT_MS` (`dracul.agora.connect-timeout-ms`) | `5000` | Connect timeout (ms) on the Agora MCP client, kept short so a dead Agora fails fast while a slow-but-alive one is waited out. |
| `DRACUL_AGORA_FORM4_TIMEOUT_MS` (`dracul.agora.tool-timeout-ms["[get_form4_transactions]"]`) | `45000` | Per-tool request timeout (ms) for the market-wide Form-4 scan. See below. |
| `DRACUL_AGORA_TIMEOUT_SEARCH_MS` (`dracul.agora.tool-timeout-ms["[search_instruments]"]`) | `2000` | Per-tool request timeout (ms) for `GET /api/instruments/search` (Chronicle's instrument search). Deliberately short: `AgoraClient.callTool` is `synchronized`, so every Dracul→Agora call shares one lock, and this is the first interactive, user-typed caller — a keystroke burst must not queue in front of the stop-loss watcher or the price refresher behind the default 25 s budget. |

**Per-tool request budgets.** `dracul.agora.tool-timeout-ms` is a map from MCP
tool name to milliseconds; anything not listed uses `dracul.agora.timeout-ms`.
Agora's tools are not one workload — most are a single upstream GET and should
fail fast, while `get_form4_transactions` walks a market-wide EDGAR window under
its own 30 s aggregate deadline. Raising the global timeout for everyone would
license the same long hang on a quote lookup, so the slow tool gets its own
budget.

The Form-4 default is derived, not guessed. Agora's worst case is
30 000 ms (`EdgarSearchService.FORM4_DEADLINE_MS`) + ~7 900 ms (two EFTS
searches of up to ten 100-hit pages each: the caller's window and the
late-filing pad) + ~190 ms (the one archive GET that may start just before the
deadline check) + ~1 000 ms MCP framing and serialisation = **39 090 ms**;
45 000 ms leaves ~15 % headroom. A market-wide 7-day scan measured **33.4 s**
end-to-end on production on 2026-08-04, against the then-configured 25 000 ms —
which is why every `strigoi-insider` run ended in a `TimeoutException` after the
EDGAR forms token was corrected and the result set grew from 42 to 1 697 hits.
`AgoraTimeoutBudgetTest` fails the build if the budget ever drops below Agora's
own worst case.

This budget is **per call**. Since 2026-08-06 `AgoraFilings.recentForm4` slices
its window as a **receding walk**: `from` stays at the start of the lookback and
only `to` steps back, two days per call, until receding further would precede
`from`. Each call reads the newest ~272 filings of its own window, so the
default `lookback_days=7` (an inclusive 8-day window) costs **four** calls —
4 × 45 s = 180 s. It is bounded by `AgoraFilings.MAX_WINDOW_SLICES` = **10**
calls (19 days of window): a longer window is walked as far as the budget allows
and reports `truncated: true` rather than looking complete. Worst case therefore
10 × 45 s = **450 s**. A caller with a tighter budget passes its own count —
`DaywalkerEventEngine` passes 1, so its intraday poll is exactly one wide call
over its whole window.

The enclosing limits, from the outside in: Vistierie's `max_run_seconds` for
`strigoi-insider` (1800 s) covers the whole run; the `fetch_recent_clusters`
webhook timeout (`InsiderDefaults.FETCH_TIMEOUT_SECONDS`) is **600 s** since
2026-08-06 (raised from 60 s) — a third of the run budget. The 150 s between
the 450 s Form-4 worst case and the 600 s timeout is not spare headroom: the
`InsiderEnrichmentService` calls (up to 25 clusters × ~5 Agora calls on the 25 s
global budget) run in the same request and live there. Changing it requires the agent-definition
reset. `InsiderToolTimeoutBudgetTest` pins the relationship itself (slice cap ×
the configured Agora budget < the webhook timeout), so the three numbers cannot
drift apart.

Map keys must be written in bracket form (`"[get_form4_transactions]"`): Spring's
relaxed binding strips `_` out of a plain map key, so an unbracketed key would
silently bind as `getform4transactions` and the override would be dead config.

**Deploy-ordering prerequisite:** Agora must be up **before** Dracul so the
first `get_quote` / `get_ohlc` calls resolve. If Agora is unreachable,
`quotes(...)` returns an empty map (the watchlist keeps its stored prices) and
`resolve` / `dailyOhlcHistory` throw `MarketDataException(UNAVAILABLE)` — the
same degradation contract as the old adapter chain, so scheduled refreshes never
crash. `AgoraClient` reconnects once on a stale session before giving up; a
terminal failure (reconnect also failed) logs one WARN line per tool,
`Agora unreachable for <tool>`.

## News credibility scoring (T1.4)

Every headline entering via the `AgoraCompanyData.news()` chokepoint is scored
against a static operator table (`dracul.news.credibility.sources` in
`application.yaml`): exact, case-insensitive matching of the item's url `domain`
AND its `source` string; if both hit, `min(domainScore, sourceScore)` wins; one
hit uses that score; no hit uses `default-score` (0.5). Items with
`score < drop-below` (0.3) are hard-dropped before any consumer (NewsDetector
triggers incl. MACRO collection, ConfounderScreen, RenfieldScheduler) sees them;
`score == drop-below` passes. One INFO log line per call summarizes drops. The
GUI depot passthrough (`DepotInstrumentService`) stays raw. `default-score` must
be >= `drop-below` — startup fails otherwise, as it does for any score outside
[0,1]. Survivors carry `credibility` into the daywalker and renfield prompts.
Reddit rows are deliberately below the threshold: reddit items drop even under
`DRACUL_NEWS_INCLUDE_SOCIAL=true`; opting reddit in means raising ALL reddit
rows (domain and `reddit-*` source rows — matching is exact). An empty table
scores everything 0.5 and drops nothing: the table itself is the switch.

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
| `DRACUL_DEPOTS_LIVE_VISIBLE_EMAILS` (`dracul.depots.live-visible-emails`) | `owner@example.com` | Comma-separated, case-insensitive allow-list of Cloudflare-Access emails permitted to see **live**-environment connections (`DepotConnection.environment() == "live"`). This is a server-side gate in `DepotService.isLiveVisible`: paper/sim connections are visible to every authenticated user regardless of this list, but a live connection is filtered out of `GET /api/depots` entirely for any email not on the list (not just hidden in the UI) — an unlisted caller never receives the live depot's account/positions/orders payload. An unauthenticated call (no email resolved) is treated as not-visible for any live connection. |
| `DRACUL_DEPOTS_CACHE_TTL_SECONDS` (`dracul.depots.cache-ttl-seconds`) | `60` | TTL (seconds) of the per-connection **display cache** in `DepotService`: the assembled `DepotDto` (account/positions/orders, quote-enriched) is cached per connection so repeated `GET /api/depots` loads / position-detail visits within the window do **not** re-hit the broker (Saxo) API. Concurrent misses for the same connection are single-flighted. `GET /api/depots?refresh=true` (the GUI's "Aktualisieren" button) bypasses the cache. Errored connections are never cached. Set to `0` to effectively disable caching. The cache is display-only — the executor's own reconcile path calls Agora directly and is unaffected. |
| `DRACUL_DEPOTS_HISTORY_LOOKBACK_DAYS` (`dracul.depots.history-lookback-days`) | `90` | Rolling window (days) `DepotHistoryService` sends as `from`/`to` on `GET /api/depots/{connection}/history`'s upstream Agora calls (`get_orders` for Alpaca, `get_closed_positions` for Saxo/default). `from = now - lookbackDays`, `to = now`. Purely a query-window bound — does not affect caching or any other depot endpoint. |

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

**Connection invariant.** `dracul.executor.connection` and
`dracul.position.connection` must resolve to the **same** connection (both
default `depot-1`). `PositionReconciler` only backfills/closes
`position_context` rows for `dracul.position.connection`; the executor only
opens/fills positions on `dracul.executor.connection`. If the two diverge,
every executor-opened position's context write (`thesis_snapshot`,
`kill_criteria`, `active_stop`) is silently invisible to gropar, stopguard,
and the reconciler's verdict-link pass — they all read `position_context`
keyed on `dracul.position.connection`, which would then never match the
executor's actual connection. There is no runtime check for this; keep both
properties in sync when overriding either one.

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

## Logging

| Variable | Default | Purpose |
|---|---|---|
| `DRACUL_LOG_DIR` | `logs` | Directory for the rotating log files. In production it is mounted onto a host directory, so the logs survive the container being recreated. |

## Notifications

| Variable | Default | Purpose |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | _(blank)_ | Telegram bot token, shared across Daywalker push alerts, Renfield proposal pushes, and executor action pushes (`DRACUL_EXECUTOR_NOTIFY_ENABLED`, see the Executor table). Blank disables push. |
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
| `DRACUL_DAYWALKER_SESSION_CRON` | `0 0 8 * * 1-5` | StreamingBee session-open cron (sec min hour dom mon dow), UTC. Default = 08:00 UTC weekdays (summer 04:00–20:00 ET, winter 03:00–19:00 ET). |
| `DRACUL_DAYWALKER_SESSION_DURATION` | `57600` | Session window length in seconds (16 hours). |
| `DRACUL_DAYWALKER_POLL_INTERVAL` | `300` | Event-source poll cadence in seconds (5 min) |
| `DRACUL_DAYWALKER_POLL_BUDGET_MS` | `60000` | Timeout budget (milliseconds) for a single poll sweep across all watched symbols. Unfinished symbols are skipped with a WARN if budget exhausted. |
| `DRACUL_DAYWALKER_PRICE_SPIKE` | `0.03` | PRICE_SPIKE threshold (fraction) |
| `DRACUL_DAYWALKER_VOLUME_MULT` | `3.0` | VOLUME_SPIKE multiple of rolling average |
| `DRACUL_DAYWALKER_COOLDOWN` | `3600` | Per-`(symbol, trigger_type)` suppression window in seconds (60 min), owner-agnostic. |
| `DRACUL_DAYWALKER_ATTEMPT_COOLDOWN` (`dracul.daywalker.attempt-cooldown`) | `600` | Emission guard per `(symbol, trigger_type)`, set at the moment the event is **emitted** and independent of how the spawned run ends. On a successful run the longer `DRACUL_DAYWALKER_COOLDOWN` dominates, because `daywalker_alerts` is only written for `status=done`; on a failed run no alert row exists and this guard is the only brake — it stops every 5-minute poll from re-launching the same failing run. `0` or a negative value disables it. In-memory (`DaywalkerEventEngine.claimEmission`), so it does not survive a restart — the damage after a restart is bounded to one extra run per pair; expired entries are evicted on every poll. |
| `DRACUL_DAYWALKER_ESCALATION_ENABLED` | `true` | Master toggle for the `daywalker-deep` reasoning-tier second-opinion escalation (see `documentation/strigoi.md`). Escalation only actually fires when `DRACUL_DAYWALKER_DEEP_ENABLED` is **also** `true` — the gate checks both flags. |
| `DRACUL_DAYWALKER_ESCALATION_CONFIDENCE` | `0.6` | A CRITICAL assessment escalates only when its `confidence` is strictly below this threshold. |
| `DRACUL_DAYWALKER_MACRO_COOLDOWN` | `28800` | Seconds; 8 h → max 2× per 16 h session; cooldown between MACRO_PORTFOLIO alerts. |
| `DRACUL_DAYWALKER_WATCHLIST_ENABLED` (`dracul.daywalker.watchlist-enabled`) | `false` | `false` (default) = daywalker's intraday sweep watches the depot's open positions only (protects the Claude Max quota). `true` = legacy behavior — the swept universe is depot ∪ full watchlist, deduped per symbol with the depot representative winning. Parsed fail-safe (`DaywalkerEventEngine.parseWatchlistEnabled`): any value other than a clean `true`/`false` (case-insensitive, trimmed) logs a WARN and resolves to `false`. |

Daywalker reuses `DRACUL_PUBLIC_URL` (webhook callback base URL).

## Sector cache (portfolio-aware news; T2.2)

| Env var | Default | Purpose |
|---|---|---|
| `DRACUL_SECTOR_TTL_SECONDS` | `86400` | Positive sector cache TTL (seconds; 24 h default). |
| `DRACUL_SECTOR_NEGATIVE_TTL_SECONDS` | `3600` | Negative sector cache TTL (seconds; 1 h default) — when a sector lookup fails. |

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

## Renfield (daily watchlist-review agent)

| Env var | Default | Purpose |
|---|---|---|
| `DRACUL_RENFIELD_ENABLED` | `false` | Register the agent + activate the webhook controller (`@ConditionalOnProperty`) |
| `DRACUL_RENFIELD_CRON` | `0 0 12 * * MON-FRI` | Scheduled cron (sec min hour dom mon dow), UTC. Default = 12:00 UTC weekdays (≈ 08:00 ET summer / 07:00 ET winter). |
| `DRACUL_RENFIELD_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for the completion webhook. Also backs `dracul.renfield.webhook-token`, used for both the outbound trigger's completion-webhook token and inbound webhook verification. **Change in production.** |
| `DRACUL_RENFIELD_MAX_SYMBOLS` | `30` | Caps how many watchlist symbols are sent to the LLM per run (cost control — one Agora news call + one prompt slot per symbol). Below the cap, the watchlist is reviewed in full, unsorted (its natural `added_at DESC` order). Above the cap, it is sorted by priority stage — `HELD` tag (0) → has a verdict (`verdictId != null`, 1) → agent-sourced (`source` starts with `agent:`, 2) → `manual` (3) → `seed` (4) → everything else (5) — tied within a stage by `added_at DESC`, then truncated to the cap; the dropped count is logged. |
| `DRACUL_RENFIELD_PRIOR_MEMORY_BUDGET_MS` | `2000` | Total wall-clock budget (ms) for the `prior_memory` pre-fetch (`HiveMemResearchService.searchForInput`) across the **whole** run, not per symbol — bounds a black-holing HiveMem. Once spent, remaining symbols simply get an empty `prior_memory` rather than blocking. |
| `DRACUL_RENFIELD_BACKFILL_ENABLED` | `false` | Gates the one-off backfill seam on `POST /api/renfield/complete`: when `true`, a request carrying `X-Dracul-Backfill: true` persists proposals but suppresses Telegram, SSE and the HiveMem thesis write-back, so replaying lost runs doesn't alert or poison `prior_memory`. Default off — the endpoint's only auth is a bearer token, so an always-on header would be a standing way to write proposals with all alerting silenced. **Only flip on for the duration of a replay, then back off.** |

Renfield analyzes the primary user's watchlist daily (each symbol flagged `held` when it is also an open depot position — depot-only positions not on the watchlist are not reviewed) and emits concrete trade proposals as Telegram push + one bundled SSE `proposal.new` event per run. It is a scheduled, trigger-only agent (`schedule=null` is false — it has a cron) that produces no orders or trades. Proposals are bundled and idempotent (retried webhooks insert zero rows). Like every Vistierie agent it also needs a budget set once via the admin endpoint before it can run — see `documentation/operations.md`'s Agent budget guard section.

Renfield reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` (same Telegram bot as Daywalker).

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
| `ECHO_MAX_CANDIDATES` (`dracul.strigoi.echo.max-candidates`) | `33` (lowered from `40` on 2026-08-04) | Ceiling on the candidate list handed to the agent. The pre-screen applies the EPS filters first, ranks the survivors by descending earnings surprise (symbol as tiebreak) and keeps the strongest `N` — so this is a payload bound, not a quality filter. **Derived, not chosen** (2026-08-04). Budget = the bridge's guaranteed-safe zone of 50 000 chars (see `MERGER_MAX_CANDIDATES` under "Strigoi Merger" below for where that number comes from; the "~95 kB" once quoted here was folklore). Measured on a production payload the same day: 29 candidates = 45 106 chars compact, of which `recentNews` was 21 427 chars — **47.5 %** — at an average 4.5 items × 160 chars, i.e. ~835 chars of base cost per candidate. The lever taken first was therefore the per-candidate SIZE, not the candidate count: `ECHO_RECENT_NEWS_CAP` went 5 → 3, bringing a candidate to ~1 365 chars. `50 000 − 4 194` (reserve for `active_patterns` growth, ~200 chars per accepted pattern ⇒ ~21 more) `= 45 806`, and `45 806 / 1 365 = 33`. Keeping a 5-item index would have forced the cap down to **28** — below the 29 candidates a real earnings day produced on 2026-08-04, i.e. actual feature reduction. Observed candidate counts over 45 days peak at 29 (typical 27–29 in an earnings week, 11–14 otherwise), so 33 serves every observed day untruncated with four to spare. `EchoPayloadBudgetTest` binds its worst case to BOTH yaml defaults and fails the build if either drifts out of the safe zone. The earnings window itself is now fetched with `limit=1000`, which without this cap would yield ~250–290 candidates. A cut is reported as `data_source_health.truncated: true` with the reason in `detail`, never silently. |
| `ECHO_OHLC_HISTORY_DAYS` (`dracul.strigoi.echo.ohlc-history-days`) | `320` | Trading days of daily OHLC fetched per symbol/proxy for SP2 CAR, momentum and ADV. |
| `ECHO_CAR_PROXY` (`dracul.strigoi.echo.car.market-proxy`) | `SPY` | Market proxy symbol used as the CAR market-adjustment benchmark. |
| `dracul.strigoi.echo.gate.max-accrual-ratio` | `ECHO_MAX_ACCRUAL` | `0.10` | Sloan accrual ratio above which an earnings beat is treated as accrual-driven and the candidate is dropped. |
| `dracul.strigoi.echo.gate.min-days-to-next-earnings` | `ECHO_MIN_DAYS_NEXT` | `10` | Drop a candidate whose next earnings report is fewer than this many days away. |
| `ECHO_RECENT_NEWS_CAP` (`dracul.strigoi.echo.recent-news-cap`) | `3` (lowered from `10` on 2026-07-28, then from `5` on 2026-08-04) | Caps the newest-first, summary-less news **index** (`recentNews`, `{headline, source, credibility, datetime}`) carried in `fetch_recent_pead_candidates`. This is the single lever the candidate-payload byte budget depends on — raising it grows every candidate's payload back toward the Vistierie bridge's tool-result cap (50 000 chars safe / 100 000 chars hard — see `MERGER_MAX_CANDIDATES`) that a full, summary-carrying news list once exceeded. It caps only the index: the deterministic confounder gate always scans the full, uncapped news list (summaries included), and the `fetch_candidate_news` detail tool (see `documentation/api.md`) returns the list with summaries for one symbol on demand, capped separately at 40 items newest-first (its own safety bound against the same bridge limit). `newsCount` on each candidate reports the true, uncapped total regardless of this cap. |

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
| `SPIN_LOOKBACK_DAYS` | `60` | Default Form-10-12B lookback window (days), 1–90. Since 2026-08-04 it bounds BOTH ends of the hunt: the EDGAR ingest search and the response payload read back from `spin_candidate` (`filing_date` OR `distribution_date` inside the window). Previously it reached only the ingest search, so the response was the whole active table regardless of what was asked for. |
| `SPIN_ABANDON_AFTER_DAYS` (`dracul.strigoi.spin.abandon-after-days`) | `180` | Lifecycle reconciler: days a tracked spin-co may sit non-distributed (`REGISTERED`/`WHEN_ISSUED`) since `discovered_at` before it is transitioned to the terminal `ABANDONED` state (kept for audit, never re-checked). See `documentation/strigoi.md`, "Strigoi-Spin: lifecycle persistence". |
| `SPIN_PROMOTION_WINDOW_DAYS` (`dracul.strigoi.spin.promotion-window-days`) | `90` | Prey-promotion gate: a `DISTRIBUTED` candidate is only promoted to prey while `daysSinceDistribution` is within this forced-selling window. `daysSinceDistribution` is measured off `promotionAnchorDate`, **not always days since the real distribution** — it prefers the term-sheet `distribution_date`, then `record_date`, then falls back to the `distributed_at` detection date when neither is known (`anchorSource`: `DISTRIBUTION_DATE` \| `RECORD_DATE` \| `DETECTED`). As of 2026-08-09 the gate additionally requires `distributionDateConfirmed = true` — defined as `anchorSource != DETECTED` — so a candidate whose window reading is only anchored to the detection timestamp is never promoted, even at `daysSinceDistribution = 0`; a record-date-only anchor **does** satisfy this gate, though it can open the window a few days earlier than the true distribution date. Dates are read by the LLM from the filing's EX-99.1 Information Statement with a verbatim evidence sentence Dracul verifies before storing (see `documentation/strigoi.md`) — `SpinTermsParser` no longer extracts either date by regex. A row held back only by this flag (all other conditions met) logs a visible `strigoi-spin` INFO line so a quiet-looking run is distinguishable from an actual quiet night. Together with a resolved `spincoMarketCapMillions` this is the promotion condition; `sizeRatio` is deliberately **not** a hard gate (see `documentation/strigoi.md`). |

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
| `LAZARUS_MEGA_CAP_USD_MILLIONS` | `100000` | Market-cap threshold (USD millions) above which a candidate skips the P/B-or-P/FCF cheapness gate — quality checks (solvency, leverage, 52-week-low unit guard) still apply unchanged. Exists because the cheapness gate encodes Piotroski's 2000 origin universe (highest book-to-market quintile) and structurally excludes very large companies that never trade below 2x book value; measured 2026-08-09, only 17 of 122 S&P 500 names above 100 Bn USD cleared the gate (banks, oil, telecom). The threshold is the S&P 500's upper-quartile cutoff (full census n=502, p75 = 96,390). **Currency-agnostic since 2026-08-13:** the screener only forwards the raw, pre-enrichment `marketCap()` as a hope (`cheapGatePassed`); `StrigoiLazarusWebhookController` resolves the candidate's listing (`LazarusListingResolver`), converts the market cap to USD once via `FxService`, and compares the USD figure against this threshold — for ANY resolved listing (`FOREIGN_SUFFIXED` or `US_CONFIRMED`), not only a `null`/`"USD"` reporting currency as before. A candidate whose listing could not be resolved (`UNKNOWN`) never gets the exemption. `0` disables the exemption entirely. |
| `dracul.strigoi.lazarus.probe-symbol` | `AAPL` | Symbol used once per batch to probe fundamentals-source health before the expensive stage (a liquid US name that is always resolvable); a probe failure makes the whole hunt `unavailable`. |
| `LAZARUS_UNIVERSE_SOURCE` | `sp500` | Index whose constituents form the screened universe, fetched via Agora `get_index_constituents`. Agora currently serves only `sp500`. The special value `watchlist` turns the index off and restores the pre-2026-08-04 watchlist-only scope (operator fallback). Watchlist entries are always screened in addition to the index. |
| `LAZARUS_UNIVERSE_MAX` | `600` | Hard ceiling on universe size after depot/watchlist de-duplication (the S&P 500 delivers ~503 symbols incl. multi-class rows). A cut is reported as `truncated`. |
| `LAZARUS_PROBE_CHUNK_SIZE` | `100` | Symbols per Agora `get_indicators_batch` call in the pre-filter (since 2026-08-06). Chunking is why the pre-filter costs `ceil(universe / chunk)` ≈ 5 Agora calls per run instead of ~503: one call per index member burned Alpaca's per-minute quota (measured 2026-08-05: 49 of 645 Alpaca calls answered 429, and TwelveData at 8 credits/minute tipped over right behind it). 100 is measured — 100 symbols in one Alpaca multi-symbol bars URL answered HTTP 200 in 1.15 s — and stays well below Agora's own 600-symbol ceiling, which **rejects** an oversized call rather than truncating it. Clamped into `[1, 600]` in code. Raising it also raises the worst-case overshoot of `LAZARUS_PRE_FILTER_BUDGET_MS`: the budget is checked after each chunk, so it can be exceeded by at most one chunk's duration (bounded by `DRACUL_AGORA_TIMEOUT_MS`). |
| `LAZARUS_PRE_FILTER_MARGIN` | `0.25` | The cheap pre-filter (an Agora `52w_range` probe per universe symbol, batched `LAZARUS_PROBE_CHUNK_SIZE` at a time) keeps a symbol trading at most this fraction above its 52-week low. Deliberately **wider** than `LAZARUS_MAX_ABOVE_LOW`: it reads a 252-bar low off Agora's OHLC provider chain (Alpaca first for US symbols, Yahoo only as last resort) while the authoritative screen reads the provider's own 52-week low, so a tighter pre-filter would drop real candidates over a definitional difference. It never relaxes the screen. |
| `LAZARUS_PRE_FILTER_BUDGET_MS` | `240000` | Wall-clock ceiling for the pre-filter pass. Symbols it does not reach are reported as `truncated`, and the next run enters the universe where this one stopped (in-memory rotation). Checked after each **chunk** since 2026-08-06 (a batched call is indivisible), so the pass can overshoot by at most one chunk's duration — one Agora request, bounded by `DRACUL_AGORA_TIMEOUT_MS` (25 s) against this 240 s. |
| `LAZARUS_MAX_CONSECUTIVE_DEAD_CHUNKS` | `2` | Consecutive pre-filter **chunk calls that resolved nothing at all** before the source counts as down and the pass stops. The unit is the chunk, not the symbol: probes are batched (`LAZARUS_PROBE_CHUNK_SIZE`), so one transient upstream error discards a block of adjacent symbols at once. On 2026-08-06 the previous symbol counter (`LAZARUS_MAX_CONSECUTIVE_FAILURES`, 10 symbols) read 37 such symbols as a dead source and left 80 of 490 universe symbols unscreened. A chunk that resolved even one usable range **clears** the run; a chunk that resolved none while failing at least one increments it; a chunk of nothing but symbols younger than 52 weeks does neither. Symbols lost inside an otherwise healthy chunk are still degradations (`partial`) — they are just not evidence about the source. Against a wholly dead source this spends at most 2 of the ~5 chunk calls a 490-symbol universe costs (<= 2 x `DRACUL_AGORA_TIMEOUT_MS` = 50 s), well inside `LAZARUS_PRE_FILTER_BUDGET_MS`, which is the backstop. **`LAZARUS_MAX_CONSECUTIVE_FAILURES` was removed, not renamed in place** — an env var of the old name has no effect, because its value meant symbols and silently re-reading it as chunks would push the threshold out of a 490-symbol universe's reach. |
| `LAZARUS_FUNDAMENTALS_MAX` | `60` | Ceiling on `get_fundamentals` calls per run — **the cost gate**. `get_fundamentals` routes US symbols to Finnhub, throttled to 60 calls/minute across all of Agora (`agora.data.finnhub.calls-per-minute`). The Finnhub *fundamentals* response is cached 6 h (`agora.data.cache.ttl.fundamentals-seconds`, unchanged) — this is a DIFFERENT cache from the Finnhub *company-profile* response `LAZARUS_PROFILE_MAX` below spends, which as of 2026-08-13 has its own 7-day TTL on the Agora side. Both `get_fundamentals` and `get_company_profile` calls share the SAME 60-calls/minute Finnhub throttle, so a full run can spend up to `LAZARUS_FUNDAMENTALS_MAX` + `LAZARUS_PROFILE_MAX` (60 + 40 = 100 by default) calls against that one 60/min budget — the naive "60 max = fits in one throttle window" reading no longer holds once the profile calls are counted in. Overrunning the throttle produces 429s and silently dropped symbols. Watchlist names are counted first, then the pre-screened index names closest to their low; the rest is reported as `truncated`. |
| `LAZARUS_PROFILE_MAX` (`dracul.strigoi.lazarus.profile-max`) | `40` | Ceiling on `get_company_profile` calls per run, spent by `LazarusListingResolver` to resolve which listing a survivor's fundamentals actually describe (candidates whose `reportingCurrency` is already non-null skip the call — the currency alone is enough). A candidate whose profile lookup did not run (cap reached, guard tripped) or came back inconclusive (missing/blank ticker) stays `ListingResolution.UNKNOWN`: no size exemption, no Altman-Z, counted as `listingUnknown` (`partial`). Shares the Finnhub 60-calls/minute throttle with `LAZARUS_FUNDAMENTALS_MAX` — see that row. |
| `LAZARUS_FETCH_TIMEOUT_SECONDS` | `600` | Value published as `webhook_timeout_seconds` on the fetch tool's definition (was a hard-coded 30 while the universe was the watchlist). It states the budget the fetch needs — 1 index call + up to `ceil(LAZARUS_UNIVERSE_MAX / LAZARUS_PROBE_CHUNK_SIZE)` pre-filter calls + `LAZARUS_FUNDAMENTALS_MAX` fundamentals calls + up to `LAZARUS_PROFILE_MAX` profile calls + enrichment. **It is not enforced anywhere (verified 2026-08-04):** Vistierie declares `webhook_timeout_seconds` on the tool definition and never applies it, and the RestClient that calls the webhook uses `SimpleClientHttpRequestFactory` with an infinite read timeout. Neither the old 30 s nor this 600 s will ever cut a tool call short — do not diagnose a long-running fetch as a timeout. **Agent definitions are `insertIfAbsent` on prod — a change here needs the agent-definition reset to become visible.** |

The **executor currency veto**: the executor drops any lazarus (or other Strigoi)
signal whose watchlist row currency does not match the expected trading currency for
its venue — a guard against acting on a mis-priced / mis-converted candidate. See
`documentation/strigoi.md` (executor) for the veto's placement in the signal pipeline.

Lazarus reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and fetches via
Agora (`DRACUL_AGORA_BASE_URL` / `DRACUL_AGORA_TOKEN`); no direct provider key
needed. An Agora failure degrades gracefully — symbols without fundamentals are
skipped by the screener. Lazarus computes a real Piotroski F-score via Agora's
`get_fundamental_score` tool (strict scoring + coverage count), gated by the
price-proximity/solvency/cheapness checks above plus a hard accruals drop, and
ranks candidates by `fScore`, dampened when `fScoreCriteriaAvailable` is thin.

**Global (EU/Asia) hunting (2026-07-14):** lazarus additively screens non-US
watchlist names (XETRA `.DE`, Tokyo `.T`, Hong Kong `.HK`) alongside US names;
US behaviour is unchanged. For non-US symbols the Altman-Z / fundamentals inputs
come from Agora's `get_fundamental_concepts` (Yahoo-backed) rather than SEC XBRL.

**Market-wide universe (2026-08-04):** the screened universe is no longer the
watchlist. It is `LAZARUS_UNIVERSE_SOURCE` (default the S&P 500, via Agora
`get_index_constituents`) **plus** every watchlist entry, minus what depot-1
already holds. The watchlist alone was the universe until this change, and it
read as empty — so every run returned zero candidates while reporting
`data_source_health.status = "healthy"`: a guaranteed no-op that read as a quiet
market. **Correction (2026-08-04): the watchlist table was NOT empty.** It holds
rows; lazarus was reading the wrong owner. The controller hard-coded the owner
as `"default"`, while `LegacyWatchlistOwnerMigration` rewrites
`watchlist_items.user_id = 'default'` to `dracul.primary-user-email` on every
boot — so a count scoped to `'default'` is zero while the table itself is not.
Lazarus now reads `dracul.primary-user-email` (falling back to `"default"` only
when that property is blank), matching every sibling that already did. The same
bug also made the documented `LAZARUS_UNIVERSE_SOURCE=watchlist` fallback a
fallback to nothing. **An empty or unfetchable universe is now reported as
`unavailable`, never as healthy-with-zero-candidates.** Per-symbol losses
(an unusable pre-filter answer, missing fundamentals, a missing 52-week low,
enrichment drops) set `partial` — but a symbol younger than 52 weeks does not,
that being a permanent property of the instrument rather than a degradation
(2026-08-06); deliberate cuts (universe cap, fundamentals
budget, spent pre-filter budget) set `truncated`. The candidates that were found
are always kept — the flags say "what you see is incomplete", not "you saw
nothing". The screen thresholds above are unchanged by this fix.

## Strigoi Merger

| Env var | Default | Purpose |
|---|---|---|
| `STRIGOI_MERGER_ENABLED` | `false` | Register the agent + activate the webhook controller (`@ConditionalOnProperty`) |
| `STRIGOI_MERGER_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for tool + completion webhooks. **Change in production.** |
| `DRACUL_MERGER_SCHEDULE` | `0 0 5 * * 1-5` | Spring cron (sec min hour dom month dow). Default: 05:00 UTC weekdays. |
| `MERGER_LOOKBACK_DAYS` | `45` | Default DEFM14A / SC TO-T lookback window (days) for the pre-screen (1–120). An agent-supplied `lookback_days` overrides it; a numeric STRING (which is what the Vistierie/MCP bridge actually sends) counts as a number, and any value that cannot be used is logged rather than silently replaced by this default. |
| `MERGER_MAX_CANDIDATES` (`dracul.strigoi.merger.max-candidates`) | `30` | Ceiling on the enriched candidate list handed to the agent — a payload bound, not a quality filter, and **derived rather than chosen**. Per candidate the enrichment costs at most two Agora round trips (one `get_filing_text`, plus one daily-OHLC call only when a term sheet yielded an agreement date; quotes are a single batched call for the whole list), but the binding limit is the tool-result size. Every Vistierie tool reaches the model as an in-process SDK MCP tool, and the Claude Code CLI caps an MCP result at `MAX_MCP_OUTPUT_TOKENS` (25 000, unset on the bridge so the compiled default applies): a cheap pre-check skips truncation entirely below a `chars/4` estimate of 12 500 tokens — i.e. **50 000 chars is the guaranteed-safe zone** — and above it a result over 25 000 tokens is cut to **100 000 chars**, the hard ceiling. Against a 50 000-char budget, 5 000 reserved for the envelope, a measured worst case of 645 chars of structured fields per candidate (200 production records) and a `MERGER_TERM_SHEET_DIGEST_CHARS`-sized digest, the arithmetic gives `45 000 / 1 400 = 32.1` → **30** (worst case 47 000 chars). Note what it is not: 25 was provably binding (a 45-day and a 90-day window both returned exactly 25 rows) and 40 cannot fit under any budget (40 × 645 = 25 800 chars of structured fields alone). `MergerPayloadBudgetTest` holds the derivation and fails the build if the two knobs drift apart. EFTS returns `file_date` DESC, so a cut drops the OLDEST deals; it is reported as `data_source_health.truncated: true`, never silently. |
| `MERGER_TERM_SHEET_DIGEST_CHARS` (`dracul.strigoi.merger.term-sheet-digest-chars`) | `700` | Per-candidate ceiling on the term-sheet **digest**. The raw term sheet is no longer shipped at all: Agora caps `get_filing_text` at 24 000 chars per filing, and 25 of those produced a 329 818-char tool result on a production run — over three times the hard ceiling, so the model received a candidate list chopped mid-JSON and answered `{"prey": []}` on five consecutive runs, each of them `status=done`. `TermSheetDigest` instead keeps the sections that price deal risk — closing conditions, regulatory approvals, termination fees, no-solicitation/go-shop, financing, shareholder vote — at most 240 chars each (word-boundary trimmed), spent from the top of that priority order until this budget is exhausted, with a head excerpt of the same length as fallback when no cue matches. Everything quantitative is already extracted server-side by `DealTermsParser` into its own fields, so the digest only has to carry the qualitative closing risk. |

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

## Pattern outcome scorer (T3.3 gates)

Weekly, pure-code job (`PatternOutcomeScorer`, no LLM) that matches every
completed TRADE outcome against every gated `PENDING`/`ACTIVE` pattern and
writes idempotent `pattern_evidence` rows plus the aggregate columns. Scheduled
one hour before the Saturday `voievod-outcome` run (`0 0 7 * * 6`).

| Env var | Property | Default | Purpose |
|---|---|---|---|
| `DRACUL_PATTERN_SCORER_ENABLED` | `dracul.pattern-scorer.enabled` | `true` | Enables the weekly pattern outcome scorer (`@ConditionalOnProperty`, `matchIfMissing = true`). |
| `DRACUL_PATTERN_SCORER_CRON` | `dracul.pattern-scorer.cron` | `0 0 6 * * 6` | Spring cron (sec min hour dom month dow), UTC. Default: Saturday 06:00 UTC, one hour before the Saturday `voievod-outcome` run (`0 0 7 * * 6`). |

No new env var is mandatory — both keys have defaults. The scorer's sector
fallback (when the joined `executor_position` row has no `sector`) reuses the
shared `SectorCascade`, which in turn reads the existing `dracul.sector.ttl-seconds`
/ `dracul.sector.negative-ttl-seconds` keys (see "Sector cache" above) — no
separate TTL configuration for the scorer.

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
| `DRACUL_EXECUTOR_CHANDELIER_MULT` | `dracul.executor.chandelier-mult` | `3.0` | ATR multiple used by `StopRatchetService`/`MaintenancePipeline` to compute the chandelier stop level (highest price reached minus `chandelier-mult` × ATR for longs, mirrored for shorts). The active stop only ever ratchets up (in the position's favor), never down; the level is rounded to two decimals toward the safe side (down for longs, up for shorts) before the ratchet guard sees it, so the value checked, the value sent to the broker and the value written to the book are identical. The modify is addressed to the position's **bracket** order id (`broker_order_id`), never the stop leg id — Agora's `modify_bracket` resolves the stop leg from the bracket. When a tranche 2 is open the position holds **two** stop legs at the broker, and both are ratcheted to the *same* level — a protective stop is a price level on the underlying, not a per-tranche quantity, and the chandelier is a function of the high and the ATR only (it never reads the entry price), so there is no per-tranche level to compute. Each leg is addressed **by name** via `modify_bracket`'s `stopOrderId`, using `executor_position.stop_order_id` and `tranche2_stop_order_id`; unnamed modifies would both land in Agora's by-symbol fallback and patch one leg twice. How many legs exist is read off those two id columns, never off `stop_legs_collapsed` — that flag records that legs were once folded together by a partial close, not how many are left, and a collapse can legitimately leave both ids populated (Agora may restore more than one live leg). Two recorded ids therefore always mean two live legs and both are moved by name, collapsed or not. If one id is missing and a collapse explains it (`stop_legs_collapsed = true`), the position is ratcheted as the genuine single-leg position it is; if one id is missing with no collapse recorded, the legs cannot be named and it still logs `ESCALATE / TRANCHE_RATCHET_UNSUPPORTED`, leaving the stop untouched. A named id the broker no longer holds is rejected by Agora as `LEG_NOT_FOUND` and escalates as `ESCALATE / BROKER_UNAVAILABLE` on the first attempt (structural, never retried) rather than silently re-pricing some other stop order. If the first leg moves and the second does not, `active_stop` keeps its **old** value (the only level true of the whole position) and it logs `ESCALATE / PARTIAL_TRANCHE_RATCHET` naming the moved and the unmoved leg — a partial is never reported as a ratchet. The next maintenance pass re-sends both legs and recovers on its own. A missing `broker_order_id` likewise logs `ESCALATE / NO_BRACKET_ID`. The ratchet is also skipped silently while the chandelier sits on the wrong side of the last close, or while no close is known. A transient broker failure (rate limit / HTTP 429) is retried inside the same run before escalating — see `ratchet-retry-attempts` below. The book (`executor_position.active_stop`) is written only **after** the broker confirms the modify, so it never claims a protection the broker does not hold. |
| `DRACUL_EXECUTOR_RATCHET_RETRY_ATTEMPTS` | `dracul.executor.ratchet-retry-attempts` | `3` | Total `modify_bracket` attempts `StopRatchetService` makes per position per run before escalating `ESCALATE / BROKER_UNAVAILABLE`. Retries apply **only** to transient broker failures (rate limit / HTTP 429); a structural rejection such as a missing stop leg escalates on the first attempt, because retrying it can only delay the escalation. A value of `1` restores the pre-2026-08-04 behaviour of escalating immediately. Unrelated to `max-broker-attempts`, which is a cross-run lockout counter on the *entry* path keyed on `executor_decision` rows — this one is an in-run retry of a single maintenance call and keeps no state. |
| `DRACUL_EXECUTOR_RATCHET_RETRY_BACKOFF_MS` | `dracul.executor.ratchet-retry-backoff-ms` | `500` | Base backoff before the ratchet's first retry; doubles per further attempt (`500`, `1000`, …). |
| `DRACUL_EXECUTOR_RATCHET_RETRY_BUDGET_MS` | `dracul.executor.ratchet-retry-budget-ms` | `5000` | Wall-clock ceiling for **all** ratchet retrying in one maintenance pass, shared across every position. It measures elapsed time in the pass, not time spent retrying. The whole ratchet runs inside the agent's 30 s `fetch_open_positions` tool call, so a per-position budget would multiply with the size of the book into a tool timeout; and a slow/hung broker consumes the budget on its first attempt and is not retried, while a fast 429 (≈1 ms) leaves ample room. `0` disables retrying entirely. |
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
| `DRACUL_EXECUTOR_DRIFT_ANCHOR_ATR_MULT` | `dracul.executor.drift-anchor-atr-mult` | `0.0` | The `BELOW_ANCHOR` veto's adverse-move tolerance (× ATR) for drift-style mechanisms (`PEAD`, `INDEX_INCLUSION`); default `0.0` tolerates no adverse move past the signal's reference-price anchor. |
| `DRACUL_EXECUTOR_VALUE_ANCHOR_ATR_MULT` | `dracul.executor.value-anchor-atr-mult` | `3.0` | The `BELOW_ANCHOR` veto's adverse-move tolerance (× ATR) for value-style mechanisms (everything outside the drift set); default `3.0` allows a wider adverse move past the reference-price anchor before rejecting. |
| `DRACUL_EXECUTOR_PACE_PER_WEEK` | `dracul.executor.pace-per-week` | `2` | Maximum new positions (tranche-1 entries) per ISO calendar week; enforced by the `PACE_LIMIT` veto. |
| `DRACUL_EXECUTOR_MAX_TRANCHE` | `dracul.executor.max-tranche` | `2` | Hard cap on tranches per position; `add-tranche` rejects with `MAX_TRANCHE` once `position.tranche() >= max-tranche`. |
| `DRACUL_EXECUTOR_ENTRY_GTD_DAYS` | `dracul.executor.entry-gtd-days` | `2` | Good-till-date window for a `place-entry` limit bracket: `ExecutorPositionRepository.setEntryExpiresAt` is set to this many trading days after placement (calendar days added, then rolled forward to the next Monday if the result lands on a Saturday or Sunday — a documented approximation, no exchange-holiday calendar in v1). `EntryExpiryService` cancels (never re-prices) any entry still `WORKING`/`PARTIALLY_FILLED` once `entry_expires_at` has passed; see `documentation/api.md`'s `CANCEL_EXPIRED` decision-log action. |
| `DRACUL_EXECUTOR_INSTRUMENT_CURRENCY` | `dracul.executor.instrument-currency` | `USD` | The currency instrument-side prices/ATR/tranche amounts are assumed to be in (v1: always USD). Used as the `EntryContextAssembler`'s FX-conversion basis and as the fallback account currency when the broker account snapshot is unavailable. |
| `DRACUL_EXECUTOR_MAX_BROKER_ATTEMPTS` | `dracul.executor.max-broker-attempts` | `3` | Number of **unsuccessful runs** inside `broker-attempt-window-hours` before the executor stops attempting for a signal; below it a pending signal stays PENDING for retry. The counter is `count(DISTINCT run_id)` over the signal's `executor_decision` rows with `reject_reason='BROKER_ERROR'` whose `created_at` lies inside the window — **not** the number of broker tool calls: the agent may call the broker several times per run, and a retry chain (429 → duplicate → 429) used to burn a whole cap of 3 in a single night. Rows with a NULL `run_id` never count (no run, no attempt). The cap covers **both** broker-write paths, not just entries: on `place-entry` the pending signal is terminally `REJECTED`, on `add-tranche` no further tranche is placed and the call answers `MAX_BROKER_ATTEMPTS` (a tranche has no signal status of its own — its source signal is long `ACCEPTED`). Both paths share the one counter, and both first try to adopt an already-live broker order via `orderByRef` before placing again — that adoption check deliberately uses the **unwindowed lifetime** count, so a still-open order from several days ago is found rather than duplicated. |
| `DRACUL_EXECUTOR_BROKER_ATTEMPT_WINDOW_HOURS` | `dracul.executor.broker-attempt-window-hours` | `72` | Rolling window (hours, strict `>` bound) that `max-broker-attempts` counts failed runs in. Without a window an already-fixed defect leaves a permanent lockout; with it a signal heals by itself once its failed runs age out. Only the attempt cap is windowed — the duplicate/adoption guard stays unwindowed. |
| `DRACUL_EXECUTOR_MAX_BROKER_CALLS_PER_RUN` | `dracul.executor.max-broker-calls-per-run` | `2` | Per-run throttle: how many `BROKER_ERROR` rows a signal may already have **in the current run** before further broker calls are refused for the rest of that run. On both paths the refusal writes an `executor_decision` row with `reject_reason='BROKER_RETRY_EXHAUSTED'` and answers `{"placed": false, "reason": "BROKER_RETRY_EXHAUSTED"}`. **Not terminal** — the next run starts with a fresh budget — and deliberately not a `BROKER_ERROR` row, so the throttle cannot inflate the attempt cap it exists to protect. Adoption of an existing live order is checked *before* the throttle, so a spent budget never leaves a broker order without a DB counterpart. A position without a `source_signal_id` (manual/imported) has no counting axis and places unconditionally. |
| `DRACUL_EXECUTOR_NOTIFY_ENABLED` | `dracul.executor.notify-enabled` | `true` | Master switch for best-effort Telegram push on executor **actions** (entry placed, entry filled, exit/close, tranche-2 add, stop ratchet). Reuses the `TELEGRAM_BOT_TOKEN`/`TELEGRAM_CHAT_ID` bot (same as Daywalker). Independent of — and additional to — the executor's existing CRITICAL escalation pushes (orphaned order/position, stale pending exit). Best-effort: a push failure never affects order placement or the position book. `false` silences all five action pushes. Amounts are shown in `instrument-currency` (no FX conversion); expired/never-filled entry cancels emit no push. |
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

## Decision doc (Chronicle "how Dracul decides")

The Chronicle top-bar (i) can render a deployment-local Markdown doc explaining
how this instance decides (see `documentation/chronicle.md`). The file is mounted
read-only at runtime and never committed or baked into the image (mount procedure
in `documentation/operations.md`). Blank path = feature off; the (i) then shows
the built-in overview instead. Served by `GET /api/decision-doc` (see
`documentation/api.md`).

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_DECISION_DOC_PATH` (`dracul.decision-doc.path`) | *(blank)* | Filesystem path to a Markdown file served to the Chronicle "how Dracul decides" (i). Blank disables the feature (endpoint returns 404 and the UI falls back to the built-in overview). |
| `DRACUL_DECISION_DOC_MAX_BYTES` (`dracul.decision-doc.max-bytes`) | `1048576` | Max size (bytes) of the served file. A file larger than this is treated as absent (endpoint returns 404). |

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
