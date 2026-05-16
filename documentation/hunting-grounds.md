# Hunting grounds

Market-data adapters live in `dracul-hunting-grounds/`. Each adapter
isolates one external API behind a domain-shaped interface so Strigoi
never see vendor-specific schemas.

## Adapters

| Adapter | Source | Purpose | Rate limit (free tier) |
|---|---|---|---|
| `edgar-adapter` | SEC EDGAR | Form-10 / 10-12B / Form-4 / 8-K filings | 10 req/s |
| `prices-adapter` | Yahoo / Alpha Vantage / Polygon | Daily OHLCV, splits, dividends | 5 calls/min (Alpha Vantage) |
| `news-adapter` | Finnhub / NewsAPI | Material-news firehose, analyst notes | Provider-dependent |
| `calendar-adapter` | Multiple | Earnings dates, index reconstitutions | — |

## Conventions

- **Rate-limiting and retry policy live in the adapter**, not in Strigoi.
  Strigoi call a clean domain interface; throttling is invisible to them.
- **Adapter responses are normalised** to Dracul domain types before leaving
  the adapter module. No vendor schema leaks into Strigoi code.
- **Free-tier limits are documented** per adapter (see table above). Paid
  upgrades are configurable via `application.yml` / env vars.
- **SEC EDGAR requires a `User-Agent` header**: `Name email@example.com`.
  Set via `EDGAR_USER_AGENT` env var.

## Which Strigoi uses which adapter

| Strigoi | Primary adapter(s) |
|---|---|
| strigoi-spin | edgar-adapter (Form-10, 10-12B) |
| strigoi-insider | edgar-adapter (Form-4) |
| strigoi-echo | calendar-adapter (earnings) + prices-adapter |
| strigoi-lazarus | prices-adapter (52w low screen) |
| strigoi-index | calendar-adapter (reconstitution dates) |
| strigoi-merger | news-adapter (deal announcements) + prices-adapter |
| daywalker | prices-adapter (5-min polling) + news-adapter + edgar-adapter (Form-4) |

## Cost considerations

Yahoo Finance has no official API; scraping is fragile. For production
use, prefer Alpha Vantage (free: 5 calls/min) or Polygon.io (paid,
~$50–100/month at meaningful volume). Plan adapter fallback accordingly:
configure multiple price sources and let the adapter fall through.

## Edgar parsing notes

SEC EDGAR Atom feeds surface new filings within minutes of acceptance.
XBRL parsing for quantitative data (GAAP metrics) is significantly more
complex than filing metadata extraction. Expect Etappe 2 of the build
plan (EDGAR adapter) to take 2–3 weeks of evening work.
