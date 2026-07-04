# Hunting grounds

Market-data adapters live in `dracul-hunting-grounds/`. Each adapter
isolates one external API behind a domain-shaped interface so Strigoi
never see vendor-specific schemas.

## Adapters

| Adapter | Source | Purpose | Rate limit (free tier) |
|---|---|---|---|
| `edgar-adapter` | SEC EDGAR | Form-10 / 10-12B / Form-4 / 8-K filings | 10 req/s |
| Agora (prices/OHLC) | Agora MCP (`get_quote` / `get_ohlc`) | Live quotes + daily OHLC | Handled by Agora |
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
| strigoi-echo | Finnhub `/calendar/earnings` (primary) → Yahoo earnings calendar (fallback) + SEC EDGAR companyconcept (EPS history for SUE) + Agora prices/OHLC |
| strigoi-lazarus | watchlist + Finnhub /stock/metric (52w-low + fundamentals) |
| strigoi-index | Wikipedia S&P 500 (main constituents table, Date added column) |
| strigoi-merger | edgar-adapter (`EDGAR EFTS forms=DEFM14A,SC TO-T (deal filings, metadata-only)`) |
| daywalker | Yahoo intraday (5-min polling) + news-adapter + edgar-adapter (Form-4) |
| gropar | Agora `get_ohlc` (daily OHLC history for indicator calculation) |

## Cost considerations

Prices and OHLC are served by Agora, which owns provider selection, fallback,
and rate-limit handling — Dracul incurs no direct price-provider cost or quota.
Dracul's remaining direct hunting-ground costs are SEC EDGAR (free, 10 req/s),
Finnhub (news / earnings / fundamentals), and Yahoo (FX / intraday / earnings
fallback).

## Market data (prices / OHLC) via Agora

Dracul no longer runs its own price/OHLC provider adapters. Quotes and daily
OHLC history come from the co-located **Agora** service, consumed over Agora's
MCP front-door. The old `MarketDataPort` seam and its Yahoo / Twelve Data /
Finnhub adapters (plus `FallbackMarketDataPort`) have been removed — Agora owns
provider fallback and rate-limit handling internally.

### AgoraClient (`de.visterion.dracul.marketdata`)

A generic, portable MCP client holding one long-lived `McpSyncClient`
(Streamable-HTTP + Bearer). It exposes `JsonNode callTool(name, args)`,
serialising calls (the sync client is not concurrency-safe; Dracul's data-call
volume is low — a batch quote is a single call) and reconnecting once on a stale
session. Nothing here is Dracul-specific. Configured via `dracul.agora.base-url`
/ `token` / `timeout-ms` (see configuration.md). Agora must be up before Dracul.

### AgoraMarketData (facade)

A concrete `@Component` (no interface) that maps Agora's `get_quote` / `get_ohlc`
tool output to the retained Dracul DTOs (`MarketData` / `Quote` / `OhlcBar`) and
throws the retained `MarketDataException` on failure. It replaces the old
`MarketDataPort` — the seven consumers (watchlist refresh + controller, stop-guard,
verdict synthesis, echo PEAD screener + enrichment, gropar) inject it directly.

- `resolve(symbol)` — `get_quote {symbols:[symbol]}` for current price / day-change
  / currency, plus a best-effort 30-day close history from `get_ohlc`. Company
  name is not supplied by `get_quote`, so the symbol is used as the display
  fallback. Throws `MarketDataException(NOT_FOUND)` when the symbol has no quote,
  `MarketDataException(UNAVAILABLE)` when Agora is unreachable.
- `quotes(symbols)` — one `get_quote` call for the whole batch (watchlist on-read
  refresh). Symbols Agora doesn't return are omitted. **On Agora failure it
  returns an empty map** so the watchlist keeps its stored prices — no exception
  surfaces to the caller.
- `dailyOhlcHistory(symbol, days)` — `get_ohlc {symbol, days}`, oldest-first.
  Gropar requests `DRACUL_GROPAR_HISTORY_DAYS` (default 260, ≈ 1 trading year)
  for exit-indicator calculation; Strigoi-Echo SP2 requests OHLC for the
  candidate and the market proxy (SPY) to compute announcement-CAR, abnormal
  volume, 6-12 month momentum and average daily dollar volume. Throws
  `MarketDataException(UNAVAILABLE)` on Agora failure.

## Earnings calendar adapter

Strigoi-Echo (PEAD) resolves recent earnings announcements via a config-selectable
earnings source (`dracul.strigoi.echo.earnings-source`, default `finnhub`).

### Finnhub earnings calendar (primary)

`FinnhubEarningsAdapter` (`de.visterion.dracul.hunting.finnhub`):

- `GET /calendar/earnings?from=…&to=…` (free tier ≈ 60 calls/min, US). Auth via
  `FINNHUB_API_KEY` query token.
- Normalises rows to `EarningsObservation(symbol, companyName, reportDate,
  epsActual, epsEstimate, epsSurprisePercent, revenueActual, revenueEstimate)`
  (Finnhub populates the revenue fields).
- **Graceful degradation:** a blank `FINNHUB_API_KEY` or any error returns an
  empty list (logged) — the bee never dies on a Finnhub hiccup.

### Yahoo earnings calendar (fallback)

`YahooEarningsAdapter` (`de.visterion.dracul.hunting.yahoo`) — now the
config-selectable fallback (`earnings-source=yahoo`):

- HTTPS GET against Yahoo's (unofficial) earnings calendar endpoint
  (`/v1/finance/calendar/earnings?startdt=…&enddt=…`), reusing the shared
  `yahooRestClient`. No API key, no official SLA.
- Normalises rows to `EarningsObservation(symbol, companyName, reportDate,
  epsActual, epsEstimate, epsSurprisePercent, revenueActual, revenueEstimate)`
  (revenue fields left null) using Yahoo's documented field names
  (`ticker`, `companyshortname`, `startdatetime`, `epsestimate`, `epsactual`,
  `epssurprisepct`).
- **Graceful degradation:** the endpoint is fragile, so any failure returns an
  empty list (after one retry) and logs a warning.

## SEC EDGAR companyconcept (EPS history for SUE)

Strigoi-Echo's enrichment layer computes time-series SUE from a company's
quarterly diluted-EPS history fetched from SEC EDGAR companyconcept:

- `GET data.sec.gov/api/xbrl/companyconcept/CIK{cik}/us-gaap/EarningsPerShareDiluted.json`
  — free, **requires a `User-Agent` header** (reuses `dracul.edgar.user-agent` /
  `DRACUL_EDGAR_USER_AGENT`). Yields the historical quarterly diluted-EPS series
  used for the Foster seasonal-random-walk model and the year-ago EPS comparison.
- The ticker→CIK map is resolved from `www.sec.gov/files/company_tickers.json`
  (free, same User-Agent).
- SUE values are ranked cross-sectionally into deciles (z-band fallback for thin
  batches); seasonal alignment is date-based so it is robust to gaps in the EDGAR
  series.
- **Graceful degradation:** a missing CIK, empty series, or any fetch error skips
  the SUE signal for that symbol rather than failing the run.
- **`companyconcept` NetIncomeLoss / NetCashProvidedByUsedInOperatingActivities / Assets** — used by
  Strigoi-Echo SP3 (`EdgarFundamentals`) for the Sloan accrual ratio.

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

### Finnhub equity-metrics adapter

`FinnhubEquityMetrics` (a separate `@Component` from the fundamentals adapter) supplies
Strigoi-Echo's SP2 size/liquidity context:

- **`/stock/metric` (metric=all)** — `beta`, `marketCapitalization`, and the 52-week range.
- **`/stock/profile2`** — `finnhubIndustry` (sector).

It degrades gracefully: a blank key or a `/stock/metric` failure yields
`EquityMetrics.unavailable()`; a `/stock/profile2` failure yields a null sector with the
rest of the metrics intact.

## Finnhub news adapter

The Daywalker resolves material news and analyst-rating shifts via
`FinnhubNewsAdapter` (`de.visterion.dracul.hunting.finnhub`):

- `companyNews(symbol, from, to)` → `/company-news`; `recommendationTrend(symbol)`
  → `/stock/recommendation`. Auth via `FINNHUB_API_KEY` query token.
- Returns normalised `NewsHeadline` / `RecommendationTrend` records.
- **Graceful degradation:** a blank `FINNHUB_API_KEY` short-circuits to an empty
  list (no HTTP); any error also returns empty. Negativity / downgrade severity
  is judged by the LLM child run, not the adapter.
- Strigoi-Echo SP3 also uses Finnhub `/company-news` (confounder keyword screen, `FinnhubEventScreen`),
  `/stock/recommendation` (analyst-revision proxy, `FinnhubRevisions`), and `/calendar/earnings`
  forward window (next-earnings timing, `FinnhubNextEarnings`).

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
