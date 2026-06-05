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
| strigoi-lazarus | watchlist + Finnhub /stock/metric (52w-low + fundamentals) |
| strigoi-index | calendar-adapter (reconstitution dates) |
| strigoi-merger | edgar-adapter (`EDGAR EFTS forms=DEFM14A,SC TO-T (deal filings, metadata-only)`) |
| daywalker | prices-adapter (5-min polling) + news-adapter + edgar-adapter (Form-4) |

## Cost considerations

Yahoo Finance has no official API; scraping is fragile. For production
use, prefer Alpha Vantage (free: 5 calls/min) or Polygon.io (paid,
~$50–100/month at meaningful volume). Plan adapter fallback accordingly:
configure multiple price sources and let the adapter fall through.

## Market data adapter

Dracul resolves ticker metadata (company name, current price, 30-day history)
via the `MarketDataPort` interface (`de.visterion.dracul.marketdata`). v1 ships
a single adapter:

- **YahooMarketDataAdapter** — HTTPS GET against
  `query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=1mo&interval=1d`.
  No API key, no official SLA. The base URL is overridable via
  `dracul.marketdata.yahoo.base-url` for tests.

Failures surface as `MarketDataException`:
- `NOT_FOUND` → HTTP 422 (symbol unknown)
- `UNAVAILABLE` → HTTP 502 (Yahoo down or unreachable)

The port is intentionally minimal so additional adapters (Alpha Vantage,
Polygon) can be added without touching consumers.

## Earnings calendar adapter

Strigoi-Echo (PEAD) resolves recent earnings reports and surprises via
`YahooEarningsAdapter` (`de.visterion.dracul.hunting.yahoo`):

- HTTPS GET against Yahoo's (unofficial) earnings calendar endpoint
  (`/v1/finance/calendar/earnings?startdt=…&enddt=…`), reusing the shared
  `yahooRestClient`. No API key, no official SLA.
- Normalises rows to `EarningsEvent(symbol, companyName, reportDate, epsActual,
  epsEstimate, surprisePercent)` using Yahoo's documented field names
  (`ticker`, `companyshortname`, `startdatetime`, `epsestimate`, `epsactual`,
  `epssurprisepct`).
- **Graceful degradation:** the endpoint is fragile, so any failure returns an
  empty list (after one retry) and logs a warning — the bee never dies on a
  Yahoo hiccup. If the endpoint proves unreliable in production, the fallback is
  a configured ticker-list universe (deferred).

## Yahoo intraday adapter

The Daywalker resolves intraday price and volume via
`YahooIntradayAdapter` (`de.visterion.dracul.hunting.yahoo`):

- HTTPS GET against `query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=1d&interval=5m`,
  reusing the shared `yahooRestClient`. No API key.
- Returns `IntradayCandles(closes, volumes)` — parallel lists of 5-minute closes
  and volumes from one call, feeding both PRICE_SPIKE and VOLUME_SPIKE detection.
- **Graceful degradation:** any failure returns empty candles (logged) — the
  Daywalker poll never dies on a Yahoo hiccup.

## Finnhub fundamentals adapter

Strigoi-Lazarus resolves 52-week range and health ratios via
`FinnhubFundamentalsAdapter` (`de.visterion.dracul.hunting.finnhub`):

- `GET /stock/metric?metric=all` — returns the 52-week high/low plus health
  ratios (ROA, current ratio, debt/equity, margins, growth, FCF/share).
- Auth via `FINNHUB_API_KEY` query token.
- **Graceful degradation:** a blank `FINNHUB_API_KEY` or any error → returns
  `null`; strigoi-lazarus skips that symbol entirely.

## Finnhub news adapter

The Daywalker resolves material news and analyst-rating shifts via
`FinnhubNewsAdapter` (`de.visterion.dracul.hunting.finnhub`):

- `companyNews(symbol, from, to)` → `/company-news`; `recommendationTrend(symbol)`
  → `/stock/recommendation`. Auth via `FINNHUB_API_KEY` query token.
- Returns normalised `NewsHeadline` / `RecommendationTrend` records.
- **Graceful degradation:** a blank `FINNHUB_API_KEY` short-circuits to an empty
  list (no HTTP); any error also returns empty. Negativity / downgrade severity
  is judged by the LLM child run, not the adapter.

## EDGAR merger adapter

Strigoi-Merger resolves recent deal filings via `EdgarMergerAdapter`
(`de.visterion.dracul.hunting.edgar`):

- SEC EDGAR full-text search (`efts.sec.gov/LATEST/search-index?forms=DEFM14A,SC TO-T&…`),
  reusing the `dracul.edgar.user-agent` header. No API key.
- Metadata-only — returns `MergerFiling(symbol, companyName, formType, filingDate,
  filingUrl)` from each hit's `_source` (no per-filing XML fetch).
- **Graceful degradation:** any failure returns an empty list (logged) — the bee
  never dies on an EDGAR hiccup.

## Edgar parsing notes

SEC EDGAR Atom feeds surface new filings within minutes of acceptance.
XBRL parsing for quantitative data (GAAP metrics) is significantly more
complex than filing metadata extraction. Expect Etappe 2 of the build
plan (EDGAR adapter) to take 2–3 weeks of evening work.

## EDGAR spin-off adapter

Strigoi-Spin resolves recent spin-off registrations via `EdgarSpinoffAdapter`
(`de.visterion.dracul.hunting.edgar`):

- SEC EDGAR full-text search (`efts.sec.gov/LATEST/search-index?forms=10-12B&…`),
  reusing the `dracul.edgar.user-agent` header. No API key.
- Metadata-only — returns `SpinoffFiling(ticker, companyName, formType, filingDate,
  filingUrl)` from each hit's `_source` (no per-filing XML fetch). Tickers are
  often absent on fresh registrations (the spin-co is not trading yet) and are
  returned empty.
- **Graceful degradation:** any failure returns an empty list (logged) — the bee
  never dies on an EDGAR hiccup.
