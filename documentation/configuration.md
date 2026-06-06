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

## Authentication

| Variable | Purpose |
|---|---|
| `DRACUL_API_TOKEN` | Single bearer token for all Chronicle API requests (Phase 1) |

## Market-data adapters

| Variable | Purpose |
|---|---|
| `EDGAR_USER_AGENT` | Required by SEC EDGAR (`Name email@example.com`) |
| `ALPHAVANTAGE_API_KEY` | Alpha Vantage prices adapter (optional; free tier = 5 calls/min) |
| `POLYGON_API_KEY` | Polygon.io prices adapter (optional; paid, higher limits) |
| `FINNHUB_API_KEY` | Finnhub news and quote adapter |
| `NEWSAPI_KEY` | NewsAPI news adapter (optional) |

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
    cron: "0 0 21 * * SUN"        # Sunday 21:00 MEZ
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

## Wikipedia

| Env var | Default | Purpose |
|---|---|---|
| `WIKIPEDIA_BASE_URL` | `https://en.wikipedia.org` | MediaWiki API base URL. Override for tests. |
| `WIKIPEDIA_USER_AGENT` | `Dracul/1.0 (research; contact via repo)` | `User-Agent` header sent on all Wikipedia requests. MediaWiki policy requires a descriptive UA. |
| `WIKIPEDIA_SP500_PAGE` | `List of S&P 500 companies` | MediaWiki page title passed as `page=` to `action=parse`. Override if the page is renamed. |

## Budget limits

Budget enforcement is delegated to Vistierie. Set tier budgets in the
Vistierie routing-rule config for the `dracul` tenant, not here. The
`/api/cost` endpoint proxies the current usage from Vistierie.
