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
| `ECHO_MIN_SURPRISE` | `5.0` | Minimum positive earnings-surprise percent for the pre-screen. |
| `ECHO_MIN_PRICE` | `5.0` | Minimum current share price (USD) liquidity floor for the pre-screen. |

Echo reuses `DRACUL_PUBLIC_URL` (webhook callback base URL) and the shared Yahoo
market-data client. It needs no API key.

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
| `DRACUL_GROPAR_ATR_PERIOD` | `22` | ATR look-back period for the Chandelier Exit stop (trading days). |
| `DRACUL_GROPAR_ATR_MULTIPLE` | `3.0` | ATR multiple for the Chandelier Exit stop level. |
| `DRACUL_GROPAR_MA_FAST` | `50` | Fast simple moving-average period (days) for the MA-cross indicator. |
| `DRACUL_GROPAR_MA_SLOW` | `200` | Slow simple moving-average period (days) for the MA-cross indicator. |
| `DRACUL_GROPAR_PROFIT_TARGET_PCT` | `40` | Unrealised-gain threshold (%) above which the gain indicator fires. |
| `DRACUL_GROPAR_STOP_LOSS_PCT` | `15` | Unrealised-loss threshold (%) below which the loss indicator fires. |

All exit-rule thresholds (`atr-multiple`, `ma-fast`, `ma-slow`, `profit-target-pct`, `stop-loss-pct`, `history-days`) are operator-tunable via env var without a code change.

Gropar reuses `DRACUL_PUBLIC_URL` (webhook callback base URL).

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
