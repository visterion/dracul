# Hunting grounds

All hunting-ground market data (filings, news, recommendations, fundamentals,
earnings, index constituents, intraday, prices, OHLC) is fetched from the
co-located **Agora** service over MCP. Five neutral domain facades in
`de.visterion.dracul.hunting.agora` isolate Agora's tool schema so Strigoi
never see vendor- or provider-specific data shapes.

## Adapters

| Adapter | Source | Purpose |
|---|---|---|
| Agora (hunting fetch) | Agora MCP via five domain facades (`AgoraFilings`, `AgoraCompanyData`, `AgoraEarnings`, `AgoraReference`, `AgoraIntraday`) | Filings, news, recommendations, fundamentals, earnings, index constituents, intraday |
| Agora (prices/OHLC) | Agora MCP (`get_quote` / `get_ohlc`) | Live quotes + daily OHLC |

All deterministic hunting-ground fetch (filings, news, recommendations,
fundamentals, earnings, index constituents, intraday, prices, OHLC) now
routes through the co-located **Agora** service over MCP. Dracul no longer
runs any direct-fetch adapters for EDGAR, Finnhub, Yahoo, or Wikipedia.

> **Health:** Settings → Data Sources actively probes the Agora service
> exclusively via the `ping` MCP tool (one cheap request, cached ~60s) and shows
> ok / rate-limited / error / not-configured / timeout. See `GET /api/settings/data-sources`.
> (Spec item 19 — health probing is now Agora-only.)

## Conventions

- **The five Agora domain facades are the sole hunting-fetch seam.** Strigoi
  call a clean domain interface (`hunting/agora` package); provider selection,
  fallback, and rate-limit handling are invisible to them — Agora owns all of
  that internally.
- **Facade responses are normalised** to Dracul domain types
  (`SpinoffFiling`, `MergerFiling`, `Form4Filing`, `NewsHeadline`,
  `RecommendationTrend`, `Sp500Constituent`, `IntradayCandles`, …) before
  leaving the facade. No vendor schema leaks into Strigoi code.
- **Domain-shaping stays in Dracul.** The facades return neutral fetched
  data only; Sloan accruals (`SloanAccrualCalculator`), the confounder scan
  (`ConfounderScreen`), the analyst-revisions proxy (`RevisionsProxy`),
  equity-metric extraction (`EquityMetricsExtractor`), EPS quarter-shaping
  (`EpsHistoryShaper`), and BasicFinancials extraction
  (`BasicFinancialsExtractor`) live Dracul-side — Agora never sees
  investment-interpretation vocabulary.

## Which Strigoi uses which adapter

| Strigoi | Facade + Agora tool(s) |
|---|---|
| strigoi-spin | `AgoraFilings.searchSpinoffs` (`search_filings` 10-12B; the spin-co's registrant CIK is parsed from the filing URL via `CikExtractor.fromFilingUrl` and preserved on `SpinoffFiling.cik`) + `AgoraFilings.filingText` (`get_filing_text`, term-sheet capture) + `SpinTermsParser` (regex-based distribution ratio / record date / distribution date / best-effort parent ticker). **Lifecycle enrichment (2026-07-12):** `AgoraMarketData.quotes` (batched distribution-detection price probe) + `AgoraFilings.conceptStrict` (`get_company_concept` **by CIK** — pre-distribution balance sheet + settlement `Assets`/`filed` probe + settled-stage valuation, a ticker not yet existing at 10-12B time) + `EquityMetricsExtractor` (Finnhub market caps for spin-co and parent → `sizeRatio`) + `AgoraFilings.ownerHistoryStrict` (`get_form4_owner_history`, post-spin open-market insider buying) + `AgoraCompanyData.fundamentals`/`profile` (settled-stage P/B, FCF yield, industry) |
| strigoi-insider | `AgoraFilings.recentForm4` (`get_form4_transactions`, cluster screen) + `AgoraFilings.ownerHistoryStrict` (`get_form4_owner_history`, routine/opportunistic classification — one call per cluster) + `EquityMetricsExtractor` / `AgoraMarketData.dailyOhlcHistory` / `AgoraCompanyData.recommendationsStrict` / `AgoraEarnings` (context enrichment) |
| strigoi-echo | `AgoraEarnings.recent` (`get_earnings_window`) + `AgoraFilings.epsHistory` (`get_eps_history`) + `AgoraFilings.concept` (`get_company_concept`) + `AgoraCompanyData` (news/recommendations/fundamentals/profile) + `AgoraEarnings.nextEarningsDate` + Agora prices/OHLC |
| strigoi-lazarus | watchlist + `AgoraCompanyData.fundamentals` (`get_fundamentals`) + `AgoraFilings.fundamentalScoreStrict` (`get_fundamental_score`) + `AgoraFilings.conceptStrict` (`get_company_concept`, Altman-Z XBRL inputs) + Agora daily OHLC (timing signals) |
| strigoi-index | `AgoraReference.indexChanges` (`get_index_constituent_changes` — announced S&P/Russell adds/removes with announcement + effective dates; called once per index) + `AgoraMarketData.dailyOhlcHistory` (`get_ohlc`, ADV/volume + idiosyncratic-vol residual + run-up/reversal enrichment) + `EquityMetricsExtractor` (market cap + beta + share-count enrichment) + `MarketSignalService.residualReturns` (idiosyncratic vol) + `ConfounderScreen` (overlapping-event screen). **The old `AgoraReference.constituents` / `get_index_constituents` route was removed** in the 2026-07-12 announcement-anchored lifecycle rebuild |
| strigoi-merger | `AgoraFilings.searchMergers` (`search_filings` DEFM14A,SC TO-T) + `AgoraFilings.filingText` (`get_filing_text`, term-sheet enrichment) + `DealTermsParser` (regex-based offer price / consideration / exchange ratio / break-fee extraction) + `AgoraMarketData.quotes` (spread computation) |
| daywalker | `AgoraIntraday.candles` + `AgoraCompanyData.news`/`recommendations` + `AgoraFilings.recentForm4` |
| gropar | Agora `get_ohlc` (daily OHLC history, for RiskMetrics + currentClose) + Agora `get_indicators` (bundled exit TA per position via `AgoraResearch`) — unchanged from pre-7c |

## Cost considerations

ALL deterministic hunting fetch (filings, news, recommendations, fundamentals,
earnings, index constituents, intraday, prices, OHLC) now routes through
Agora, which owns provider keys, fallback, and rate-limit handling
internally. Dracul incurs no direct EDGAR, Finnhub, Yahoo, or Wikipedia cost
or quota.

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

## Hunting fetch via Agora (five domain facades)

As of slice 7c, all hunting-ground fetch that used to be served by direct
EDGAR / Finnhub / Yahoo / Wikipedia adapters is served by Agora over MCP,
consumed through five neutral domain facades in
`de.visterion.dracul.hunting.agora`:

- **`AgoraFilings`** — `searchSpinoffs` (10-12B), `searchMergers` (DEFM14A,
  SC TO-T), `recentForm4` (Form-4 insider transactions), `ownerHistoryStrict`
  (`get_form4_owner_history` — multi-year per-owner Form-4 history for the
  strigoi-insider routine/opportunistic classification; strict, propagates
  `AgoraUnavailableException` for the batch source-down guard), `concept` /
  `epsHistory` (SEC XBRL companyconcept series, used for the Sloan accrual
  ratio and EPS-quarter shaping respectively; `conceptStrict` is the same
  fetch but propagates `AgoraUnavailableException` instead of degrading to an
  empty series, so batch callers — the lazarus Altman-Z enrichment and the
  strigoi-spin lifecycle enrichment — can short-circuit a down source instead
  of burning their latency budget. `conceptStrict` also has a CIK-keyed overload
  (`conceptStrict(symbol, cik, tag)`): Agora's `get_company_concept` accepts a
  `cik` as an alternative to a ticker, which strigoi-spin relies on to read a
  pre-ticker spin-co's XBRL facts by its registrant CIK, and exposes the XBRL
  `filed` date on `ConceptSeries.Point` for the spin settlement probe),
  `fundamentalScore`
  (`get_fundamental_score` — a real Piotroski F-Score computed by Agora from
  SEC companyfacts, with a strict pass/fail per criterion plus a
  `fScoreCriteriaAvailable` coverage count; consumed by strigoi-lazarus's
  enrichment step, degrading to "unavailable" on any Agora failure;
  `fundamentalScoreStrict` propagates `AgoraUnavailableException` like
  `conceptStrict`, letting the lazarus batch short-circuit a down source),
  `filingText`
  (`get_filing_text` — fetches a filing's primary document as cleaned
  summary-term-sheet text; consumed via `AgoraFilings.filingText(url)` →
  `FilingText(text, available)` by strigoi-merger and strigoi-spin, fail-soft
  to `available = false` on any Agora failure).
- **`AgoraCompanyData`** — `news`, `recommendations`, `fundamentals`,
  `profile`.
- **`AgoraEarnings`** — `recent` (earnings window for PEAD candidates),
  `nextEarningsDate`.
- **`AgoraReference`** — `indexChanges` (`get_index_constituent_changes` —
  announced index-constituent changes: an add/remove ticker with its announcement
  and effective dates and a `source` of `sp_press` or `russell_reconstitution`,
  normalised to `IndexChangeEvent`; rows without a symbol or effective date are
  skipped, symbols upper-cased to the persisted natural key). The old
  `constituents` (`get_index_constituents`, S&P 500 membership) method was removed
  when strigoi-index flipped to the announcement-anchored `index_event` lifecycle
  (2026-07-12).
- **`AgoraIntraday`** — `candles` (intraday closes/volumes for daywalker).

Each facade normalises Agora's tool output straight into the retained Dracul
domain records (`SpinoffFiling`, `MergerFiling`, `Form4Filing`, `NewsHeadline`,
`RecommendationTrend`, `IndexChangeEvent`, `IntradayCandles`, `ConceptSeries`,
`EarningsObservation`) and wraps the result in `DataSourceResult` with
`source = "agora"`. **Graceful degradation:** any Agora failure surfaces as
`DataSourceResult.unavailable("agora", …)` — callers treat that exactly like
the old adapters' empty-list/null degradation; no Strigoi run dies on an
Agora hiccup.

**Index-constituent-change data provenance (Agora-side, cached).** The
`get_index_constituent_changes` tool aggregates two independently-degradable
providers inside Agora; Dracul never sees the raw feeds:

- **S&P press-release RSS** — the S&P Global press RSS is parsed for
  "Set to Join / be Removed from S&P 500" releases, with the effective date
  regex-extracted from each release's full text (`source = sp_press`). This is
  the announcement-lead half that the strigoi-index lifecycle was verified against.
- **Russell reconstitution** — a config-driven schedule (announcement =
  preliminary date, effective date) plus the free LSEG Russell-3000
  additions/deletions PDFs (PDFBox text extraction; the preliminary-dated list is
  fetched during the pre-effective lead, the final effective-dated list once
  published; `source = russell_reconstitution`). The PDFs carry only **Russell
  3000** granularity; the R1000-vs-R2000 bucket is resolved against the iShares
  IWB/IWM holdings CSVs, which are **bot-walled from server IPs** — an
  unresolvable ticker defaults to `russell2000`, so `russell1000` degrades to
  empty while `russell2000` still carries every change (a documented, safe skew).

Both providers are fail-soft (empty on unavailability), cached with per-source
TTLs, and emit only extracted structured fields (no raw RSS/PDF/CSV prose, per the
LSEG/BlackRock and S&P terms).

Domain-shaping (Sloan accruals, confounder scan, revisions proxy,
equity-metric extraction, EPS quarter-filtering, BasicFinancials extraction)
consumes the facades' neutral output and lives entirely Dracul-side — see
"Conventions" above.

## Data-source health

Every prey-producing Strigoi tool endpoint wraps its result in an output
envelope that includes a `data_source_health` object:

```json
{
  "output": {
    "candidates": [...],
    "data_source_health": {
      "status": "healthy",
      "source": "agora",
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
| `source` | string | Logical source name (`agora` for all hunting-fetch facades) |
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
| strigoi-spin | `agora` |
| strigoi-merger | `agora` |
| strigoi-insider | `agora` |
| strigoi-echo | `agora` |
| strigoi-lazarus | `agora` |
| strigoi-index | `agora` |

Voievod (no external source) and Daywalker (outside the audit scope) do
not emit `data_source_health`.

### Known limitations (v1)

- **Agora unavailability is uniform:** since 7c, all hunting Strigoi report
  `unavailable` for the same reason — the Agora MCP call failed or timed
  out. The old per-provider quirks (blank Finnhub key silently degrading to
  `healthy`-with-fewer-candidates, Wikipedia empty-result-always-means-failure)
  no longer apply; Agora's internal fallback/retry behavior is opaque to
  Dracul and out of scope for this doc.

## Prey → ExecutorSignal flow

When a hunter's `/complete` webhook persists its findings (`preyRepo.insertAll`),
the same request also feeds the guarded executor — but only when it is enabled
(`dracul.executor.enabled=true`). `PreySignalEmitter` maps each persisted `Prey`
to a pending `ExecutorSignal` via the deterministic `PreySignalMapper`:

| ExecutorSignal field | Source |
|---|---|
| `signalId` | freshly generated UUID (same as the operator inject seam) |
| `source` | `prey.discoveredBy()` (the hunter name) |
| `symbol` | `prey.symbol()` |
| `direction` | constant `BUY` — all six anomalies are long-biased (per-type nuance is a deferred fast-follow) |
| `confidence` | `prey.confidence()` |
| `mechanism` | `prey.anomalyType()` |
| `horizon` | `prey.horizon()` |
| `killCriteria` | empty (derived downstream) |
| `agentVersion` / `referencePrice` | `null` |
| `status` | `PENDING` |
| `createdAt` | `null` (DB defaults to `now()`) |

The emitter **skips** any prey whose symbol is already an open position
(`executor_position`) or already sits in a pending signal (`executor_signal`),
and dedupes repeated symbols within a single batch — the executor is never
handed duplicate work. When the executor is disabled, no emitter bean is wired
and hunts complete exactly as before (the hook is an optional
`ObjectProvider<PreySignalEmitter>` that no-ops when absent).
