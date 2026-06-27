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
`/api/voievod`, `/api/daywalker`) and `/actuator/health` are always excluded — they
authenticate with their own bearer tokens and are reached in-cluster, bypassing
Cloudflare.

## Market-data adapters

| Variable | Purpose |
|---|---|
| `EDGAR_USER_AGENT` | Required by SEC EDGAR (`Name email@example.com`) |
| `ALPHAVANTAGE_API_KEY` | Alpha Vantage prices adapter (optional; free tier = 5 calls/min) |
| `POLYGON_API_KEY` | Polygon.io prices adapter (optional; paid, higher limits) |
| `FINNHUB_API_KEY` | Finnhub news and quote adapter |
| `NEWSAPI_KEY` | NewsAPI news adapter (optional) |

## Twelve Data (primary price adapter)

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_MARKETDATA_TWELVEDATA_API_KEY` (`dracul.marketdata.twelvedata.api-key`) | _(blank)_ | **Required for live prices.** Twelve Data API key. If blank, Twelve Data rejects calls with a status-error response; the watchlist gracefully serves stored prices instead. |
| `DRACUL_MARKETDATA_TWELVEDATA_BASE_URL` (`dracul.marketdata.twelvedata.base-url`) | `https://api.twelvedata.com` | Base URL for all Twelve Data requests. Override for tests. |
| `DRACUL_MARKETDATA_TWELVEDATA_CACHE_SECONDS` (`dracul.marketdata.twelvedata.cache-seconds`) | `120` | TTL (seconds) for the in-adapter batch-quote cache. Repeated watchlist loads within the window cost zero provider credits. |

**Free-tier note:** Twelve Data free tier = 8 API credits/min. A batch `/quote`
of N symbols costs N credits. The default 120-second cache ensures at most one
fresh batch call per window; a watchlist larger than ~8 tickers may exceed the
8-credit/min limit on a cold load (chunking is deferred).

## Yahoo Finance (fallback price / FX / intraday / earnings adapter)

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_MARKETDATA_YAHOO_BASE_URL` (`dracul.marketdata.yahoo.base-url`) | `https://query1.finance.yahoo.com` | Base URL for all Yahoo Finance requests. Override for tests. |
| `DRACUL_MARKETDATA_YAHOO_USER_AGENT` (`dracul.marketdata.yahoo.user-agent`) | `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36` | User-Agent sent on all Yahoo Finance requests (market-data resolve/quotes, FX, intraday, earnings). Yahoo returns HTTP 429 to a **Linux**-Chrome UA (`X11; Linux x86_64`) even though it is browser-like, so the default is a **Windows**-Chrome UA; both return 200. Override via env only if Yahoo changes its heuristics. |
| `DRACUL_MARKETDATA_YAHOO_TIMEOUT_MS` (`dracul.marketdata.yahoo.timeout-ms`) | `5000` | Connect + read timeout (ms) on the Yahoo client so a slow Yahoo can't stall a request. |
| `DRACUL_MARKETDATA_FX_REFRESH_ENABLED` (`dracul.marketdata.fx-refresh.enabled`) | `true` | Background FX-rate warm-up. Watchlist/portfolio currency conversion is served from this warmed cache and never does a live fetch in the request path. |
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

Daywalker reuses `DRACUL_PUBLIC_URL` (webhook callback base URL).

**DST caveat:** the session cron is a fixed UTC expression, so it drifts ~1h
against US market open across the EST/EDT boundary. A calendar-aware open is
deferred.

## Watchlist price refresh

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_WATCHLIST_PRICE_REFRESH_ENABLED` (`dracul.watchlist.price-refresh.enabled`) | `true` | Enables the background scheduler that refreshes watchlist prices into the DB. |
| `DRACUL_WATCHLIST_PRICE_REFRESH_CRON` (`dracul.watchlist.price-refresh.cron`) | `0 * 13-20 * * MON-FRI` | Spring cron (UTC) for the refresh. Default = every minute during the US session (EDT 13:30–20:00 UTC; widen to `13-21` for EST winter). |

## Finnhub

| Variable | Default | Purpose |
|---|---|---|
| `FINNHUB_API_KEY` | _(blank)_ | Finnhub token for Daywalker news + recommendation triggers. Blank → those triggers degrade to no-op. |
| `FINNHUB_BASE_URL` | `https://finnhub.io/api/v1` | Override for tests. |

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
| `DRACUL_EDGAR_USER_AGENT` | `dracul-research/1.0 contact@example.com` | SEC requires identifying User-Agent on EDGAR requests. Use a real contact in production. |

## Strigoi Echo

| Env var | Default | Purpose |
|---|---|---|
| `STRIGOI_ECHO_ENABLED` | `false` | Enable agent registration on Dracul startup (controller + registrar `@ConditionalOnProperty`) |
| `STRIGOI_ECHO_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for tool + completion webhooks. **Change in production.** |
| `DRACUL_ECHO_SCHEDULE` | `0 0 22 * * 1-5` | Spring cron (sec min hour dom month dow). Default: 22:00 UTC weekdays, after US close. |
| `ECHO_EARNINGS_SOURCE` (`dracul.strigoi.echo.earnings-source`) | `finnhub` | Earnings-announcement source for the pre-screen. Allowed: `finnhub` (primary — Finnhub `/calendar/earnings`) or `yahoo` (fallback — Yahoo earnings calendar). |
| `ECHO_MIN_SURPRISE` | `5.0` | Minimum positive earnings-surprise percent for the pre-screen. |
| `ECHO_MIN_PRICE` | `5.0` | Minimum current share price (USD) liquidity floor for the pre-screen. |
| `ECHO_OHLC_HISTORY_DAYS` (`dracul.strigoi.echo.ohlc-history-days`) | `320` | Trading days of daily OHLC fetched per symbol/proxy for SP2 CAR, momentum and ADV. |
| `ECHO_CAR_PROXY` (`dracul.strigoi.echo.car.market-proxy`) | `SPY` | Market proxy symbol used as the CAR market-adjustment benchmark. |

Echo reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and the shared price /
Yahoo market-data client. Its v2 signal data (academic PEAD signals: time-series
SUE deciles, revenue-surprise / double-beat, consecutive beats) additionally
requires **`FINNHUB_API_KEY`** (earnings announcements when
`earnings-source=finnhub`) and **`DRACUL_EDGAR_USER_AGENT`** (SEC EDGAR
companyconcept quarterly diluted-EPS history + ticker→CIK map for SUE).

## Strigoi Spin

| Env var | Default | Purpose |
|---|---|---|
| `STRIGOI_SPIN_ENABLED` | `false` | Register the agent + activate the webhook controller (`@ConditionalOnProperty`) |
| `STRIGOI_SPIN_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for tool + completion webhooks. **Change in production.** |
| `DRACUL_SPIN_SCHEDULE` | `0 0 4 * * 1-5` | Spring cron (sec min hour dom month dow). Default: 04:00 UTC weekdays. |
| `SPIN_LOOKBACK_DAYS` | `60` | Default Form-10-12B lookback window (days) for the pre-screen. |

Spin reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and the shared EDGAR
User-Agent. It needs no API key.

## Strigoi Lazarus

| Env var | Default | Purpose |
|---|---|---|
| `STRIGOI_LAZARUS_ENABLED` | `false` | Register the agent + activate the webhook controller (`@ConditionalOnProperty`) |
| `STRIGOI_LAZARUS_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for tool + completion webhooks. **Change in production.** |
| `DRACUL_LAZARUS_SCHEDULE` | `0 0 6 * * 1-5` | Spring cron (sec min hour dom month dow). Default: 06:00 UTC weekdays. |
| `LAZARUS_MAX_ABOVE_LOW` | `0.10` | Maximum fraction above the 52-week low to pass the price-proximity screen (default: within 10%). |
| `LAZARUS_MAX_DEBT_EQUITY` | `3.0` | Leverage cap for the solvency gate; candidates above this ratio are excluded. |

Lazarus reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and `FINNHUB_API_KEY`
(fundamentals adapter). A blank API key degrades gracefully — symbols without
fundamentals are skipped by the screener.

## Strigoi Merger

| Env var | Default | Purpose |
|---|---|---|
| `STRIGOI_MERGER_ENABLED` | `false` | Register the agent + activate the webhook controller (`@ConditionalOnProperty`) |
| `STRIGOI_MERGER_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for tool + completion webhooks. **Change in production.** |
| `DRACUL_MERGER_SCHEDULE` | `0 0 5 * * 1-5` | Spring cron (sec min hour dom month dow). Default: 05:00 UTC weekdays. |
| `MERGER_LOOKBACK_DAYS` | `45` | Default DEFM14A / SC TO-T lookback window (days) for the pre-screen (1–120). |

Merger reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and the shared EDGAR
User-Agent. It needs no API key.

## Strigoi Index

| Env var | Default | Purpose |
|---|---|---|
| `STRIGOI_INDEX_ENABLED` | `false` | Register the agent + activate the webhook controller (`@ConditionalOnProperty`) |
| `STRIGOI_INDEX_TOKEN` | `dev-token-change-me` | Bearer token shared with Vistierie for tool + completion webhooks. **Change in production.** |
| `DRACUL_INDEX_SCHEDULE` | `0 0 7 * * 1-5` | Spring cron (sec min hour dom month dow). Default: 07:00 UTC weekdays. |
| `INDEX_LOOKBACK_DAYS` | `30` | Default S&P 500 `Date added` lookback window (days) for the pre-screen (1–90). |

Index reuses `DRACUL_PUBLIC_URL` (webhook callback base URL). It needs no API key.

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

Gropar reuses `DRACUL_PUBLIC_URL` (webhook callback base URL).

## Stop-Proximity Watcher

Deterministic intraday watcher that checks every held position's live price against its persisted `active_stop` and ATR every ~15 minutes during the US session. Emits `STOP_PROXIMITY` (WARNING) and `STOP_BREACHED` (CRITICAL) alerts via the daywalker alert store, SSE panel, and Telegram. Gated off by default; enabling it requires Telegram bot-token and chat-id already configured (same `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` as gropar / morning report). This is a **Dracul-internal cron** — it does **not** register a Vistierie agent and requires no Vistierie budget change or `definition/reset`.

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_STOPGUARD_ENABLED` (`dracul.stopguard.enabled`) | `false` | Enables the `StopProximityWatcher` scheduled poll. Set to `true` to activate. Requires Telegram bot-token + chat-id. |
| `DRACUL_STOPGUARD_CRON` (`dracul.stopguard.cron`) | `0 */15 9-16 * * 1-5` | Spring cron (zone: America/New_York) for the intraday poll. Default: every 15 min from 09:00–16:59 NY time on weekdays. |
| `DRACUL_STOPGUARD_ATR_MULTIPLE` (`dracul.stopguard.atr-multiple`) | `0.5` | Width of the proximity zone as a fraction of ATR. A position is in the proximity zone when `active_stop < price ≤ active_stop + atr-multiple × ATR`. |
| `DRACUL_STOPGUARD_COOLDOWN` (`dracul.stopguard.cooldown`) | `82800` | Per-`(owner, symbol, zone)` re-alert suppression window in seconds. Default: 82800 s ≈ 23 h (≈ once per trading day). `STOP_PROXIMITY` and `STOP_BREACHED` have independent cooldowns so a breach escalates immediately even if a proximity alert was recently sent. |
| `DRACUL_STOPGUARD_NOTIFY_LEVEL` (`dracul.stopguard.notify-level`) | `WARNING` | Minimum alert severity that triggers a Telegram push (`WARNING` or `CRITICAL`). Default `WARNING` sends both proximity and breach alerts. |

Reuses `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` / `TELEGRAM_BASE_URL` — no additional Telegram config is needed.

## Morning Report (daily digest)

A daily Telegram digest of the morning report. Gated off by default; enabling it requires Telegram bot-token and chat-id already configured for the gropar notifications (i.e. `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` set). This is a **Dracul-internal cron** — it does **not** register a Vistierie agent and requires no Vistierie budget change.

The digest only sends on days with at least one **actionable** position (`SELL` or `TRIM`): when every held position is `HOLD` the push is skipped entirely, and on action days the digest body lists only the actionable positions (the `GET /api/morning-report` endpoint and the `/report` view still show all held positions, including HOLD).

| Env var / property | Default | Purpose |
|---|---|---|
| `DRACUL_REPORT_MORNING_ENABLED` (`dracul.report.morning.enabled`) | `false` | Enables the scheduled morning-report Telegram digest. Set to `true` to activate. Requires Telegram bot-token + chat-id to be configured. |
| `DRACUL_REPORT_MORNING_CRON` (`dracul.report.morning.cron`) | `0 0 7 * * 1-5` | Spring cron (zone: Europe/Berlin) for the digest send. Default: 07:00 Berlin time on weekdays. |

## Wikipedia

| Env var | Default | Purpose |
|---|---|---|
| `WIKIPEDIA_BASE_URL` | `https://en.wikipedia.org` | MediaWiki API base URL. Override for tests. |
| `WIKIPEDIA_USER_AGENT` | `Dracul/1.0 (research; contact via repo)` | `User-Agent` header sent on all Wikipedia requests. MediaWiki policy requires a descriptive UA. |
| `WIKIPEDIA_SP500_PAGE` | `List of S&P 500 companies` | MediaWiki page title passed as `page=` to `action=parse`. Override if the page is renamed. |

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

## Agent tool-fetch cache

Results of the agent `/tools/fetch-*` webhooks are cached (keyed by tool + request
params) so repeated tool calls within a run — or quick re-triggers — do not re-hit
upstream providers (EDGAR / Yahoo / market-data).

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
