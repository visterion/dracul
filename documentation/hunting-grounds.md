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

> **Health:** Settings → Data Sources actively probes each source (one cheap
> request, cached ~60s) and shows ok / rate-limited / error / not-configured /
> timeout. See `GET /api/settings/data-sources`.

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
| strigoi-index | Wikipedia S&P 500 (main constituents table, Date added column) |
| strigoi-merger | edgar-adapter (`EDGAR EFTS forms=DEFM14A,SC TO-T (deal filings, metadata-only)`) |
| daywalker | prices-adapter (5-min polling) + news-adapter + edgar-adapter (Form-4) |
| gropar | prices-adapter (daily OHLC history for indicator calculation) |

## Cost considerations

Yahoo Finance has no official API; scraping is fragile. For production
use, prefer Alpha Vantage (free: 5 calls/min) or Polygon.io (paid,
~$50–100/month at meaningful volume). Plan adapter fallback accordingly:
configure multiple price sources and let the adapter fall through.

## Market data adapter

Dracul resolves ticker metadata (company name, current price, 30-day history)
via the `MarketDataPort` interface (`de.visterion.dracul.marketdata`).

### FallbackMarketDataPort (primary bean)

The `@Primary` `MarketDataPort` is a thin decorator wired in `MarketDataConfig`.
It serves from Twelve Data first and automatically falls back to Yahoo when
Twelve Data fails (HTTP error, exhausted credits) or cannot resolve a symbol:

- `resolve(symbol)` — try Twelve Data; on any failure, retry via Yahoo. Only if
  Yahoo also fails does the exception surface.
- `quotes(symbols)` — take Twelve Data's batch result, then fill any symbols it
  did not return (or all symbols, if the batch call threw) from Yahoo.

This keeps add-symbol, the echo/PEAD screener, and verdict synthesis working even
when Twelve Data is unavailable.

### TwelveDataMarketDataAdapter (primary provider)

The primary provider behind the fallback decorator. Requires a Twelve Data API key
(`dracul.marketdata.twelvedata.api-key` / `DRACUL_MARKETDATA_TWELVEDATA_API_KEY`).

**`resolve(symbol)`** — resolves full ticker metadata for a single symbol:

1. `GET /quote` — retrieves current price, company name, and percent day-change.
2. `GET /time_series?interval=1day&outputsize=30` — retrieves 30 daily closes.

Returns `MarketData(companyName, currentPrice, dayChangePercent, priceHistory30d)`.

**Daily OHLC history (gropar):** Gropar requests longer OHLC series for exit-indicator
calculation via `GET /time_series?interval=1day&outputsize=N` where N is controlled by
`DRACUL_GROPAR_HISTORY_DAYS` (default 260, ≈ 1 trading year). The Yahoo fallback for
this path uses `range=1y&interval=1d`. History fetches are per-position and run during
the nightly gropar agent run, not on the read path.

**`quotes(symbols)`** — batch price refresh for the watchlist on-read path:

- Single call: `GET /quote?symbol=A,B,C` — returns current price and day-change
  for all symbols in one HTTP request.
- Results are cached in-adapter for `dracul.marketdata.twelvedata.cache-seconds`
  (default `120`). Repeated loads within the TTL window cost zero provider credits.

**Graceful degradation:** if the API key is blank or Twelve Data returns a
status-error response, the `FallbackMarketDataPort` retries via Yahoo; if Yahoo
also has no value, the watchlist on-read path serves the stored price rather than
failing. No exception surfaces to the watchlist caller.

**Free-tier limit:** Twelve Data free tier = 8 API credits/min. A batch `/quote`
of N symbols costs N credits. A watchlist of up to ~8 tickers fits within one
fresh refresh per 2-minute cache window. Watchlists larger than ~8 tickers can
exceed the 8-credit/min limit on a cold load; chunking the batch request is a
future improvement.

### YahooMarketDataAdapter (fallback provider)

Non-primary Spring bean. Used as the automatic price fallback behind
`FallbackMarketDataPort` (above), and directly by the earnings calendar and
intraday hunting-ground adapters (see below). The base URL is overridable via
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

## Finnhub quote adapter (watchlist price refresh)

`FinnhubMarketDataAdapter` (`de.visterion.dracul.marketdata.finnhub`) is the
**primary** watchlist price source. It calls `GET /quote` (free tier: 60 calls/min)
for each ticker and returns current price and day-change percent.

The full watchlist price-refresh chain is **Finnhub → Twelve Data → Yahoo**:

- `WatchlistPriceRefresher` calls `FinnhubMarketDataAdapter.quotes()` first.
- On failure or missing symbols it falls back to `TwelveDataMarketDataAdapter`
  via `FallbackMarketDataPort`.
- Yahoo is the final fallback for any symbols still unresolved.

Finnhub is quote-only (no history). The history-bearing `resolve()` path used
when adding a symbol continues to flow through Twelve Data (with Yahoo as its
fallback), as Twelve Data's `/time_series` provides the 30-day price history
required for the watchlist chart.

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

## Wikipedia S&P 500 adapter

Strigoi-Index resolves recently-added S&P 500 constituents via `WikipediaSp500Adapter`
(`de.visterion.dracul.hunting.wikipedia`):

- MediaWiki API `action=parse&prop=wikitext` on "List of S&P 500 companies",
  using a dedicated `RestClient` configured by `dracul.wikipedia.base-url` and
  `dracul.wikipedia.user-agent`. No API key.
- Parses only the **main constituents table** — extracts the Symbol and
  `Date added` columns; does not fetch historical additions/removals tables.
- Metadata-only — returns `Sp500Constituent(symbol, companyName, dateAdded)`
  records; no per-entry follow-up requests.
- **Graceful degradation:** any failure (network error, parse error, missing
  columns) returns an empty list (logged) — the bee never dies on a Wikipedia
  hiccup.

## Data-source health

Every prey-producing Strigoi tool endpoint wraps its result in an output
envelope that includes a `data_source_health` object:

```json
{
  "output": {
    "candidates": [...],
    "data_source_health": {
      "status": "healthy",
      "source": "edgar",
      "detail": "2 filings fetched",
      "checked_at": "2026-06-16T02:14:07Z"
    }
  }
}
```

(The top-level array key varies per Strigoi: `candidates`, `clusters`, etc.)

### Field reference

| Field | Type | Description |
|---|---|---|
| `status` | `"healthy"` \| `"unavailable"` | Whether the external source was reachable |
| `source` | string | Logical source name (`edgar`, `yahoo`, `finnhub`, `wikipedia`) |
| `detail` | string | Human-readable summary (count, error message, …) |
| `checked_at` | ISO-8601 UTC | Timestamp of the check |

### Healthy vs unavailable

- **`healthy`** — the fetch completed. The source was reachable and the
  request succeeded. The result array may still be empty (nothing matched
  the screen criteria).
- **`unavailable`** — the fetch could not complete: blank API key, HTTP
  error, or parse failure. The result array will be empty, but for a
  different reason than `healthy`+empty.

**Key distinction:** `healthy`+empty (source worked, nothing found) is not
the same as `unavailable`+empty (source broke). Consumers should surface
`unavailable` as an infrastructure alert rather than treating it as a
quiet night.

### Caching

`unavailable` results are **not cached**. A transient failure will not
persist as an empty result until the next TTL expiry.

### Per-Strigoi source mapping

| Strigoi | `source` value |
|---|---|
| strigoi-spin | `edgar` |
| strigoi-merger | `edgar` |
| strigoi-insider | `edgar` |
| strigoi-echo | `yahoo` |
| strigoi-lazarus | `finnhub` |
| strigoi-index | `wikipedia` |

Voievod (no external source) and Daywalker (outside the audit scope) do
not emit `data_source_health`.

### Known limitations (v1)

- **strigoi-lazarus:** only a blank `FINNHUB_API_KEY` surfaces as
  `unavailable`. Finnhub HTTP outages currently degrade to
  `healthy`-with-fewer-candidates rather than `unavailable`, because the
  adapter's per-symbol graceful-degradation returns `null` silently.
- **strigoi-index / wikipedia:** an empty result is always reported as
  `unavailable`. The Wikipedia constituents page always lists ~500
  companies; an empty parse result can only mean a fetch or parse failure.

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
