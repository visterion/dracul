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
| `DRACUL_VISTIERIE_BASE_URL` | Vistierie base URL (e.g. `http://vistierie:8090`) |
| `DRACUL_VISTIERIE_TOKEN` | Bearer token Dracul uses against Vistierie |
| `DRACUL_TOOL_WEBHOOK_TOKEN` | Token Vistierie uses to call back into Dracul tool webhooks |

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

| Variable | Purpose |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Telegram bot token for Daywalker push alerts |
| `TELEGRAM_CHAT_ID` | Target chat / user ID for Daywalker notifications |

## Daywalker

| Property | Default | Purpose |
|---|---|---|
| `dracul.daywalker.enabled` | `true` | Enable / disable Daywalker |
| `dracul.daywalker.market-open` | `15:30` | Market open time MEZ (UTC+1/+2) |
| `dracul.daywalker.market-close` | `22:00` | Market close time MEZ |
| `dracul.daywalker.poll-interval` | `5m` | Price / volume poll interval |
| `dracul.daywalker.price-spike-threshold` | `0.03` | Trigger if price moves > 3% in 1 hour |
| `dracul.daywalker.volume-spike-multiplier` | `3.0` | Trigger if volume > N× rolling average |

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

## Budget limits

Budget enforcement is delegated to Vistierie. Set tier budgets in the
Vistierie routing-rule config for the `dracul` tenant, not here. The
`/api/cost` endpoint proxies the current usage from Vistierie.
