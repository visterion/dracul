# Strigoi

A Strigoi is a specialised hunter agent. Each Strigoi targets exactly
one documented market anomaly. Strigoi are registered with Vistierie as
scheduled agents; Vistierie owns the runtime, Dracul owns the domain
logic and the hunt pattern.

## Roster

| # | Name | Anomaly | Tier | Academic source |
|---|------|---------|------|-----------------|
| 1 | strigoi-spin | Spin-offs, forced selling | reasoning | Greenblatt 1997 |
| 2 | strigoi-insider | Insider cluster buys | reasoning | Lakonishok & Lee 2001 |
| 3 | strigoi-echo | Post-earnings drift (PEAD) | reasoning | Bernard & Thomas 1989 |
| 4 | strigoi-lazarus | Quality at 52w low | reasoning | Piotroski 2000 |
| 5 | strigoi-index | Index-inclusion drift | routine | S&P / Russell studies |
| 6 | strigoi-merger | M&A arbitrage | reasoning | Mitchell & Pulvino 2001 |
| 7 | strigoi-tech | Conviction basket (large tech / "future" names) | reasoning | — (breadth + staying invested; spec 2026-10-03) |

## Implementation status

| Strigoi | Status |
|---|---|
| strigoi-spin | **implemented 2026-06-05; term-sheet enrichment 2026-07-08; structured distribution fields 2026-07-11; full lifecycle persistence 2026-07-12** — EDGAR Form-10-12B spin-off registrations (last 60 days), reasoning tier (model_purpose `reasoning`), agent registered with Vistierie on startup; deterministic pre-screen surfaces recent spin-co registrations, the LLM assesses the Greenblatt forced-selling thesis (only tradeable tickers persisted). Each candidate carries `termSheet` / `termSheetAvailable` — since 2026-08-15 this is the **EX-99.1 Information Statement**, not the Form 10 shell: Agora's `get_filing_text` resolves the named exhibit off the filing's index page and reads it with `extract_mode=LEADING` (`AgoraFilings.filingText(filingUrl, "EX-99.1", "LEADING")`), a leading 24 000-character window rather than a heading seek, because the Information Statement's terms sit before any section title. A deterministic `SpinTermsParser` regex-extracts only `distributionRatio` (and a best-effort `parentSymbol` from an exchange-qualified parenthetical) server-side; **it no longer extracts dates at all** — four review rounds found real filings where the regex bound the wrong date (record date mistaken for distribution date), so date extraction from free SEC prose was dropped as a regex problem entirely. `recordDate` / `distributionDate` are instead read by the LLM straight from `termSheet` prose, and the model must quote the verbatim sentence it read each date from as `evidence`. `TermEvidenceVerifier` (`strigoi/spin/TermEvidenceVerifier.java`) checks that evidence server-side before Dracul stores anything: the evidence must be ≥30 chars, occur in the stored `termSheet` (whitespace-normalised, case-insensitive), contain exactly one date matching the one the model submitted, and carry its own field's keyword (e.g. `record date` / `holders of record` for `RECORD_DATE`) but not the opposing field's — this is what stops a sentence naming both dates ("the record date is X and the distribution date is Y") from having its record date accepted as a distribution date. A reading that fails verification is dropped (counted as `termsRejected`, logged), never stored; the LLM's own read is not otherwise trusted, verifying rather than recomputing. **As of the 2026-07-12 lifecycle rebuild** the hunter is no longer single-shot/stateless: every 10-12B registration is persisted to `spin_candidate` (V26) and tracked through a REGISTERED → WHEN_ISSUED → DISTRIBUTED → SETTLED/ABANDONED state machine across hunts, with stage-appropriate enrichment (pre-distribution balance sheet, post-distribution size/forced-selling read, post-settlement valuation) and prey promotion gated to the DISTRIBUTED forced-selling window. Prompt bumped to `1.3.0` (new nullable stage fields, confidence rubric, 3m/6-12m horizon split); further bumps since then (1.5.0 memory-search fix, 1.6.0 `distributionDateConfirmed`, current `1.9.0`: `anchorSource` explained, `symbol`/`recordDate`/`distributionDate`/`evidence` schema fields named, evidence rules spelled out — see this section's EX-99.1/`TermEvidenceVerifier` paragraph above). See "Strigoi-Spin: lifecycle persistence" below for the full flow |
| strigoi-insider | **implemented 2026-05-25; context enrichment 2026-07-12** — Form-4 cluster screener, reasoning tier (model_purpose `reasoning`; moved off Haiku/`routine` after a JSON-key-drift bug (the model emitted German field names like `thesis_de` instead of the schema's English keys, so every prey was rejected)), agent registered with Vistierie on Dracul startup, deterministic pre-screen (≥3 distinct filers, 30-day window, total > $500k purchases). Each clustered filer now carries its free-text Form-4 officer `role` (empty for non-officers), so the LLM's CEO/CFO-diversity rubric can actually weigh it. `fetch_recent_clusters` also annotates each cluster with `netInsiderDollar` (purchases minus concurrent insider sales in the window) and `concurrentInsiderSells` (distinct selling insiders); this is advisory only — the LLM weighs it into confidence, no cluster is dropped for it. Each cluster is further enriched by `InsiderEnrichmentService` (added 2026-07-12, fail-soft with per-group availability flags, never gates): `marketCap` / `adv` + `metricsAvailable` (`marketCap` via `AgoraCompanyData.fundamentalsStrict`, `adv` via Agora `get_ohlc` 20-day average dollar volume — two separate lookups, see the correction below), `analystCoverage` + `coverageAvailable` (Finnhub recommendation-trend analyst count, semantics as in echo SP3, fetched via `AgoraCompanyData.recommendationsStrict` — the outage-propagating variant, so the source-down guard below is real for this source; the swallowing default `recommendations()` stays with echo). `ytdReturn` + `ytdReturnAvailable` (calendar-YTD fraction from Agora OHLC), and `nextEarningsDate` / `daysToEarnings` + `earningsDateAvailable` (Agora earnings calendar via `AgoraEarnings`, informational only — no hard gate, unlike echo). `marketCap` and `adv` are two SEPARATE lookups under two separate guards, each falling under `metricsAvailable` only through their shared field group: `marketCap` comes from `AgoraCompanyData.fundamentalsStrict` (the `health.metrics` guard), `adv` from `AgoraMarketData.dailyOhlcHistory` (the `health.ohlc` guard, shared with `ytdReturn`) — a metrics-source outage never touches `adv`, and vice versa (see `EnrichedInsiderCluster`'s Javadoc for the same warning against conflating the two). **Metrics-fetch source-down guard corrected (2026-08-16):** the `marketCap` fetch used to go through the swallowing `AgoraCompanyData.fundamentals()`, so a total Agora outage kept calling the `health.metrics` guard's `recordSuccess()` on every cluster — the guard read "up" through it while every market cap silently fell to `null`. It now uses `AgoraCompanyData.fundamentalsStrict` (propagating `Scope`), so an outage on this source behaves exactly like the coverage/owner-history sources: it trips the guard, and every cluster that loses it is counted into `EnrichedInsiderBatch.degradedClusters` and reported as `partial` in `data_source_health`, instead of the run looking `healthy` while the market-cap dampener silently went dark. Latency guard: clusters are sorted by `totalDollarValue` descending before the 25-cluster cap (truncation is logged, the smallest are dropped); a source is skipped for the rest of the batch once `EnrichmentSourceGuard` declares it down, and with ≥2 sources down enrichment is skipped entirely for the remaining clusters — a dead Agora tool cannot blow the webhook budget (60 s since 2026-08-04, `InsiderDefaults.FETCH_TIMEOUT_SECONDS` — raised together with the Agora-side Form-4 budget, see below; changing it needs the agent-definition reset). **Form-4 timeout (2026-08-04):** correcting the EFTS forms token grew the market-wide 7-day Form-4 result from 42 to 1 697 hits, and the scan now runs 33.4 s end-to-end (measured on production: 3.7 s EFTS paging + a 29.9 s archive-fetch phase bounded by Agora's own 30 s `FORM4_DEADLINE_MS`). Against Dracul's 25 000 ms MCP client timeout every single run therefore ended in `TimeoutException`, reported as `items=0 ... status=unavailable`. The fix is a per-tool budget — `dracul.agora.tool-timeout-ms["[get_form4_transactions]"]` = 45 000 ms (`DRACUL_AGORA_FORM4_TIMEOUT_MS`) — rather than a higher global timeout, so a slow Form-4 scan does not license a 45 s hang on a quote lookup. Agora's deadline was deliberately NOT lowered: that would reduce data. See `documentation/configuration.md` for the arithmetic. **Receding-walk Form-4 window (2026-08-06):** one call reads only the newest ~272 filings of its window, and a NARROW window is the worst place to spend that budget: measured on production Agora 2026-08-05, a one-day window (`2026-08-04`) returned 13 transactions and zero open-market buys, while the 8-day window `2026-07-29..2026-08-05` returned 676 transactions of which 191 were dated 2026-08-04. EFTS orders by `file_date` descending and SEC §16(a) grants two business days, so the filings filed on a given day mostly report trades from the previous one or two days, which the transaction-date filter then discards. `AgoraFilings.recentForm4` therefore keeps `from` fixed and recedes only the `to` bound (2 days per call, derived from the smallest measured reach of a call: `to=08-05` covered back to 08-03, `to=08-03` to 07-30, `to=08-01` to 07-29), merging the calls into a record-keyed set — consecutive calls overlap on purpose so no day falls between them, and the union of the three measured calls is ~1,400 transactions against 676 for a single call. The default `lookback_days=7` (an inclusive 8-day window) costs FOUR calls, capped at `MAX_WINDOW_SLICES` = 10. **Truncation means the walk did not reach `from`** (slice budget exhausted, or a call failed) — the calls' own `truncated` flags are deliberately not OR-ed, since every call of this walk is a cut of its own window by construction and OR-ing them would flag every answer while saying nothing. `partial` is still OR-ed. Worst case 10 × 45 s = 450 s, so `InsiderDefaults.FETCH_TIMEOUT_SECONDS` was raised 60 s → **600 s** (a third of Vistierie's 1800 s `max_run_seconds`); the 150 s above the Form-4 worst case is not spare, it is where the `InsiderEnrichmentService` calls in the same request live. That change needs the agent-definition reset. `InsiderToolTimeoutBudgetTest` pins slice cap × configured Agora budget < webhook timeout. The prompt's rubric (v2, `1.2.0`) up-weights small/mid-cap + low-coverage names (Lakonishok-Lee information asymmetry), dampens well-covered large caps, backs the value-trap risk with the `ytdReturn` number, adds a `daysToEarnings` < ~10 timing caveat to risks, and demotes CEO/CFO role diversity to a secondary refinement of the cluster/conviction picture. **Routine/opportunistic classification (added 2026-07-12, prompt `1.3.0`)**: each filer is classified `OPPORTUNISTIC` / `ROUTINE` / `UNKNOWN` (Cohen, Malloy & Pomorski 2012) from their multi-year Form-4 history fetched via `AgoraFilings.ownerHistoryStrict` (`get_form4_owner_history`) — ONE call per cluster (the tool returns every reporting owner of the company at once), obeying the same availability source-down guard as the other enrichment sources. `RoutineClassifier` rule: ROUTINE when ≥2 distinct prior calendar years each carry an open-market purchase (code `P`) in the same month ±1 as the current buy (Dec/Jan wrap included; a positive cadence wins even on a `truncated` history); UNKNOWN when the history is `truncated` or carries fewer than 3 purchases (absence of a cadence is not trusted — never scored as opportunistic); OPPORTUNISTIC otherwise. The cluster carries `opportunisticShare` (opportunistic ÷ classifiable), `classifiedFilers`, `unknownFilers`, `classificationAvailable`; each filer also carries `sharesOwnedFollowing`, `purchaseAsPctOfHoldings` (relative conviction) and `planned10b5_1` (tri-state Rule 10b5-1(c) plan flag — derived from the same owner history, `null` = unknown ≠ false). **Source-down guard corrected (2026-08-06):** `EnrichmentSourceGuard` used to declare a source down on the exception TYPE alone, so a single per-item error disabled it for the whole batch. On the 2026-08-06 production run `Agora tool error: Yahoo Finance OHLC returned HTTP 404 NOT_FOUND` (Yahoo does not know that symbol) and `Agora tool error: no CIK for N/A` (one issuer that would not resolve) each disabled a source after one cluster, together crossed the ≥2 rule, and switched enrichment off for the entire run — reported as `items=1 (partial=false truncated=false status=healthy)`. `AgoraUnavailableException` now carries a `Scope` set at the throw site in `AgoraClient`: `SOURCE` when Agora produced no answer at all (transport, empty body, unparseable body) and `REQUEST` when Agora answered with an error envelope about that one call. The guard trips immediately on `SOURCE` (or a non-Agora `MarketDataException(UNAVAILABLE)`), and on `REQUEST` only after **3 consecutive** such errors with no success in between; a `NOT_FOUND` or an unrelated `RuntimeException` neither counts nor resets the run. No message-substring matching is involved. The scope survives `AgoraMarketData`'s re-wrap into `MarketDataException(UNAVAILABLE)` via the cause chain. **Degradation reporting (2026-08-06):** the enrichment returns `EnrichedInsiderBatch` (clusters + `truncated` + `degradedClusters`) instead of a bare list, and `StrigoiInsiderWebhookController.mergeHealth` ORs it into `data_source_health` — the 25-cluster cap as `truncated`, clusters that lost at least one enrichment source as `partial` (mirrors the merger hunter). 10b5-1-planned buys are **marked, not dropped** (aff10b5One is tri-state; `null` is not `false`, so a hard drop would silently discard unknown-plan buys). The `1.3.0` prompt makes `opportunisticShare` the PRIMARY criterion — a routine-dominated cluster is skipped/dampened regardless of dollar size or filer count — uses `purchaseAsPctOfHoldings` as an amplifier, dampens 10b5-1 buys, treats UNKNOWN/`classificationAvailable=false` conservatively (never as opportunistic), and reworks the confidence bands around the opportunistic read rather than raw filer-count + dollar. |
| strigoi-echo | **implemented 2026-06-02; signal upgrade (v2 SP1) 2026-06-24; market-reaction signals (v2 SP2) 2026-06-27; kill-criteria polish 2026-07-13** — reasoning tier (model_purpose `reasoning`; moved off Haiku/`routine` after a JSON-key-drift bug (the model emitted German field names like `thesis_de` instead of the schema's English keys, so every prey was rejected)), agent registered with Vistierie on Dracul startup, deterministic long-only pre-screen (current price ≥ $5, capped at `dracul.strigoi.echo.max-candidates` strongest-by-EPS-surprise, default 33 — see "Strigoi-Echo: news index/detail split" below). Earnings announcements come from **Finnhub `/calendar/earnings`** (primary), with the Yahoo earnings calendar demoted to a config-selectable fallback. A deterministic enrichment layer replaces the old raw-5%-surprise signal with academic PEAD signals: **time-series SUE** (Foster seasonal-random-walk, from SEC EDGAR quarterly diluted-EPS history with date-based seasonal alignment) ranked cross-sectionally into **deciles** (z-band fallback for thin batches), **revenue-surprise / double-beat**, and **consecutive seasonal beats**. SP2 further enriches surviving candidates with market-reaction signals from daily OHLC and Finnhub metrics (see SP2 section below). The LLM applies a SUE-based confidence rubric (not a fixed 5% threshold); each emitted prey echoes its numeric **SUE + decile** in `signals`. Long-only. Prompt bumped to `1.2.0` (2026-07-13): the kill-criteria section now includes a worked, concrete price example (a close below `currentPrice` — the price at the time the thesis was flagged — stated as a dollar level, e.g. "dead if it closes below $54.20"), aligning it with the concrete-price-example style already used by strigoi-lazarus/merger/spin; no field renames, thesis unchanged. |
| strigoi-lazarus | **implemented 2026-06-05; real Piotroski F-score (Slice 2b) 2026-07-07** — watchlist-scoped; screens watchlist names within ~10% of their 52-week low with a light solvency gate (positive ROA or free cash flow, modest leverage), plus a **cheapness (valuation) gate** (must be cheap by price-to-book or price/FCF-per-share) and a hard drop on high Sloan accruals — both deterministic and applied server-side. The F-Score is no longer judged qualitatively by the LLM: it is computed deterministically via Agora's `get_fundamental_score` tool (strict scoring + a `fScoreCriteriaAvailable` coverage count from SEC companyfacts) and attached to each surviving candidate. The reasoning-tier LLM then applies the ranking/confidence rubric — rank by the F-Score RATIO (`fScore / fScoreCriteriaAvailable`, ≥0.67 to surface; below 6 available criteria always skips regardless of ratio, prompt `1.7.0`, 2026-08-10 — see "F-Score ratio + mega-cap path in the prompt (2026-08-10)" below) — and narrates the thesis, rather than scoring the F-Score itself, and emits `QUALITY_52W_LOW` Prey. Each enriched candidate also carries `cfoExceedsNetIncome` **plus `cfoExceedsNetIncomeAvailable`** (added 2026-07-12): because the accruals hard-drop already removes every candidate with an available-but-false signal server-side, a wire-level `false` only ever means "not computable" — the availability flag makes that explicit, and the prompt treats unavailable as unknown (mild confidence dampening), not as a quality warning. **Timing/stabilization signals (added 2026-07-12)**: each surviving candidate additionally carries three deterministic signals computed server-side from one Agora daily-OHLC query (~260 trading days) — `priceVs50dMa` (last close vs the 50-day MA, decimal fraction), `weeksSinceNewLow` (full weeks since the ~52-week closing low; 0 = fresh low), `momentum3m` (~63-bar price change, decimal fraction) — plus `timingAvailable` (false only when all three are null; individual fields may still be null on short history). The prompt uses them for a "no falling knife" rule: a fresh low (≤ ~2 weeks) with the price clearly below the 50-day MA means skip or dampen hard regardless of `fScore`; ≥ ~4 weeks since the low or price above the 50d MA reinforces the setup; `momentum3m` near zero after a decline reads as base building. Fail-soft per candidate; an OHLC failure disables the OHLC source for the remaining candidates of the batch only under the shared `EnrichmentSourceGuard` rule (Agora produced no answer at all, or 3 consecutive per-request errors — see "Source-down guard corrected (2026-08-06)" in the strigoi-insider row); a symbol-specific NOT_FOUND or a single per-request error does not. **Altman-Z distress screen (added 2026-07-12)**: each surviving candidate also carries `zScore` (classic Altman Z, 1968; scale 2) + `zScoreAvailable`, computed server-side by `AltmanZCalculator` from SEC XBRL concepts via Agora's `get_company_concept` (Assets, AssetsCurrent, LiabilitiesCurrent, Liabilities, RetainedEarningsAccumulatedDeficit as same-date balance-sheet instants; OperatingIncomeLoss as EBIT and Revenues — with the F-score's fallback tag chain — as latest-fiscal-year annual flows) plus the Finnhub market cap already fetched for the screen (USD millions, converted ×10⁶ to USD for X4). No partial Z: any missing input, date misalignment, or non-positive liabilities → `zScoreAvailable=false`, `zScore=null`. Z is attempted for every surviving candidate whenever the concept source is still up, decoupled from the F-score (non-US names often carry a sparse F-score while their concept balance sheet is present). A data-less symbol returns ok-empty concepts and never throws; a concept fetch that does throw goes through the shared `EnrichmentSourceGuard` (2026-08-06), so it disables Z for the remaining candidates of the batch only on a source-scoped failure or 3 consecutive per-request errors — a single per-request error costs that one candidate its Z. The prompt applies a distress VETO: Z < 1.8 → do not emit, regardless of `fScore`/timing; 1.8–3.0 → grey zone (dampened confidence, Z named in `risks`); > 3.0 → solid; `zScoreAvailable=false` → unknown, judge conservatively, never invent a Z. Caveat in the prompt: Z is calibrated on industrials and unreliable for financials (banks/insurers, recognized by `companyName` patterns since the payload has no sector field) — there it is ignored in either direction. **Batch cap + F-score guard (added 2026-07-12)**: the enrichment sorts candidates by `pctAboveLow` ascending (closest to the 52w low first — the only meaningful priority available before any enrichment data is fetched) and caps at 25 per batch (log line on truncation, mirroring the insider cap). The F-score fetch itself now uses the strict variant (`fundamentalScoreStrict`) and sits behind the same `EnrichmentSourceGuard` (2026-08-06): a source-scoped failure or 3 consecutive per-request errors disable the fetch for the remaining candidates of the batch, while a single per-request error (an unresolvable issuer) costs only that candidate its score (candidates ride through score-less/fail-soft, exactly as with an unavailable score). Candidates that came back missing any enrichment source are counted (`EnrichedLazarusBatch.degradedCandidates`) and reported as `partial` in `data_source_health`; candidates that vanished (accruals hard-drop, the 25-candidate cap) stay on the existing `enrichmentDropped` counter. **Forward revisions + analyst coverage (added 2026-07-12)**: each surviving candidate also carries `netEstimateRevisionsProxy` / `netEstimateRevisionsDirection` (the echo SP3 recommendation-trend delta, reused via `RevisionsProxy` — latest-period net minus previous-period net of strongBuy+buy−sell−strongSell; `up`/`down`/`flat`) and `analystCoverage` (latest-period analyst count via `AnalystCoverage`, from the SAME `get_analyst_estimates` response — no extra call), plus ONE shared `revisionsAvailable` flag (echo's two flags are always equal by construction, so lazarus carries one; false ⇒ all three fields null). Costs one additional Agora call per candidate, fail-soft, with the same `EnrichmentSourceGuard` source-down rule as the OHLC fetch (symbol-specific failures and single per-request errors do not disable the source) — fetched via `AgoraCompanyData.recommendationsStrict`, the outage-propagating variant (the default `recommendations()` swallows outages into an empty list, which would make the guard dead code and burn a dead ~16s call per remaining candidate). The prompt uses it as a forward-looking check on the backward-looking TTM fundamentals: a clearly negative revisions direction = value-trap warning → dampen confidence + name it in `risks` — explicitly a DAMPENER, not a veto (severity ladder: fScore<6 skip > Z<1.8 veto > falling-knife veto > revisions dampener); `up`/`flat` near the low = quiet reinforcer; low `analystCoverage` = mild advisory neglect up-weight, high = mild dampener; `revisionsAvailable` false = unknown/conservative, never invented. **Depot dedup (added 2026-07-13)**: the candidate universe (market-wide since 2026-08-04, see below; the user's watchlist names before that) is filtered against the live depot-1 positions (`HeldPositionService.openPositions`, by symbol) before screening — a watchlist name already held is not a "new" quality-at-low candidate. A depot-down fetch (fail-soft, empty position list) excludes nothing rather than erroring. **Market-wide universe (2026-08-04)**: the universe is now the S&P 500 via Agora `get_index_constituents` plus the watchlist, behind a cheap `52w_range` pre-filter (batched via `get_indicators_batch` since 2026-08-06, ~5 Agora calls per run instead of ~490), with every per-symbol and budget loss reported as `partial`/`truncated` and an empty universe reported as `unavailable` — see "Lazarus market-wide universe + honest health" below. **USD-normalised market cap on the wire (2026-08-09)**: each enriched candidate now also carries `marketCapUsdMillions` + `marketCapAvailable`. `LazarusCandidate.marketCap()` is in millions of the REPORTING currency (BMW.DE `36294` EUR, 0941.HK `1522877` CNY on production 2026-08-09; null `reportingCurrency` = USD). `LazarusEnrichmentService` warms `FxService` once per distinct non-USD reporting currency in the batch before the candidate loop, then per candidate: null `marketCap` → unavailable; null/`"USD"` `reportingCurrency` → passed through unconverted, no FX call; any other currency → converted ONLY when `FxService.hasRate` confirms a cached rate, otherwise unavailable. `FxService.convert` is never called without a preceding successful `hasRate` check, because on a cache miss it silently returns the amount unconverted rather than blocking — reading it as success would misread e.g. CNY millions as USD millions. `marketCapAvailable=false` always means unknown, never "small". **Mega-cap exemption from the cheapness gate (2026-08-09; made currency-agnostic 2026-08-13)**: a candidate whose USD-normalised market cap is at least `dracul.strigoi.lazarus.mega-cap-usd-millions` (default 100 000, USD millions, `0` disables it) skips the price-to-book/price-to-FCF cheapness gate — the solvency gate, leverage cap and 52-week-low unit guard still apply unchanged. The screener itself no longer decides this: it only forwards `cheapGatePassed` (whether the candidate cleared P/B or P/FCF on its own) plus the raw `marketCap()`/`reportingCurrency()`. `StrigoiLazarusWebhookController` resolves each survivor's listing (`LazarusListingResolver`, see "Listing resolution replaces symbol-shape guessing" below), converts the market cap to USD once via `FxService`, and keeps the candidate when `cheapGatePassed` OR the USD size clears the threshold — for ANY resolved listing, not only a `null`/`"USD"` reporting currency as before 2026-08-13 (0941.HK's ~226 Bn USD reported in CNY now gets the exemption it used to be fail-closed out of). **F-Score ratio + mega-cap path in the prompt (2026-08-10, prompt-only, `1.6.0`→`1.7.0`)**: the old absolute "skip below 6" rule was measuring reporting coverage, not company quality — `fScoreCriteriaAvailable` came back 5, 6 or 8 (never 9) across six production runs, so a name with only 5 available criteria could never reach an absolute `fScore` of 6 no matter how healthy it was. The prompt now ranks by the ratio `fScore / fScoreCriteriaAvailable` (≥0.67 to surface, same bar as the old 6-of-9) with a data floor (`fScoreCriteriaAvailable < 6` skips regardless of ratio, reasoned as thin data, never as company quality) to stop the ratio rewarding a thin 4/4. The prompt also now describes the wire's `marketCapUsdMillions`/`marketCapAvailable` fields and instructs the LLM to apply, on top of the screener's mega-cap cheapness exemption, one additional judgement-level requirement for candidates ≥100 000 USD millions: `revenueGrowthYoy ≥ 0` AND `epsGrowthYoy ≥ −10` (percent, null in either never reads as 0). This is explicitly named in the prompt as a weak floor, not a selection mechanism — 75.4% of the S&P 500 already clears it; it only catches the obvious collapse (measured: APTV −75.8% EPS, PPL −58.8% revenue). The Altman-Z veto, falling-knife rule and revisions dampener are unchanged for mega-caps. **Listing resolution (2026-08-13)**: `marketCapAvailable=false` now has a third cause besides an absent raw market cap or an unconvertible currency — the candidate's listing could not be resolved (`ListingResolution.UNKNOWN`), so it is unknown whether the reported fundamentals even describe the requested security; prompt bumped to `1.8.0` to name this in the `marketCapAvailable` explanation, no other rule changed (see "Listing resolution replaces symbol-shape guessing" below). |
| strigoi-index | **implemented 2026-06-06; liquidity enrichment 2026-07-11; announcement-anchored lifecycle 2026-07-12** — routine tier (model_purpose `routine`), agent registered with Vistierie on startup. **As of the 2026-07-12 lifecycle rebuild** the hunter no longer reads the Wikipedia `Date added` column (effective-date-only, i.e. already too late). It ingests announced constituent changes from Agora's `get_index_constituent_changes` (S&P press-release RSS + Russell reconstitution — each change carrying both an **announcement date** and an **effective date**), persists every change to `index_event` (V27) and tracks it through an ANNOUNCED → EFFECTIVE → POST → CLOSED / ABANDONED state machine across hunts. The logic is flipped: the LLM judges whether the **today → `effectiveDate`** forced-buy window is still open (not whether an addition already happened) and emits `INDEX_INCLUSION` Prey **only** from ANNOUNCED rows; EFFECTIVE/POST rows are informational (run-up/reversal observation only). Prey promotion is hard-gated to the still-open ANNOUNCED window (source-aware: S&P 5 trading days, Russell 20). Prompt bumped to `2.0.0` (logic-flip). See "Strigoi-Index: announcement-anchored lifecycle" below for the full flow |
| strigoi-merger | **implemented 2026-06-05; term-sheet enrichment 2026-07-08; structured deal terms + server-computed spread 2026-07-11; expected-value data (Mitchell & Pulvino) 2026-07-12** — EDGAR EFTS `forms=DEFM14A,SC TO-T` (definitive merger proxies + tender offers, last 45 days), reasoning tier (model_purpose `reasoning`), agent registered with Vistierie on startup; surfaces recent SEC deal filings (DEFM14A definitive merger proxies + SC TO-T tender offers); the reasoning-tier LLM judges the spread and closing probability and emits `MERGER_ARB` Prey. Each candidate carries `termSheetDigest` / `termSheetAvailable` — **a bounded digest of the filing's summary term sheet, no longer the raw text** (see "Strigoi-Merger: term-sheet digest and the derived cap" below) — plus `lastPrice` / `priceAvailable`; the LLM reads the closing-risk sections out of the digest and computes the spread vs `lastPrice`, fail-soft (conservative judgement) when unavailable. A deterministic `DealTermsParser` regex-extracts `offerPrice` / `considerationType` (cash/stock/mixed) / `exchangeRatio` / `breakFee` from the fetched filing text server-side, and `MergerEnrichmentService` computes `spreadPercent = (offerPrice − lastPrice) / lastPrice × 100` when both are available; the LLM prefers these server-extracted fields (verifying rather than recomputing) and falls back to reading `termSheetDigest` itself when any is `null`. `DealTermsParser` also extracts the deal time-axis dates — `agreementDate` (the announcement anchor; the feed's DEFM14A/SC TO-T land weeks/months after announcement, so `lastPrice` is already the arb price), `expectedCloseDate` (quarter/half estimates mapped conservatively to the period end), and a separate `outsideDate` (End Date, never used as the close estimate). `MergerEnrichmentService` then adds the Mitchell & Pulvino (2001) expected-value inputs: `unaffectedPrice` / `unaffectedPriceAvailable` (close of the last trading day before `agreementDate`, from ONE ~400-day Agora daily-OHLC query per candidate, same latency-guard/source-down short-circuit as Lazarus), `daysToClose`, `annualizedSpreadPercent` (`spreadPercent × 365 / daysToClose`, guarded to `daysToClose ≥ 1`), and `breakDownsidePercent` (`(lastPrice − unaffectedPrice) / lastPrice × 100`, the deal-break cliff). The prompt (v1.2.0) reframes the judgement around expected value — weigh `annualizedSpreadPercent` against `breakDownsidePercent`, don't chase wide spreads, dampen stock/mixed deals (unhedged acquirer risk), couple the horizon to `expectedCloseDate`/form type, and treat the payoff as negatively-skewed with an event-based (not trailing-stop) exit. **Degradation reporting (2026-08-04):** the enrichment returns `EnrichedMergerBatch` (candidates + `truncated` + `filingTextFailures` + `oversizedFilings`) instead of a bare list, so its own losses reach `data_source_health` — previously the health came exclusively from `searchMergers` and both losses were invisible. The candidate cap moved from a hard-coded 25 to `dracul.strigoi.merger.max-candidates` (**default 30**, derived from the bridge's tool-result cap — see below) and reports a cut as `truncated`; term sheets that could not be fetched report as `partial`, naming separately how many were Agora refusing an oversized document (not an outage, and it will fail again on retry). **Payload fix (2026-08-04):** the raw term sheet no longer rides the response at all — `termSheet` became `termSheetDigest`, a ≤ `dracul.strigoi.merger.term-sheet-digest-chars` (default 700) digest of the risk-bearing sections |
| strigoi-tech | **implemented 2026-10 (disabled by default)** — nightly 22:30 UTC Mon–Fri; tools fetch_tech_book / check_tech_candidate / search; picks become TECH_CONVICTION prey → executor signals with exit profile CONVICTION; catastrophe exits flag open CONVICTION positions |

### Strigoi-Echo SP2: market-reaction signals

**SP2 market-reaction signals (deterministic, added 2026-06-27).** Each surviving
candidate is further enriched from daily OHLC and Finnhub metrics:

- `announcementCar1d` / `announcementCar3d` — market-adjusted abnormal return around the
  report day, computed vs the market proxy (default SPY) and beta-adjusted when beta is
  known. A positive CAR with the same sign as the surprise is the strongest confirming
  signal; a negative CAR (the market already faded the beat) is a hard counter-argument.
- `announcementCar2d` (2026-10-02) — the same abnormal return summed over the report-day bar and
  the next bar ([0,+1]). It is the confirmation signal the prompt uses: `announcementCar1d` reads
  only the report-day bar, which for an after-close reporter is the day before the market saw the
  numbers.
- `preReportClose` (2026-10-02) — the last close before the report-day bar; echo's
  `kill_close_below` level (the announcement reaction fully given back).
- `abnormalVolume` — report-day volume / trailing 20-day average volume.
- `momentum6_12m` — price return over the 6-12 month window (price + earnings momentum
  compound).
- `adv`, `marketCap`, `beta`, `sector` — liquidity and size, used to dampen confidence on
  large, heavily-arbitraged names.

Every SP2 field carries an availability flag (`carAvailable`, `metricsAvailable`); missing
OHLC or metrics degrade the affected field conservatively and never abort the run.

**Day-0 deferral (2026-10-02).** Echo runs at 22:00 UTC on the report day; for an after-close
reporter the market has not reacted yet. The session clock is the MARKET proxy series (the same
SPY-proxy OHLC already fetched once per batch for the CAR denominator), never the candidate's own
stock series: a candidate is deferred only when the MARKET series is non-empty and its last bar is
on or before the report date; it reappears in a later run (lookback 7 days). When the market series
is empty/unavailable, `EchoEnrichmentService` falls back to the older stock-only rule (the
candidate's own series decides). A stale or halted stock series — its own last bar on/before the
report date while the market has already moved on — is KEPT, not deferred, and counted separately.
An empty or failed stock series is never skipped and never counted as stale (the candidate reaches
the LLM with `carAvailable=false`), so an OHLC outage on one symbol — or on the whole batch — cannot
become a silent quiet night. Both counters are logged at INFO (`echo enrichment:
skipped_no_post_report_bar=N stale_stock_series=N …`) and added to the fetch tool's
`data_source_health.detail` as `skipped_no_post_report_bar=N` and `stale_stock_series=N` —
informational, they change neither `status` nor `partial`/`truncated`.

### Strigoi-Echo SP3: earnings-quality + event/timing gate

**SP3 earnings-quality + event/timing gate (deterministic, added 2026-06-27).** Before a
candidate reaches the LLM it must pass a server-side hard gate:

- **Sloan accrual ratio** `(netIncome − operatingCashFlow) / totalAssets` from EDGAR. Above
  `echo.gate.max-accrual-ratio` (default 0.10) the beat is accrual-driven (not cash-backed) and
  the candidate is dropped.
- **Confounder screen** over Finnhub company news since the report date (M&A, restatement,
  guidance cut, dilution, investigation). Any hit drops the candidate — the announcement-CAR is
  then not the drift signal. (EDGAR 8-K item-code parsing is a deferred refinement.) **Known gap:**
  echo's confounder gate scans an already-fetched headline list with `ConfounderScreen.confounders(List)`,
  which makes no Agora call and so has no source-down state to carry — a news outage on this path
  still reads as "no confounders found", not as "unknown". This is unlike the index hunter, which
  calls the health-aware `ConfounderScreen.confounders(String, LocalDate)` overload and persists
  `confoundersUnknown` for exactly this case (see "Strigoi-Index" below). Fixing echo the same way
  needs a source-aware `gate.evaluate` result, which is out of scope here — this is a deliberately
  open gap, not an oversight.
- **Timing gate** — if the next earnings report is within `echo.gate.min-days-to-next-earnings`
  (default 10) the candidate is dropped (next-report event risk overlays the drift).

Survivors carry soft signals for the LLM: `accrualRatio`, `netEstimateRevisionsProxy`
(analyst recommendation-trend delta) / `netEstimateRevisionsDirection` (the sign of that
proxy — the analyst recommendation-revision direction, not management guidance), and
`nextEarningsDate` / `daysToNextEarnings`. All SP3 lookups degrade gracefully (availability flags) and never abort a run.

Each candidate also carries `analystCoverage` (analyst count from the latest recommendation
trend) and `coverageAvailable`. Low coverage marks an under-followed name where PEAD drift
tends to be stronger and persist longer — the prompt treats it as a mild neglect-premium
up-weight (high coverage is a mild dampener); it is advisory only, the LLM decides.

### Strigoi-Echo: news index/detail split

**Three tools (added 2026-07-28), in order: `fetch_recent_pead_candidates`,
`fetch_candidate_news`, `search`.** Echo used to carry the full per-candidate news
(headline + summary) in the `fetch_recent_pead_candidates` response. That summary text
alone accounted for ~44% of a candidate's payload and, on a batch with several
newsy candidates, pushed the tool-call result past Vistierie's bridge limit — the
bridge silently offloaded the oversized result to a file the agent cannot read, and Echo
returned nothing for seven days without any error surfacing.

**What that limit actually is (measured 2026-08-04; the "~95 kB" quoted here before was
folklore).** Every Vistierie tool reaches the model as an in-process SDK MCP tool, and the
Claude Code CLI caps an MCP result at `MAX_MCP_OUTPUT_TOKENS` = 25 000 tokens (unset on the
bridge container, so the compiled default applies). Enforcement has two stages: a cheap
pre-check estimates tokens as `round(chars/4)` and skips truncation entirely at or below
12 500 tokens — **50 000 characters is the guaranteed-safe zone** — while above it the real
tokenizer runs and a result over 25 000 tokens is cut to `25 000 × 4` = **100 000
characters**, the hard ceiling, with `[OUTPUT TRUNCATED - exceeded 25000 token limit]`
appended.

The fix combines two defenses: **a hard candidate cap** and a two-step, index-then-detail flow. The candidate-filtering pre-screen ranks all qualifying earnings observations by EPS-surprise strength and **caps at `dracul.strigoi.echo.max-candidates` (default 33 since 2026-08-04, previously 40)** before resolving any prices — a structural ceiling against that limit. Recalibrated 2026-08-04 against a measured production payload: 29 candidates = 45 106 chars, `recentNews` 47.5 % of it. Cutting the news index 5 → 3 items dropped a candidate to ~1 365 chars, which is what allows 33 to fit under the 50 000-char guaranteed-safe zone with a 4 194-char reserve for `active_patterns` growth; at 5 items the cap would have had to fall to 28, below a real earnings day. The surplus is dropped deterministically (strongest first) and reported via `data_source_health.truncated = true`.

**Price-source outage now aborts the screen instead of running quiet (2026-08-16).** The
price-resolution step of the pre-screen (`EchoPeadScreener.screen`, after the EPS filter and
candidate cap, before enrichment) used to catch every `MarketDataException` and `continue` —
so a dead price source silently dropped every shortlisted candidate one by one and left a
`priceSourceUnavailable=false`, `truncated=false` empty result that looked exactly like a
quiet night. It now stops the loop and reports the outage — `ScreenResult.priceSourceUnavailable`,
folded into `data_source_health` by `StrigoiEchoWebhookController.mergeHealth` — on either of two
triggers, sharing the same `EnrichmentSourceGuard` two-evidence rule used elsewhere in this
document: immediately on a `Scope.SOURCE` failure (Agora produced no answer at all), or after
**three consecutive** `Scope.REQUEST` failures with no success in between (Agora answering with a
per-request error envelope for every symbol, the actual shape of the 2026-08-06 incident). A
single `Scope.REQUEST` failure — one unresolvable symbol — still just skips that one candidate
(`continue`) and does not trip the guard.

The two-step flow provides a second layer:

1. `fetch_recent_pead_candidates` now returns `recentNews` as a lean, summary-less
   **index** per candidate — newest-first, each item `{headline, source, credibility,
   datetime}` — capped at `dracul.strigoi.echo.recent-news-cap` (default 3, env
   `ECHO_RECENT_NEWS_CAP`; see `documentation/configuration.md`). Each candidate also
   carries `newsCount`, the true uncapped headline count, so the LLM can tell when it is
   only seeing a slice.
2. For a candidate it is seriously considering — or whose headlines are ambiguous, or
   whose `newsCount` is much larger than the index it received — the LLM calls the new
   `fetch_candidate_news` tool (`POST /api/strigoi-echo/tools/fetch-news`, see
   `documentation/api.md`) with `{symbol, since}`. That call returns the news for that one
   symbol, each item including `summary`, capped at 40 items newest-first — a safety bound
   against the same bridge tool-result limit, not a curation step. The prompt explicitly
   scopes this to the shortlist, not every candidate, to respect the run's 25-turn budget.

The deterministic confounder gate (SP3, above) is **unaffected by this cap**. It runs
during `fetch_recent_pead_candidates` enrichment, over the same single, uncapped
per-candidate news fetch that also feeds `recentNews`. Both the confounder scan and the
capped `recentNews` index are derived from that one uncapped fetch inside
`EchoEnrichmentService.safeNewsScan(...)` (`gate.evaluate(...)` itself runs afterwards, over
the scan's confounder result). What matters is not that ordering but the source: the
confounder scan always reads the full, uncapped news list — the cap only shapes the separate
`recentNews` index handed to the LLM, it never reduces what the gate sees. A hard-drop for a
disqualifying news event (M&A, restatement, guidance cut, dilution, investigation) behaves
exactly as before; only the amount of news text handed to the LLM changed.
`fetch_candidate_news` is a separate, on-demand detail fetch for the shortlist and plays no
role in the gate.

`AgoraCompanyData` gained a health-aware `newsResult(symbol, from, to)` variant for the
detail tool: an Agora outage now surfaces as `data_source_health.status: "unavailable"`
instead of masquerading as "this symbol has no news", which is what the pre-existing
`news(...)` method (still used internally for the index + confounder scan) does on
failure.

**Prompt correction, 1.7.1 → 1.7.2 (2026-07-29).** Echo is the only hunter with two tools,
and its old "Empty results are valid" clause was unscoped: "If the tool returns no
candidates — or its `data_source_health.status` is `unavailable` — return exactly
`{"prey": []}`". Read literally, that sentence also fired when `fetch_candidate_news` — the
per-symbol **detail** tool above, not the screener — answered `unavailable`. A single
candidate's news lookup failing (e.g. an Agora hiccup on that one symbol) was enough to
instruct the model to discard every candidate in the run, even ones the screening tool
(`fetch_recent_pead_candidates`) had already returned successfully. The fix is two sentences
in `prompts/strigoi-echo.md`:

1. The empty-result clause is now scoped by name: "If the **screening tool
   `fetch_recent_pead_candidates`** returns no candidates — or its
   `data_source_health.status` is `unavailable` — return exactly `{"prey": []}`."
2. A new instruction next to `fetch_candidate_news`'s description: if that tool answers
   `unavailable` for a symbol, judge that one candidate from `recentNews` alone — do not
   retry it, and do not drop the other candidates.

Covered by `StrigoiPromptContractTest#echoScopesTheEmptyClauseToTheScreeningTool`,
`#echoTellsTheAgentWhatToDoWhenTheDetailToolFails`, and
`#theOtherFiveStillScopeTheClauseToTheScreeningTool` (the last one pins that the other five
hunters keep the "screening tool" wording, so the fix can't quietly regress). Version bump
`1.7.1` → `1.7.2`, new `body_hash` in `prompt_registry.json`, and the usual
`agent_definition` reset (see `documentation/vistierie-integration.md`, "Agent budgets and
definition updates").

### Strigoi-Spin: lifecycle persistence

**Full lifecycle persistence (added 2026-07-12).** A spin-off's key evidence —
relative size, trading status, valuation, post-spin insider buying — only exists
*after* the Distribution Date, weeks or months after the early Form-10-12B
registration. A stateless single-shot hunter could only ever judge filing
metadata. Strigoi-spin therefore persists every registration to the `spin_candidate`
table (V26, see `architecture.md`) and tracks it across hunts through a
forward-only state machine, enriching each row with stage-appropriate data as the
spin-off matures.

The same webhook cron runs a **four-phase hunt** (`StrigoiSpinWebhookController.hunt`,
no new scheduler):

1. **INGEST** — `AgoraFilings.searchSpinoffs` (10-12B, default 60-day lookback) →
   `SpinoffScreener` (CIK-first dedup, collapsing amendments) → `upsertRegistered`
   writes each spin-co as a `REGISTERED` row. Idempotent: `INSERT … ON CONFLICT DO
   UPDATE` on the natural key `COALESCE(cik, lower(company_name))`, so a re-run never
   duplicates a spin-co nor resets its lifecycle — but it CAN backfill a `symbol` that
   was missing (`SET symbol = COALESCE(spin_candidate.symbol, EXCLUDED.symbol)`; a
   known symbol is never overwritten, `company_name`/`filing_url` are never touched on
   conflict since `company_name` is itself part of the conflict key for a CIK-less
   row). Added 2026-08-08: Agora's EFTS reader failed to resolve the ticker on some
   10-12B filings before its 2026-08-04 fix, leaving nine rows stuck `symbol IS NULL`
   forever under plain `DO NOTHING`; re-ingesting the same filing now lands the ticker.
   The spin-co's registrant CIK is parsed from the EDGAR filing URL
   (`CikExtractor.fromFilingUrl`) and preserved.
2. **RECONCILE** — `SpinLifecycleReconciler` recomputes the desired state from the
   persisted non-terminal rows and applies forward-only transitions via guarded
   compare-and-set. Two phases: calendar transitions (pure SQL/Java, **zero** Agora
   calls) and **one** batched `AgoraMarketData.quotes()` probe across every
   symbol-bearing pre-distribution row.
3. **ENRICH** — `SpinCandidateEnricher` fetches stage-appropriate data for a bounded
   work-set (rows that transitioned this run first, then non-terminal rows
   oldest-checked, deduped and capped at **25/run** to hold the webhook latency
   budget) and persists it as per-stage JSONB snapshots. A row past the cap keeps
   answering RESPOND from its last-persisted snapshot (e.g. a stale `daysSinceDistribution`)
   until its next enrich turn — self-correcting, and conservative in the direction that
   matters (a stale count only ever reads too LARGE, never too fresh).
4. **RESPOND** — the LLM payload (`EnrichedSpinCandidate` rows) is rebuilt from the
   persisted columns + snapshots of the **active, unpromoted** window {`REGISTERED`,
   `WHEN_ISSUED`, `DISTRIBUTED`}, newest-discovered first — not read straight from
   the live search — **and restricted to the hunt's requested window** (`filing_date`
   OR `distribution_date` OR `distributed_at` on/after `today − lookback_days`;
   `SpinCandidateRepository.findActiveUnpromotedInWindow`). Before 2026-08-04 this
   read the whole active table, so `lookback_days` reached the EDGAR ingest search
   but never the answer: a 14-day and a 90-day request returned the identical rows.
   The filing date is the primary clock because `lookback_days` is already an EDGAR
   filing-date window on the ingest side; the distribution date is ORed in because a
   spin-off's tradeable event lands weeks or months after the 10-12B and a
   freshly-distributed spin-co must not drop out because its registration is old.
   Added 2026-08-08: `distributed_at` (the reconciler's own DISTRIBUTED-transition
   timestamp) joins the same OR, because `distribution_date` is parsed from the term
   sheet prose and is frequently absent — checked against all nine of the then-stuck
   production rows, none of whose term sheets mentioned a "record date" or
   "distribution date" — so a row with an old `filing_date` and a null
   `distribution_date` used to transition to DISTRIBUTED and then never reach the LLM
   again, no matter how fresh the transition.
   `discovered_at` is deliberately not the filter (it records when Dracul first saw
   the row, not a market fact) — it stays the ordering. A row carrying neither date
   is always returned rather than silently dropped. The response is capped at 50
   rows; a full page is reported conservatively as
   `data_source_health.truncated = true`. The ingest search's data-source health
   rides the response, merged with that cap.

**State transitions and their triggers** (all guarded CAS, `WHERE status = <from>`;
never reversed):

| Transition | Trigger | Agora cost |
|---|---|---|
| _new_ → `REGISTERED` | an unseen 10-12B natural key ingested | — (rides the ingest search) |
| `REGISTERED` → `WHEN_ISSUED` | calendar: `record_date` reached and distribution not yet (`record_date` ≤ today, and `distribution_date` null or today < it) | 0 |
| `REGISTERED`/`WHEN_ISSUED` → `DISTRIBUTED` | the batched `quotes()` probe returns a positive price for the symbol (stamps `distributed_at`) — a formality when `distribution_date` is known and past, the **primary** distribution signal when it is unknown | 1 shared batched call |
| `REGISTERED`/`WHEN_ISSUED` → `ABANDONED` | sat non-distributed past `abandon-after-days` (default 180) since `discovered_at`; terminal, kept for audit | 0 |
| `DISTRIBUTED` → `SETTLED` | first XBRL `Assets` datapoint whose `periodEnd` **and** `filed` both fall strictly after the effective distribution date (the term-sheet `distribution_date`, else the `distributed_at` detection date) — the spin-co's first standalone report | 1 `conceptStrict` probe (in the enrich phase) |

The `SETTLED` transition is detected in the enrich phase (not the reconciler): a
`DISTRIBUTED` row issues **one** dedicated `conceptStrict(cik, "Assets")` probe, and
the (terminal) SETTLED compare-and-set is committed **only after** the valuation
snapshot is secured — so a transient valuation-fetch failure leaves the row
`DISTRIBUTED` (retried next run) instead of burning SETTLED with an empty,
never-revisited snapshot.

**Stage-appropriate enrichment** (each field nullable and fail-soft; snapshots stored
as JSONB):

- **`REGISTERED` / `WHEN_ISSUED` / `DISTRIBUTED`** — term capture (`captureTerms`)
  runs first for every row in the queue while both dates are still null,
  throttled to once per 7 days via `terms_checked_at` (V44) so a row that keeps
  failing to yield a date does not get re-fetched every hunt. It fetches the
  EX-99.1 Information Statement and runs `SpinTermsParser` → `distribution_ratio`
  and a best-effort `parent_symbol` only (see above — dates are no longer parsed
  here); `record_date`/`distribution_date` are populated later, if at all, by the
  agent's evidence-verified reading of the same `term_sheet_text` (see the
  `strigoi-spin` row in "Implementation status" above). Then, for the
  pre-distribution stages, the **balance sheet by CIK**
  (`SpinBalanceSheetSnapshotter`):
  `totalAssets`, `totalLiabilities`, `retainedEarnings` (XBRL concepts anchored to a
  single balance-sheet instant, no cross-date mixing) plus Finnhub `industry` when a
  ticker exists. No market-cap ratios — there is no market capitalisation before the
  distribution.
- **`DISTRIBUTED`** — the size / forced-selling read (`SpinDistributionSnapshotter`):
  `spincoMarketCapMillions` / `parentMarketCapMillions` (Finnhub via
  `EquityMetricsExtractor`, parent keyed on the best-effort `parent_symbol`),
  `sizeRatio` (spinco ÷ parent market cap, the small-spin-off effect; null unless both
  caps resolve), `daysSinceDistribution`, `distributionDateConfirmed`, and
  `postSpinInsiderBuying` (any Form-4 open-market purchase — code `P` — on or after the
  distribution date, from one `ownerHistoryStrict` call). `daysSinceDistribution` is
  measured off `SpinLifecycleReconciler.promotionAnchorDate` — deliberately **not**
  `effectiveDistributionDate`, which stays the separate settlement threshold (see
  below). `promotionAnchorDate` prefers, in order: the term-sheet `distributionDate`,
  then the term-sheet `recordDate`, then the `distributed_at` detection timestamp.
  Each candidate also carries `anchorSource` (`DISTRIBUTION_DATE` | `RECORD_DATE` |
  `DETECTED`, `SpinLifecycleReconciler.anchorSourceFor`) naming which of those three
  actually supplied the date. `distributionDateConfirmed` (`true`/`false`) is now
  defined as `anchorSource != DETECTED` — **true for a record-date anchor as well as
  a distribution-date anchor**, not only the latter. This still stops the LLM from
  reading a small `daysSinceDistribution` off a bare detection timestamp as "the
  window just opened" when it is really only "Dracul just noticed" (the original
  2026-08-08 problem this flag was added for), but a record-date anchor opens the
  promotion window a few days earlier than the true distribution date — the prompt
  says so explicitly when it explains `anchorSource`.
  **Closed 2026-08-09:** the deterministic promotion gate
  (`StrigoiSpinWebhookController.withinPromotionWindow`, `SPIN_PROMOTION_WINDOW_DAYS`,
  default 90 days) requires `distributionDateConfirmed = true` in addition to the
  existing `spincoMarketCapMillions` and window checks — a missing/`false` flag fails
  the gate regardless of how small `daysSinceDistribution` reads. Since dates now come
  from the agent's evidence-verified reading of the EX-99.1 Information Statement
  rather than a regex that provably bound the wrong date on real filings (see
  "Implementation status" above), a confirmed anchor is expected to become the normal
  case going forward, not the near-permanent gate-block it was while
  `SpinTermsParser` still owned date extraction. A row that clears every other
  condition (cap resolved, inside the window) and is held back only by
  `distributionDateConfirmed = false` logs a `strigoi-spin candidate {id} ({symbol})
  would promote (...) but distributionDateConfirmed=false — deliberately held back,
  not an error` INFO line, so a run in which this is the reason nothing promotes does
  not read like a quiet night in the daily analysis. Rows failing on cap or window do
  not produce this line — only the confirmed-only case does, to keep it a signal
  rather than noise.
- **`SETTLED`** — the fundamental re-rating read (`SpinValuationSnapshotter`):
  `priceToBook` (Finnhub `pbAnnual`), `fcfYield` (reciprocal of Finnhub
  `pfcfShareTTM`), `bookValue` (XBRL Assets − Liabilities), and `evToEbit`. **`evToEbit`
  is a coarse, upward-biased book enterprise-value proxy** — `EV = marketCap +
  totalLiabilities − cash`, divided by the latest annual XBRL `OperatingIncomeLoss` —
  and uses total liabilities in place of pure interest-bearing debt (a known upward
  bias, left in deliberately because isolating debt cleanly from XBRL is unreliable);
  the prompt treats it as a rough screen, not a precise multiple.

**Promotion (candidate → prey)** rides the one shared-base-class hook,
`HuntController.afterPersist(inserted, body)` — a no-op for the other five hunters,
overridden by strigoi-spin. For every newly-persisted prey it matches the symbol back
to a `DISTRIBUTED`, unpromoted row (`findPromotableBySymbol`) and stamps it promoted
(`markPromoted`, guarded on `promoted_at IS NULL`), so the candidate leaves the active
window and can never be re-emitted. This is **idempotency marking, not the emit
decision** — the LLM already decided what to emit from the RESPOND payload; the hook
only closes the double-emission loop. Gate (deliberately relaxed from the blueprint):
`status = DISTRIBUTED` and `promoted_at IS NULL` (both enforced by the SQL lookup), a
non-null `spincoMarketCapMillions`, `distributionDateConfirmed = true` (since
2026-08-09 — see above; means `anchorSource != DETECTED`, so a confirmed
record-date-only anchor also satisfies this gate), and
`daysSinceDistribution ≤ promotion-window-days` (default 90). **`sizeRatio` is NOT a
hard condition** — parent/sizeRatio are often
unresolvable and gating on them would silence the hunter, so `sizeRatio` is a
confidence booster in the prompt instead. Exactly-once is layered: the delivery-level
filter in `complete()` (only newly-inserted prey reach the hook), the row-level
`promoted_at IS NULL` CAS, and the prey same-day natural-key unique index (V21) as the
final backstop. A prey matching no promotable row is skipped fail-soft — it is already
persisted regardless.

**Horizon.** The prompt (`1.3.0`) splits the thesis into a ~3-month
forced-selling/index-drop compression window and a 6-12-month fundamental re-rating;
the controller's default horizon is `6m`.

## Strigoi-Merger: term-sheet digest and the derived cap

**The hunter was blind and looked healthy (2026-08-04).** Agora caps
`get_filing_text` at 24 000 characters per filing, and Dracul shipped that
verbatim as the candidate field `termSheet`. One production run
(`74754073068449F3BA047A2DC32CB22F`, 05:00) produced a **329 818-character** tool
payload for 25 candidates; four further runs measured 305 587–353 968
characters. All five finished `status=done` with a final `{"prey": []}` — the
model received a candidate list chopped mid-JSON at the bridge's 100 000-character
hard ceiling (see "Strigoi-Echo: news index/detail split" above for the limit and
how it is enforced) and had nothing usable to reason over.

**The payload is now bounded twice: a digest per candidate, and a derived
candidate cap.**

*The digest.* `termSheet` is gone; the field is `termSheetDigest`, produced by
`TermSheetDigest.of(text, budgetChars)`. It keeps the sections that price deal
risk, spent from the top of this priority order until the budget runs out:

1. closing conditions, 2. regulatory approvals (antitrust / HSR / CFIUS),
3. termination fees, 4. no-solicitation / go-shop, 5. financing,
6. shareholder vote.

Each slice starts **at** the heading (an unlabelled fragment is worse than none),
runs at most 240 characters and is trimmed to a word boundary; the total never
exceeds `dracul.strigoi.merger.term-sheet-digest-chars` (default 700). When no
cue matches — a filing whose summary is written unconventionally — it falls back
to a head excerpt of the same length rather than returning nothing.
`termSheetAvailable` is unchanged.

*Why a digest and not a truncation.* The head of a summary term sheet is its
least useful part: page references, "The Parties to the Merger (page 19)", and
paragraphs on where each entity is incorporated. A head excerpt would spend the
whole budget before reaching a fact that moves a closing probability.

*Why so little is enough.* Everything quantitative the prompt used to ask the
model to mine out of that prose is already extracted server-side by
`DealTermsParser` and rides the payload as its own field — offer price,
consideration type, exchange ratio, break fee, agreement/expected-close/outside
dates, plus the spread, annualized spread, unaffected price and break downside
computed from them. What parsing cannot deliver is the *qualitative* closing
risk, which is exactly what the cues select.

*The cap.* `dracul.strigoi.merger.max-candidates` is **30**, derived rather than
chosen: a 50 000-character budget (the bridge's guaranteed-safe zone) minus 5 000
reserved for the envelope leaves 45 000 for candidates; per candidate 645
characters of structured fields (measured worst case over 200 production records;
the average is 623) + 20 characters of key overhead + a 700-character digest,
× 1.05 for JSON escaping ≈ 1 400; `45 000 / 1 400 = 32.1` → 30. Worst case
`30 × 1 400 + 5 000 = 47 000` characters — 6 % under the safe zone and 2.13× under
the hard ceiling. It is deliberately not 25 (provably binding: a 45-day and a
90-day window both returned exactly 25 rows) and cannot be 40 (40 × 645 = 25 800
characters of structured fields before a single character of deal text).
`MergerPayloadBudgetTest` holds the derivation and fails the build if the cap and
the digest budget drift apart; `TermSheetDigestTest` covers the section selection
and the fallback.

The prompt stays at version `1.4.0` with a changed body (registry hash updated):
it describes `termSheetDigest` instead of the raw term sheet.

## Strigoi-Index: announcement-anchored lifecycle

**Announcement-anchored lifecycle (added 2026-07-12).** The index-inclusion edge
lives in the window between a change being *announced* and its *effective* date —
index-tracking funds must trade the name in the effective-day closing auction
regardless of price. The old hunter anchored on the Wikipedia `Date added` column,
which is the effective date, so a name only surfaced *after* the forced-buying
window had already closed. Strigoi-index now ingests **announced** constituent
changes (each carrying both an announcement date and an effective date), persists
every change to the `index_event` table (V27, see `architecture.md`) and tracks it
across hunts through a forward-only state machine, flipping the judgement to the
still-open forward window.

The same webhook cron runs a **four-phase hunt** (`StrigoiIndexWebhookController.hunt`,
no new scheduler):

1. **INGEST** — `AgoraReference.indexChanges` (`get_index_constituent_changes`) is
   called **once per tracked index** (`sp500`, `russell1000`, `russell2000` — the
   Agora tool is single-index), and each announced change is upserted as an
   `ANNOUNCED` row. Idempotent: `INSERT … ON CONFLICT DO NOTHING` on the natural key
   `(index_name, upper(symbol), action, effective_date)`, so a re-run never
   duplicates a change nor resets its lifecycle. A change **missing its effective
   date or its announcement date is dropped visibly** (WARN + index/symbol/action) —
   both back NOT NULL columns, and a change with no announcement is useless for the
   ANNOUNCED-window anchor. The `sp500` fetch's data-source health rides the RESPOND
   envelope (parity with spin surfacing its single ingest search's health).

   **Issuer name (2026-08-04).** Agora now carries `companyName` on each change — read
   off the S&P press-release prose (`Ferguson Enterprises Inc. (NYSE: FERG) will replace
   Electronic Arts Inc. (NASD: EA)`) and off the FTSE Russell reconstitution list, which
   prints the name next to the ticker. It is **best effort and explicitly nullable**: a
   release whose prose does not yield a name gives `null`, never a guess. For rows that
   arrive nameless, INGEST falls back to the index membership list
   (`AgoraIndexConstituents.constituents`, at most one call per index per hunt, only when
   something is actually missing, fully fail-soft). That list is a snapshot of who is a
   member **right now**, which decides the two directions:
   - **`remove`** — still a member until the effective date, so the name resolves
     (verified on prod 2026-08-04: `get_index_constituents("sp500")` carried
     `EA → "Electronic Arts"`).
   - **`add`** — not a member yet, so the list cannot name it; only the change feed can
     (verified on the same call: no `FERG` row at all).

   Whatever is left stays `null` all the way to the LLM — the prompt orders it copied
   through verbatim and forbids inventing a name, and `prey-list-index.json` types
   `companyName` as `["string","null"]` while keeping it **required**, so "unknown" and
   "omitted" stay distinguishable. Because ingestion is `ON CONFLICT DO NOTHING`, a row
   first seen without a name would otherwise stay nameless forever; a second run that
   does know the name writes it via `IndexEventRepository.fillMissingCompanyName`
   (guarded by `company_name IS NULL` — a stored name is never overwritten).

   > **Why this matters.** Prod run `4ED119E68E1D48FEB3D23B3F652641D1` (2026-08-04)
   > failed with `output_schema: /prey/0/companyName: null found, string expected`, and
   > a schema violation is terminal — Vistierie discards the entire run output. The
   > schema demanded a field the data could not supply. Same class as the executor
   > `side` defect: prompt and schema must state the same contract.
2. **RECONCILE** — `IndexLifecycleReconciler` recomputes the desired state from the
   persisted non-terminal rows and applies forward-only transitions via guarded
   compare-and-set. It is **pure calendar with ZERO Agora calls** — the effective
   date is already authoritative on every row, so unlike the spin reconciler there
   is no quote probe. At most one transition per row per pass.
3. **ENRICH** — `IndexEventEnricher` fetches stage-appropriate data for a bounded
   work-set (rows that transitioned this run first, then non-terminal rows
   oldest-checked, capped at **25/run**) and persists it as per-stage JSONB
   snapshots.
4. **RESPOND** — the LLM payload (`EnrichedIndexEvent` rows) is rebuilt from the
   persisted columns + snapshots of the **active, unpromoted** window {`ANNOUNCED`,
   `EFFECTIVE`, `POST`}, not read straight from a live constituents list. (The old
   `AgoraReference.constituents()` / `get_index_constituents` route is gone.)

**State transitions and their triggers** (all guarded CAS, `WHERE status = <from>`;
never reversed; pure calendar):

| Transition | Trigger | Agora cost |
|---|---|---|
| _new_ → `ANNOUNCED` | an unseen constituent-change natural key ingested | — (rides the ingest fetch) |
| `ANNOUNCED` → `EFFECTIVE` | calendar: `today >= effective_date` (stamps `effective_at`) | 0 |
| `EFFECTIVE` → `POST` | unconditional on the next pass (EFFECTIVE is a transient tick) | 0 |
| `POST` → `CLOSED` | calendar: `today >= effective_date + observation-window-days` (default 30); terminal | 0 |
| `ANNOUNCED` → `ABANDONED` | safety-valve: announcement older than `abandon-after-days` (default 45) while `effective_date` is still in the future = source/data anomaly; terminal, kept for audit | 0 |

There are only **two** JSONB snapshots (`announced_snapshot` / `post_snapshot`) —
`EFFECTIVE` is a transient calendar tick, so its drift read is stored under the
`post_snapshot` column. Reversal-vs-continuation is a boolean `reversalObserved` in
the post snapshot, not a fifth status (mirrors spin's forward-only discipline).

**Stage-appropriate enrichment** (each field nullable and fail-soft; snapshots stored
as JSONB):

- **`ANNOUNCED`** — the forced-demand / liquidity read (`IndexDemandSnapshotter` →
  `announced_snapshot`): `adv` / `avgVolume20d` (20-day average daily dollar/share
  volume, carried over verbatim from the deleted `IndexEnrichmentService`),
  `marketCap` (Finnhub, USD millions), `idiosyncraticVol` (sample stddev of the last
  ~`idio-vol-lookback-days` daily residual returns vs the market proxy — SPY by
  default — reusing echo's shared `MarketSignalService.residualReturns` machinery),
  `freeFloatProxyMillions` (**a deliberately coarse proxy**: total shares outstanding
  × price, *not* true free float), `demandToAdvRatioEstimate` (**derived entirely
  from coarse per-index config constants**: passive AUM × free-float weight ÷ ADV),
  and `confounders[]` (reusing echo's `ConfounderScreen` over company news since the
  announcement), plus `confoundersUnknown` — true when the confounder screen's news
  source did not answer for this symbol. Unlike echo's confounder gate (see "Confounder
  screen" above), this snapshot calls the health-aware `ConfounderScreen.confounders(String,
  LocalDate)` overload, so a news outage lands here as "unknown" instead of silently inside
  an empty `confounders` list — a bare `[]` is only a positive "scanned, nothing matched"
  statement when `confoundersUnknown` is false. A snapshot written before this flag existed
  reads back `confoundersUnknown = true` (unknown), never `false` — see
  `IndexEventEnricher.confoundersUnknown` for the reader. The single strict source is the
  Agora price feed; an availability outage propagates so the enricher can short-circuit the
  source for the rest of the batch.
- **`EFFECTIVE` / `POST`** — the run-up / reversal read (`IndexDriftSnapshotter` →
  `post_snapshot`): `runUpPct` (announcement bar → effective bar), `postEffectivePct`
  (effective bar → latest), `reversalObserved` (run-up and post-effective moves have
  opposite signs past a ~1% noise floor — the classic Petajisto give-back), and
  `daysSinceEffective`.

**Promotion (event → prey)** rides the one shared-base-class hook,
`HuntController.afterPersist(inserted, body)` — a no-op for four hunters, overridden
by strigoi-spin and (2026-07-12) strigoi-index. For every newly-persisted prey it
matches the symbol back to an `ANNOUNCED`, unpromoted row
(`findPromotableBySymbol`) and stamps it promoted (`markPromoted`, guarded on
`promoted_at IS NULL`). **The logic-flip is enforced structurally.** The hard gate is
a pure calendar fact: `status = ANNOUNCED` and `promoted_at IS NULL` (both enforced
by the SQL lookup — EFFECTIVE/POST/CLOSED rows are never returned and can never
promote), `effective_date` strictly in the future, and `daysToEffective <=
promotion-window-days` chosen **per source** (`sp_press` uses the tight S&P window,
default 5; `russell_reconstitution` the wider Russell window, default 20). The
demand/liquidity numbers (`idiosyncraticVol`, `demandToAdvRatioEstimate`, …) are
**NOT** part of the gate — they are noisy proxies/estimates acting as prompt-side
confidence boosters only, matching the spin lesson that `sizeRatio` is a booster,
not a gate. A prey matching no promotable row is skipped fail-soft.

**Prompt (`2.0.0`).** A full rewrite around the logic-flip: judge the today →
`effectiveDate` window (no `dateAdded` field anymore), emit only from `ANNOUNCED`
rows, treat every demand field as a coarse proxy to be judged qualitatively (never
quoted as precise), dampen on an adjacent `reversalObserved` (front-running warning),
and set a source-aware horizon (S&P `1m`, Russell `3m`). The tool was renamed
`fetch_recent_index_additions` → `fetch_index_reconstitution_events` (the
`@PostMapping` path is unchanged).

**Honest limits.** The Russell R1000/R2000 split is genuinely coarse: the free LSEG
reconstitution PDFs carry only **Russell 3000** additions/deletions, and the
per-name R1000-vs-R2000 bucket is resolved against the iShares IWB/IWM holdings CSVs
— which are **bot-walled from server IPs** (they answer with an HTML product page,
not CSV). With iShares unresolvable the bucket **defaults to `russell2000`**, so in
practice `russell1000` degrades to empty while `russell2000` carries every Russell
change (a documented, safe skew, all Agora-side). The `demandToAdvRatioEstimate` /
`freeFloatProxyMillions` / `passiveAumTrackingBillions` fields are coarse
proxies/constants, **not** precise figures, and the prompt is instructed never to
cite them as such.

## Strigoi-Tech: conviction basket

`strigoi-tech` (disabled by default, `dracul.strigoi.tech.enabled`) builds and guards a basket
of large technology and "future" companies held for the long run. Its edge is breadth and
staying invested, not entry timing: every name is bought once at a fixed size and exited only
by code (exit profile CONVICTION, see "Executor" below). The agent runs on the reasoning tier,
22:30 UTC Mon–Fri (after the US close, before the executor), with up to 40 turns / 1800 s, and
has three tools: `fetch_tech_book`, `check_tech_candidate` and the shared `search`.

### Tools

Neither tool is cached — the book is tonight's state and the check is per symbol.

- **`fetch_tech_book`** (`POST /api/strigoi-tech/tools/fetch-book`, no input) returns
  `book.open_positions` (open CONVICTION positions on the executor connection, each with
  `symbol`, `entry_price`, `qty`, `highest_close`, `current_close`, `pl_pct`, `active_stop`,
  `half_sold`, `days_held`, `catastrophe_flagged`, `news_available` and `news_since_last_run` —
  up to 5 headlines of the last 3 calendar days, which covers the weekend gap of a Mon–Fri
  schedule),
  `book.pending_signals` (PENDING executor signals with mechanism `TECH_CONVICTION`),
  `basket_size`, `slots_free`, `new_names_allowed_this_week`, `accepted_this_week`,
  `recently_exited`, `executor_available` and `last_completion_notes`. The current closes come
  from one `get_quote` call, the news from a strict `get_company_news` read per position.
  Neither failure ever makes the book `unavailable` (every hunter prompt answers that with
  `{"prey": []}`, and the two halves are independent: a quote outage says nothing about the
  news the catastrophe check reads). Instead `data_source_health` stays `healthy` with
  `partial: true` (source `agora`) and a `detail` naming what is missing —
  `no current price for k of n open position(s): …` and/or `news unavailable for k of n open
  position(s) — not judgeable for a catastrophe tonight: …`. A position whose news read
  failed carries `news_available: false` (empty headlines that mean "could not be read", not
  "no news"); the prompt does not flag such a position that night.
- **`check_tech_candidate`** (`POST /api/strigoi-tech/tools/check-candidate`,
  `{"symbol": "<TICKER>"}`) returns `candidate.profile` (name, provider industry, market cap
  in millions, currency, exchange, `listing_ticker`, `type`), `quote` (price, currency),
  `technicals` (`current_close`, `atr` (22), `ma50`, `ma200`, `high_52w`, `low_52w` from
  `get_indicators`), `fundamentals` (the lazarus `BasicFinancials` summary), `analyst_estimates`
  (the newest recommendation trend), the last 10 `news` headlines (14 days) and the code
  verdict `eligible` / `reasons` (blocking) / `notes` (informational). A section whose source
  failed is null. `data_source_health` is `unavailable` only when the profile AND the quote both
  failed with an Agora outage (SOURCE scope) — an error about one unknown symbol is not an
  outage. The prompt answers an `unavailable` health from either tool with `"prey": []` but
  still returns catastrophe exits already backed by headlines it received.

### Eligibility (code)

Code rejects only what is clearly out of scope (`TechEligibility`); everything else is the
LLM's judgement:

- **Equity only** — the instrument type comes from Agora `search_instruments` (exact-symbol
  hit, `type` = the provider's quote type, upper-cased); anything but `EQUITY` is
  `not_equity:<TYPE>`. No hit is `data_unavailable:instrument_type`. `search_instruments`
  ignores queries shorter than 2 characters, so a **1-letter ticker is always
  `data_unavailable:instrument_type`** and cannot be picked (accepted limitation).
- **USD quote** — `quote.currency` must equal `dracul.executor.instrument-currency`
  (`quote_currency:<CCY>`; missing → `data_unavailable:quote_currency`).
- **Not held, not pending, not recently exited** — `already_held` (depot holdings on
  `dracul.position.connection` plus every OPEN executor position on the executor connection),
  `already_pending` (any PENDING executor signal), `recently_exited` (a CONVICTION position of
  that symbol CLOSED within `reentry-block-days`, default 90).
- **Market cap ≥ `min-market-cap-usd-millions`** (default 20 000) — enforced only for a
  CONFIRMED listing (`profile.ticker` equals the symbol, the discriminator
  `LazarusListingResolver` uses). For a foreign primary listing (an ADR) the profile reports
  the home market's cap in its own currency, so the check becomes the non-blocking note
  `market_cap_unverified` and tradability is left to the executor's LIQUIDITY veto. A blank
  profile is `data_unavailable:profile`, a missing cap `data_unavailable:market_cap` — partial
  data never passes silently.
- **No sector filter** — provider industry labels file large tech platforms under "Media" or
  "Retail"; what counts as technology is the LLM's call.

### Completion

`POST /api/strigoi-tech/complete` runs, in this order: (1) the status check (base
`HuntController`), (2) **catastrophe exits**, unconditionally — also on `prey: []` and on a
duplicate re-delivery whose prey are all already persisted, (3) re-validation of every pick
with the same rules as `check_tech_candidate` (duplicates in one output and ineligible picks
are dropped), (4) the cap, applied in the LLM's order to the eligible picks only, (5) persist
the prey (`anomalyType=TECH_CONVICTION`, `discoveredBy=strigoi-tech`, horizon `12m`) and emit
executor signals. The cap is

    capacity = max(0, min(basket-size − open CONVICTION − pending tech signals,
                          max-new-per-week − ACCEPTED tech signals this ISO week − pending tech signals))

The ISO week starts Monday 00:00 UTC; "ACCEPTED this week" counts tech signals whose
`processed_at` falls in it. `kill_criteria` stay free text (context only); this hunter has no
`kill_close_below`.

### Catastrophe exits

`catastrophe_exits[]` (`symbol`, `reason`, `evidence[]`) flag a thesis-destroying event: fraud
or a restatement, loss of a key market by regulation or an export ban, a guidance collapse that
breaks the business model, a delisting or a fixed-price takeover. Price weakness alone is never
a catastrophe. Code accepts a flag only for an **OPEN CONVICTION position on the executor
connection** (`dracul.executor.connection`) — a STANDARD, CLOSED, unknown or foreign-connection
row is rejected (`catastrophe_rejected`). The flag writes
`executor_position.catastrophe_reason` (reason + `[evidence: …]`, at most 1000 characters) and
`catastrophe_flagged_at` **once**: a flag is final (an operator clears it in SQL). The next
executor maintenance pass flattens the position with `HARD_CATASTROPHE`. A flag on a CONVICTION
entry that has **not filled yet** is not acted on by the hard trigger; the entry's GTD expiry
(`dracul.executor.entry-gtd-days`, 2 days) cancels it — if it fills inside that window the
position exits at the next pass after the fill. With the executor disabled, catastrophe exits
are dropped (`executor_disabled`) and eligibility checks depot holdings only
(`executor_available: false` in the book).

### Health notes

Each completion counts `picks_over_cap`, `ineligible_pick`, `catastrophe_rejected` and
`executor_disabled`. A completion has no `data_source_health` channel, so the counts go to
one WARN line (`strigoi-tech completion notes: run=… picks_over_cap=… ineligible_pick=…
catastrophe_rejected=… executor_disabled=…`, only when any count is non-zero) and into the next
`fetch_tech_book` as `book.last_completion_notes` (in memory — reset by a restart).

### Exit profile

The executor derives exit profile CONVICTION from the mechanism `TECH_CONVICTION`: fixed size
`position-pct` × `dracul.executor.total-budget`, an emergency stop 35 % below entry, half sold
once a daily close reaches +30 %, the rest trailed 30 % below the highest close, no LLM soft
exits and no tranche 2. See "Executor" below and `documentation/configuration.md`
(`dracul.executor.profiles.conviction.*`).

**Broker leg: narrow at entry, widened after the fill.** The broker rejects a bracket leg
beyond its proximity band at entry, so the protective leg starts at the entry band
(`entry-broker-stop-pct`, −20 %) and the row is flagged `broker_stop_narrow`. The first
maintenance pass after the fill moves that leg out to the logical −35 % stop (see
`StopRatchetService` under "Executor" below). Until then — the first trading session after an
entry placed at 23:00 UTC — an intraday fall of 20 % fills the narrow leg before the half-sale
or the close-based −35 % stop can act. If the broker refuses the widening, the position keeps
the −20 % leg as its effective emergency stop (`BROKER_STOP_WIDEN_REJECTED`, see below).

## Hunt Pattern

Every Strigoi follows the same three-step shape:

1. **Pre-screen** (deterministic, no LLM) — pulls candidates from the
   appropriate hunting-ground adapter (EDGAR, prices, news, calendar)
   and filters to the ones worth spending tokens on. Every fetch-tool payload
   carries a `data_source_health` object (see `hunting-grounds.md`, "Data-source
   health"); it can additionally carry `partial: true` and/or `truncated: true`
   when Agora reports a degraded-but-usable fetch (e.g. a market-wide window
   that hit its row cap). `status` stays `healthy` in that case — the data is
   usable, just demonstrably incomplete — and both fields are omitted entirely
   when not set, never emitted as `false`.

2. **LLM evaluation** via Vistierie — a Sonnet-tier (`reasoning`) or
   Haiku-tier (`routine`) call. The fetch-tool response includes `active_patterns`
   — the statements of every `ACTIVE` pattern scoped to this Strigoi (plus any
   scoped `'all'`), so approved user lessons weigh directly on this hunt (see
   "Learning loop" below). Returns structured `Prey` JSON, including:
   - `kill_criteria` (1–5 strings, required): falsifiable exit conditions — a measurable
     threshold, a concrete date, or a single unambiguous public event under which the
     thesis is dead. They flow through the Prey→ExecutorSignal adapter; the executor
     hard-rejects (`SCHEMA_INVALID`) any entry signal without them. Vague concerns belong
     in `risks`.
   - `kill_close_below` (number, optional — strigoi-echo and strigoi-lazarus only, V51): the one
     price level whose single daily close below it kills the thesis, and the only kill condition
     the executor enforces in code. Echo uses `preReportClose`, lazarus the 52-week low that
     defined the setup; the key is omitted when that level is not strictly below the current
     price. The schemas accept number/string/null so a malformed value never fails the run;
     `PreyMapper` keeps only a strictly positive number and WARNs on anything else present.

3. **Persist** — the parsed `Prey` records are written to `dracul.prey`.
   Vistierie handles cost accounting and run history; Dracul handles
   domain persistence.

   When the executor is enabled (`dracul.executor.enabled=true`), the same
   `/complete` request also auto-feeds the executor: `PreySignalEmitter` maps
   each persisted prey to a pending `executor_signal` (skipping symbols already
   open or already pending). This is a read-only-to-execution handoff — the
   hunters still only produce prey; the code-guarded executor is the sole agent
   that acts on the resulting signals. See `hunting-grounds.md`
   ("Prey → ExecutorSignal flow"). With the executor disabled, hunts complete
   exactly as before.

### Reference implementation

> **Note:** the sketch below illustrates the *generic* three-step hunt shape.
> Strigoi-spin itself no longer follows this single-shot form — since 2026-07-12
> it runs the four-phase lifecycle hunt over the persisted `spin_candidate` table
> (see "Strigoi-Spin: lifecycle persistence" above), extending `HuntController` and
> fetching via the `AgoraFilings` facade rather than a direct `EdgarClient`.

```java
@Component
public class StrigoiSpin implements Bee<HuntRequest, List<Prey>> {

    private final EdgarClient edgar;
    private final SpinoffScreener screener;
    private final PatternLibrary patterns;   // active Voievod lessons

    @Override
    public BeeId id() { return BeeId.of("strigoi-spin"); }

    @Override
    public AgentTier preferredTier() { return AgentTier.REASONING; }

    @Override
    public List<Prey> hunt(HuntRequest input, BeeContext ctx) {
        // Step 1: deterministic pre-screen
        var candidates = edgar.findRecentForm10Filings(input.lookback());
        var qualified  = screener.filter(candidates);
        if (qualified.isEmpty()) return List.of();

        // Step 2: LLM with pattern context
        var activePatterns = patterns.activePatternsFor(this.id());
        var response = ctx.llm().complete(
            buildEvaluationPrompt(qualified, activePatterns));

        // Step 3: parse and return
        return PreyParser.parse(response, qualified);
    }
}
```

## Learning loop (accepted patterns feed back into hunts)

When the user approves a proposed pattern (`PATCH /api/patterns/{id}` with
`action: "approve"`), `PatternController` sets its status to `ACTIVE` and
assigns it a slug `name`. From that point on, every hunter's fetch-tool
response — the payload returned from e.g. `/api/strigoi-spin/tools/fetch-candidates`
— carries an `active_patterns` array: the `statement` text of every `ACTIVE`
pattern where `applies_to_strigoi` equals that hunter's agent name or `'all'`
(`PatternRepository.findAcceptedByStrigoi`, wired into `HuntController#handleFetch`
via a field-injected `ObjectProvider<PatternRepository>`, mirroring the existing
`PreySignalEmitter` pattern — if the bean is ever absent, the key is simply
omitted rather than failing the hunt). Voievod's own fetch tool
(`VoievodWebhookController`, which does not extend `HuntController`) includes the
same key, but scoped to `PatternRepository.findAllAccepted()` — every `ACTIVE`
pattern regardless of `applies_to_strigoi` — since Voievod judges consensus
clusters spanning multiple hunters rather than a single anomaly type.

Each of the 6 hunter prompts and the Voievod prompt (bumped to `1.1.0`, see
`prompts/prompt_registry.json` and the archived `1.0.0` bodies under
`prompts/archive/<agent>/`) instructs the agent to weigh candidates against
`active_patterns` as user-confirmed lessons from past hunts.

**Kill-criteria example honesty (prompts `1.2.0`, 2026-07-12):** the
spin/insider/index payloads carry no price data, yet their prompts' good-example
lists showed price-level kill criteria ("close below X — state the level") the
model could only fabricate. Those examples were replaced with date/event-based
ones provable from the actual payload (spin: `distributionDate` deadline;
insider: C-suite cluster-buyer departure; index: `dateAdded`-derived drift-window
expiry). Lazarus was bumped in the same round to document
`cfoExceedsNetIncomeAvailable` and to stop reading a wire-level
`cfoExceedsNetIncome=false` as a quality warning.

**Lazarus global (EU/Asia) hunting (2026-07-14, additive).** Lazarus now screens
non-US watchlist names — XETRA (`.DE`), Tokyo (`.T`) and Hong Kong (`.HK`) blue-chips
seeded in `V32__seed_global_watchlist.sql` — alongside the existing US universe; the
US path is unchanged. For non-US symbols the Altman-Z solvency inputs are sourced from
Agora's `get_fundamental_concepts` (Yahoo-backed fundamentals) instead of SEC XBRL
`get_company_concept`, since foreign issuers do not file XBRL company-facts with the SEC;
the prompt vocabulary is region-neutral (no "SEC"/"XBRL"/provider names). The executor
applies a **currency veto**: a signal whose watchlist-row currency does not match its
venue's expected trading currency is dropped, guarding against acting on a mis-converted
non-US candidate. Config: `dracul.strigoi.lazarus.probe-symbol` (health probe, default
`AAPL`) — see `documentation/configuration.md`. **`dracul.fundamentals.non-us-suffixes`
(the former non-US venue whitelist mentioned here) has no reader as of 2026-08-13**:
`InstrumentClassifier`, the class that consumed it, was deleted when the Altman-Z route
moved to `ListingResolution` (see "Listing resolution replaces symbol-shape guessing"
below) — routing is now decided per candidate from `reportingCurrency`/a resolved
company profile, not from the ticker's suffix.

**Lazarus market-wide universe + honest health (2026-08-04).** The screened
universe is no longer `watchlist_items`. It is now `LAZARUS_UNIVERSE_SOURCE`
(default `sp500`, fetched via Agora `get_index_constituents` and its
`AgoraIndexConstituents` facade) **plus** every watchlist entry, minus what
depot-1 already holds. Watchlist names bypass the pre-filter and are always
screened — a name the user tracks by hand is the stronger signal.

*Why:* `StrigoiLazarusWebhookController` built an empty universe, skipped the
health probe and returned `DataSourceResult.healthy("agora", [])`. **Correction
(2026-08-04): the watchlist table itself was never empty** — the count that
looked like proof (`watchlist_items` for user `default` → 0) was scoped to the
wrong owner. The controller hard-coded `USER = "default"`, while
`LegacyWatchlistOwnerMigration` rewrites every `user_id = 'default'` row to
`dracul.primary-user-email` on each boot, so the rows exist and none of them
carry `'default'`. Lazarus now takes
`@Value("${dracul.primary-user-email:}")` and falls back to `"default"` only
when it is blank — the same convention Renfield, gropar, daywalker and stopguard
already used; lazarus was simply never migrated with them. The same bug made the
documented `LAZARUS_UNIVERSE_SOURCE=watchlist` fallback a fallback to nothing.
Every run was a guaranteed no-op reporting
`data_source_health {"status":"healthy"}` — a
quiet market that never existed (run `D91C16769F1B4C30879530B4B0A07A6A`,
Dracul log `strigoi-lazarus fetch: items=0 (partial=false truncated=false
status=healthy)`, with Agora logging 145 successful OHLC provider calls in the
same window — the probes were being served, the universe was empty by
construction).

*Two stages, because the cheap and the expensive data come from different
providers.* `get_fundamentals` routes US symbols to Finnhub, throttled to 60
calls/minute across all of Agora — one call per S&P 500 member would spend
eight-plus minutes inside that throttle, collect 429s, and silently drop most of
the universe, i.e. reproduce the bug with a bigger number. So
`LazarusUniverseService` first narrows the index on a cheap
`52w_range` probe per symbol — served by Agora's OHLC provider
chain (Alpaca first for US symbols, Yahoo only as last resort), a different and
far less throttled source than the fundamentals path (`AgoraPriceRange`, returns both the
52-week low and the last completed close — since SP8 the same bar vintage as
the 52-week window itself, so the ratio stays well-defined while a session
runs), keeping everything within
`LAZARUS_PRE_FILTER_MARGIN` (default 0.25 — deliberately wider than the 0.10
screen, since the two lows come from different definitions); only the survivors,
capped by `LAZARUS_FUNDAMENTALS_MAX` (default 60) and ranked by `pctAboveLow`
ascending, cost a fundamentals call. **Expected Agora calls per run:** 1 index
(Wikipedia-sourced, cached 24 h inside Agora) +
`ceil(universe / LAZARUS_PROBE_CHUNK_SIZE)` pre-filter (≈ 5 at the default chunk
size; it was ~503 before 2026-08-06, see "Batched pre-filter" below) + ≤ 60
fundamentals + the unchanged enrichment (≤ 25 candidates). The value the fetch
tool publishes as `webhook_timeout_seconds` was raised from 30 s to
`LAZARUS_FETCH_TIMEOUT_SECONDS` (default 600) to describe that — but **no tool
timeout is applied at all** (verified 2026-08-04): Vistierie declares
`webhook_timeout_seconds` and never uses it, and the calling RestClient has an
infinite read timeout. Treat the number as documentation of intent, not a
ceiling. **Agent definitions are `insertIfAbsent` on prod, so a change here
needs the agent-definition reset.**

*Honest health.* Every way a symbol can be lost is now counted and folded into
`data_source_health` through the shared `DataSourceHealth.degradedWith` helper:
`partial` for data we tried to read and could not (an unusable pre-filter
answer, a missing fundamentals blob, a 52-week range whose SOURCE failed,
enrichment drops, an unavailable index falling back to the watchlist),
`truncated` for universe we
deliberately did not read (`LAZARUS_UNIVERSE_MAX`, the fundamentals budget, a
spent `LAZARUS_PRE_FILTER_BUDGET_MS`). Status stays `healthy` throughout so the
candidates we did find survive the prompt's "if unavailable, return exactly
`{"prey": []}`" clause. **The one thing it can no longer be is healthy with an
empty universe:** an unfetchable or empty universe is `unavailable`.

**Not everything lost is a degradation (2026-08-06).** An index member younger
than 52 weeks has no 52-week range to compare against and never will until it
ages. Those symbols are counted separately (`notEligible` in the per-run log
line) and deliberately do **not** set `partial`: a flag raised by a permanent
property of an instrument fires on every run and stops carrying information.
Measured on 2026-08-05, three S&P 500 members (all listed within the year) made
every single lazarus run report `partial=true` while all 490 screened symbols
were in fact read successfully. They also do not count towards
`LAZARUS_MAX_CONSECUTIVE_DEAD_CHUNKS` — index constituents are walked in list
order, so adjacent new listings could otherwise have aborted the pass and
declared a healthy source down.

**The same split at the fundamentals stage (2026-08-07, BUG-S29).** A candidate
whose fundamentals carry no 52-week low used to be counted as `no52wLow` and
reported as `partial` whatever the reason — so an outage of Agora's OHLC chain
was recorded as a fact about the company. Agora now emits a group-scoped marker
inside the metrics blob when, and only when, the source failed:
`"52WeekRange": {"available": false, "error": "..."}`; an instrument-scoped
absence deliberately carries none. Dracul reads it and counts the two apart:
`no52wLowSourceFailed` in the per-run log line is a degradation and sets
`partial` ("N symbols dropped: 52-week range source unavailable"), while
`no52wLow` — the instrument genuinely has no such value — stays in the log line
only, exactly as `notEligible` does. Either way the symbol is dropped, so
neither can produce a false candidate. Until the Agora-side change is deployed
the marker is never present and every such loss lands, as before, on `no52wLow`
(which no longer sets `partial`).

A run of `LAZARUS_MAX_CONSECUTIVE_DEAD_CHUNKS` pre-filter chunk calls that
resolved **nothing at all** stops the pass rather than burning dead calls, and
the next run enters the universe where this one stopped (in-memory rotation), so
a permanently tight budget still covers the whole index eventually. Screen
thresholds are unchanged — they were never the bug.

**A price below the candidate's own 52-week low is a unit error, not a
candidate (2026-08-09).** `LazarusScreener` used to check only the upper bound
(`pctAboveLow > maxAboveLow`), so a name whose price and 52-week-low figures
are quoted in different units could pass as "at its low" while actually
sitting near its yearly *high*. Measured on prod Agora, 2026-08-09: BRK.B
returned `price 520.96` (B-share units) against `52WeekLow 693021` (A-share
units), `pctAboveLow = -0.9992`. `screen(...)` now returns a `ScreenResult`
(`candidates` + `implausibleRange`) instead of a bare list and drops any row
with `pctAboveLow < 0` (`== 0`, price exactly at the low, still passes).
Same treatment as `notEligible`/`no52wLow`: a data error about the instrument,
not a source outage, so it counts in the per-run log line
(`implausibleRange=…`) but never sets `partial`.

**Mega-cap exemption from the cheapness gate (2026-08-09; superseded 2026-08-13).**
Originally: `LazarusScreener.screen` accepted a `megaCapUsdMillions` threshold and
added a third OR-branch, `megaCap`, evaluated only when the reporting currency was
`null`/`"USD"` — a non-USD mega-cap (e.g. 0941.HK, ~226 Bn USD reported in CNY) was
deliberately not exempted, since the screener is pure/I/O-free and had no FX access.
**`LazarusScreener.screen` no longer knows about the threshold at all.** It now only
computes `cheapGatePassed` (whether the candidate cleared P/B or P/FCF on its own —
the solvency gate, leverage cap and unit-plausibility guard above still run first and
unchanged) and forwards the raw `marketCap()`/`reportingCurrency()`, tagging every
candidate `ListingResolution.UNKNOWN` (it cannot resolve a listing itself). The size
decision moved to `StrigoiLazarusWebhookController`, downstream of listing resolution
— see "Listing resolution replaces symbol-shape guessing" immediately below for the
full mechanism and why the reporting-currency-only reading is what caused the original
mismatched-instrument bug this whole change fixes.

**Listing resolution replaces symbol-shape guessing (2026-08-13).** Finnhub's
fundamentals describe a security's **primary listing** — its own currency and share
basis — while Dracul always holds the price of the requested symbol. For a US-listed
ADR or a company's second share class, "requested symbol" and "listing the numbers
describe" diverge, and nothing on the wire said so until this change. Evidence
gathered on prod, 2026-08-13: `TEVA` (an ADR) carries its market cap in ILS; `UL` and
`VOD` (ADRs) carry it in GBP; `BRK.B` inherits A-share-scale figures from `BRK.A`;
`FOX`/`NWS` resolve to `FOXA`/`NWSA`; `CNI` reports in CAD, which without conversion
manufactured a fabricated "sitting at its 52-week low" reading. **`profile.currency`
is explicitly NOT the discriminator** — it is the *reporting* currency of the
financial statements, not proof the figures describe the requested listing; the only
legitimate signal is whether `reportingCurrency` (Finnhub's per-metric market-cap
currency field) is present at all, currency-agnostically (`"USD"` counts exactly like
`"EUR"` — 0005.HK reports its market cap in USD and is still a foreign listing).

Mechanism, in the order it runs inside `StrigoiLazarusWebhookController.hunt`:

1. `LazarusScreener.screen` no longer decides gate-vs-no-gate; it only sets
   `cheapGatePassed` (self-explanatory: cleared P/B or P/FCF unaided) and always
   `ListingResolution.UNKNOWN` on every candidate it emits — it is pure/I/O-free and
   genuinely cannot know the listing.
2. `LazarusListingResolver.resolve` walks the survivors once: `reportingCurrency() !=
   null && !isBlank()` → `FOREIGN_SUFFIXED` immediately, no remote call. Otherwise it
   calls Agora's `get_company_profile` (capped at
   `dracul.strigoi.lazarus.profile-max`, default 40, env `LAZARUS_PROFILE_MAX`, behind
   the same `EnrichmentSourceGuard` every other lazarus enrichment source uses): a
   returned `ticker` equal to the requested symbol (case-insensitive) → `US_CONFIRMED`;
   a present-but-different ticker means the fundamentals describe a different listing
   outright, and the candidate is **dropped**, counted as `foreignListing` (log-only,
   never `partial` — a permanent instrument property, not a lookup failure); a
   missing/blank ticker, a missing profile, the cap being hit, or the guard already
   tripped all leave the candidate `UNKNOWN` (fail-closed — absence of evidence is not
   evidence of a US listing), counted as `listingUnknown` (**does** set `partial` — a
   lookup failure, distinct from `foreignListing`, the two never sharing a counter).
3. `StrigoiLazarusWebhookController` converts the market cap to USD exactly once per
   survivor, gated on `ListingResolution`: `FOREIGN_SUFFIXED` converts via
   `FxService`, but ONLY when `FxService.hasRate` confirms a cached rate first —
   `FxService.convert` alone is never trusted, because on a cache miss it silently
   returns the amount unconverted, which would misread e.g. ILS millions as USD
   millions; `US_CONFIRMED` passes the raw figure through (defensively re-checked for
   a non-USD currency, though the resolver's own invariant means that branch cannot
   currently fire); `UNKNOWN` stays unavailable — no size can be trusted for an
   unresolved listing. The size-exemption decision then reads exactly one figure: keep
   the candidate when `cheapGatePassed` **or** the USD-normalised size clears
   `dracul.strigoi.lazarus.mega-cap-usd-millions` (`0` disables the exemption; a bare
   `>=` against `0` would otherwise silently enable it for everyone).
4. `AltmanZCalculator.zScore` takes the resolved `ListingResolution` as a parameter
   instead of guessing the route from the symbol's shape: `US_CONFIRMED` → the SEC
   XBRL `get_company_facts` path (`reportingCurrency` ignored); `FOREIGN_SUFFIXED` →
   the currency-aware `get_fundamental_concepts` (Yahoo-backed) path, with an X4
   currency-consistency guard between the market cap and the concept liabilities;
   `UNKNOWN` → unavailable without any remote call, since the correct route cannot be
   determined. **`InstrumentClassifier` (the former suffix-list class) is deleted** —
   see the "Lazarus global (EU/Asia) hunting" paragraph above for the config property
   this obsoletes.

`marketCapAvailable=false` therefore now has three distinct causes on the wire (prompt
`1.8.0`, 2026-08-13): the raw market cap was absent; it was reported in a currency
`FxService` has no rate for; or the candidate's listing could not be resolved
(`ListingResolution.UNKNOWN`) — the LLM prompt names the third explicitly. All three
still mean "unknown", never "small" — the prompt's existing instruction not to invent
a market-cap value is unaffected.

**Batched pre-filter (2026-08-06).** The pre-filter no longer spends one
`get_indicators` call per index member. It walks the universe in chunks of
`LAZARUS_PROBE_CHUNK_SIZE` (default 100) and asks Agora's `get_indicators_batch`
once per chunk — ~5 calls per run instead of ~490. *Why:* the per-symbol walk
tore through Alpaca's per-minute quota. Measured in the run window on 2026-08-05,
49 of 645 Alpaca calls answered `429`, TwelveData (8 credits/minute) tipped over
right behind it and Yahoo carried the remainder; nothing was lost, but ~90 calls
per run were wasted and the run depended on the last-resort provider holding.

Nothing about the accounting changed with it, deliberately:

- Every requested symbol gets a verdict. `AgoraPriceRange.range52wBatch` returns
  a `RangeProbe` for **every** symbol it was given. A symbol Agora's batch answer
  does not carry at all is `UNUSABLE` — a degradation — never `NOT_ELIGIBLE`: a
  gap in the answer must not be able to pose as a young listing.
- A chunk that loses symbols says so before the number disappears into a counter:
  `WARN lazarus pre-filter: 52w-range chunk answered for <n> of <m> symbols — the
  missing <k> count as degradations`, and for a dead call
  `WARN … 52w-range chunk of <m> symbols starting at <SYM> failed (…) — counting
  all <m> as degradations`. A batch path that silently returns fewer symbols than
  it asked for reads downstream exactly like a quiet market; this is the line that
  makes it visible.
- The source-down heuristic counts **chunks**, not symbols (fixed 2026-08-06,
  see below), and the pass stops at a chunk boundary.
- `LAZARUS_PRE_FILTER_BUDGET_MS` is checked after each chunk (the call is
  indivisible), so the pass can overshoot the budget by at most one chunk's
  duration — one Agora request, bounded by `DRACUL_AGORA_TIMEOUT_MS` (25 s)
  against a 240 s budget. The rotating entry point and the wrap-around are
  unchanged.

Agora rejects a batch over its own 600-symbol cap rather than truncating it;
Dracul clamps the configured chunk size into `[1, 600]` so it can never produce
one.

**Source-down now counts dead chunks, not failed symbols (2026-08-06).** The
heuristic that declares Agora down was written for the per-symbol walk, where
failures arrived scattered among successes. Batching changed the shape of a
loss: one transient upstream page error discards a whole block of adjacent
symbols. Measured the same day: an upstream page error inside a 90-symbol chunk
discarded 37 partially read symbols, those 37 adjacent failures blew through the
old threshold of 10 symbols, and the run ended `screened=410 … unscreened=80
sourceDown=true` on a 490-symbol universe whose other chunks answered fine.

The rule now:

- The unit is the **chunk call**. A chunk that resolved at least one usable range
  **clears** the run — a source that answers is answering, however few of that
  chunk's symbols it could serve. A chunk that resolved none while failing at
  least one **increments** it. A chunk of nothing but too-young symbols does
  neither.
- There is no per-symbol component left. Symbols lost inside an otherwise
  healthy chunk are still counted and still raise `partial`; they are simply not
  evidence about the source.
- The threshold is `LAZARUS_MAX_CONSECUTIVE_DEAD_CHUNKS` (default 2). The old
  `LAZARUS_MAX_CONSECUTIVE_FAILURES` was **removed**, not reinterpreted: its
  value meant symbols, and reading an operator's `10` as 10 chunks would put the
  threshold beyond a 490-symbol universe's reach. An env var of the old name now
  has no effect.
- Cost of being wrong the other way: against a wholly dead source the pass spends
  at most 2 of the ~5 chunk calls a 490-symbol universe costs at the default
  chunk size — at most 2 x `DRACUL_AGORA_TIMEOUT_MS` (25 s) = 50 s.
  `LAZARUS_PRE_FILTER_BUDGET_MS` (240 s) remains the backstop and bounds even the
  case where the heuristic never trips: all ~5 chunks against a timing-out source
  cost ~125 s and still fit inside it.

**Cache-expiry caveat:** `handleFetch` responses are served through
`ToolFetchCache` (per-tool TTL). A pattern approved or rejected after a tool's
cache entry was populated only becomes visible in `active_patterns` once that
cache entry expires — acceptable for v1; there is no cache-invalidation hook
on pattern-status changes.

## Prior research memory (`search` tool)

Eight agents — the six hunters plus `gropar` and `voievod` — carry a second tool,
`search`, an mcp passthrough to HiveMem's research realm `dracul-research`. Dracul
files every thesis and outcome cell under **the ticker as the cell `topic`**
(`HiveMemResearchService`), so a per-symbol lookup is
`{"where": {"realm": "dracul-research", "topic": "<TICKER>"}, "limit": 5}`.

The supported `where` keys are exactly `realm`, `topic`, `tags`, `signal` and
`status`; HiveMem rejects anything else with `Unknown where field`, and it rejects
`where.query` for `search` specifically. There is **no `symbol` key** —
`MemorySearchCatalogContributor` now declares the full typed `where` schema
(`additionalProperties: false`) and the shared MEMORY-RUBRIC block in all eight
prompts requires `where.topic` alongside `where.realm`.

Why this is spelled out: while `where` was advertised as a bare untyped object, the
model invented `where.symbol`. That is *not* an error path — a filter without
`topic` browses the newest cells of the whole realm, so consecutive lookups for
different tickers returned the identical unrelated cells and every agent was reading
other companies' theses as its own symbol's history (observed in production 2026-08,
`strigoi-index`). Prompts bumped: `strigoi-echo` 1.8.0, `strigoi-index` 2.2.0,
`strigoi-insider` 1.6.0, `strigoi-lazarus` 1.5.0, `strigoi-merger` 1.4.0,
`strigoi-spin` 1.5.0, `gropar` 1.2.0, `voievod` 1.3.0. (`strigoi-lazarus` moved on
to 1.6.0 with the market-wide-universe change of 2026-08-04; `strigoi-spin` moved on
to 1.6.0 with the `distributionDateConfirmed` fix of 2026-08-08, see below.)

`daywalker` and `renfield` do not carry the tool: their memory context is pre-fetched
server-side by `HiveMemResearchService.searchForInput`, which has always filtered on
`topic`.

## Adding a new agent

Adding a new agent to Dracul requires code changes and one config entry. Registration with Vistierie is now fully DB-driven — no hardcoded registrar list.

### Code

1. **Webhook controller** — if the agent produces `Prey`, extend `HuntController`; otherwise write a bespoke `@RestController`. Secure it with a bearer token matched against the agent's token property.
2. **Adapter / screener** — deterministic pre-screen logic (no LLM). Lives in the appropriate `dracul-hunting-grounds` module or a new sub-module.
3. **Output domain + persistence** — only needed when the agent emits a new domain object (not `Prey`). Add a Flyway migration and update `documentation/architecture.md`.
4. **Prompt + schema** — add `src/main/resources/prompts/<agent-name>.md` and, if the agent returns structured JSON, a schema file `<agent-name>-schema.json` alongside it.
5. **Tool catalog** — if the agent needs tool callbacks, implement `AgentToolCatalog.catalogEntries()` contributions (or add entries via a new `AgentDefaultProvider.catalogEntries()` override).
6. **`AgentDefaultProvider` bean** — implement the interface in the agent's package, annotate with `@Component` (and `@ConditionalOnProperty` if the agent is opt-in). Return an `AgentDefinition` from `defaultDefinition()` with the correct name, prompt path, schedule, model purpose, enabled state, and tool bindings. This is the only registration step required.

### How registration works

On startup `AgentDefinitionBootstrap` iterates all `AgentDefaultProvider` beans and upserts each definition into the `agent_definition` table (insert-if-absent, so manual edits made via the REST API survive redeployment). `GenericAgentRegistrar` then reads all definitions from the DB, prepends `dracul.public-url` to the webhook callback URLs, appends the current language directive to the system prompt, and calls Vistierie's create-or-update agent endpoint. The registrar re-runs on `AgentDefinitionChangedEvent` and `LanguageChangedEvent`, so runtime edits take effect immediately without a restart.

`SettingsController.strigoiNames()` derives the budget-panel roster directly from the `AgentDefaultProvider` beans filtered to names starting with `strigoi-`; no hardcoded list is maintained anywhere.

### Config

Add one `@ConditionalOnProperty` guard (e.g. `dracul.strigoi.<name>.enabled`) and one webhook-token property (e.g. `dracul.strigoi.<name>.webhook-token`). Document both in `documentation/configuration.md`. No new `dracul.agents.*` namespace — each agent owns its own property prefix.

## Manual hunt trigger

Every Strigoi can be run on demand, in addition to its cron schedule, from two
places in the Chronicle frontend — both call `POST /api/strigoi/{name}/run`
(see `documentation/api.md`), which proxies to Vistierie's `POST
/agents/{name}/run` and returns `202 {"runId": "..."}`.

- **Strigoi Detail** (`/strigoi/:name`): a primary button in the page header
  (`data-testid="sd-trigger-hunt"`) labelled "Jagd starten" / "running…" while
  in flight. Disabled while a trigger is already in flight or while the agent
  is paused (tooltip explains why). On success it refetches the Strigoi detail
  so the new run/stats appear without a full reload; on failure it shows the
  error via the toast system.
- **Settings → Agent config**: each row in the agent list has a
  "Run"/"Ausführen" button (`data-testid="agent-run-{name}"`), disabled while
  paused or while a run for that row is already in flight. On success it shows
  a success toast and reloads the agent list; on failure it shows the error
  message as a toast.

Both surfaces surface the same backend error mapping: 404 unknown Strigoi, 409
`AGENT_PAUSED`, 422 `BUDGET_EXCEEDED` — rendered as the raw error message in
the toast rather than a bespoke per-code UI.

## Groparul (exit-timing agent)

**Implemented 2026-06-14; repointed to the live depot 2026-07-13.** Dracul's exit-timing
agent. Groparul ("the gravedigger") monitors open **depot-1** positions daily and advises
when to exit — SELL, TRIM, or HOLD. It is advisory only: it never executes trades.

Groparul runs once per day after the US close (default cron: `0 0 22 * * 1-5`, UTC).
On each run:

1. `POST /api/gropar/tools/fetch-held-positions` — tool webhook pulls every open position
   from the live depot (`depot-1`) joined by symbol to its `position_context` row
   (`HeldPositionService.openPositions`, `de.visterion.dracul.position`) — the depot, not the
   watchlist, is the single source of truth for what's held. Each position carries an opaque
   `positionId` (the symbol) the LLM echoes back so signals can be matched to a still-open
   position at `/complete` time. When the position has an open context row, its `thesis`
   block is built directly from that row's stored `thesisSnapshot`/`killCriteria` (captured
   once, at the point the position was opened/backfilled — see `PositionReconciler` /
   the executor's entry-fill write) rather than re-resolved from the verdict live. A
   position with **no** open context row (e.g. opened by the executor before a matching
   verdict was linked) degrades to **TA-only**: indicators are still computed, but `thesis`
   is `null` — it is never dropped from the feed.
   Positions the executor holds with exit profile CONVICTION are labelled
   `managed by exit profile CONVICTION` (`thesis.exitProfile`), carry no fired rules or profit
   targets, and no exit signal is persisted for them (lookup by connection + symbol through the
   optional executor repository).
2. `GroparExitIndicators` assembles the exit-indicator bundle for each position. The technical
   indicators are sourced from Agora's bundled `get_indicators` MCP tool (one call per position)
   via the `AgoraResearch` facade — Dracul no longer computes them locally:
   - **ATR Chandelier Stop** — 22-period ATR × 3.0 multiple (Chandelier Exit)
   - **MA Cross** — 50-period vs 200-period simple moving average
   - **52-week proximity** — distance to 52-week low/high
   - **Gain/loss thresholds** — unrealised gain ≥ 40% or unrealised loss ≥ 15% (derived in Dracul
     from the position's entry price and Agora's current close)
   - **Time stop** — based on position age vs. the verdict horizon; the horizon itself still
     rides along in the depot-sourced context, but the verdict's creation date needed to
     evaluate "has the horizon elapsed" is not currently carried by `HeldPosition`, so
     `TIME_STOP` does not fire post-repointing (known gap, see `HeldPosition`/`position_context`)
   - **R-framework** — `RiskMetricsService` (retained, position-domain) is fed Agora's ATR to derive
     the frozen ATR initial stop, risk unit R, gain in R, MFE since entry, and a giveback
     (peak-drawdown) guard (`INITIAL_STOP` / `GIVEBACK` rules)
3. Indicator bundle → reasoning-tier LLM judgment. The LLM returns `ExitSignal` per position:
   `verdict` (SELL / TRIM / HOLD), `thesis_status` (INTACT / WEAKENING / INVALIDATED / NONE —
   `NONE` means the position has neither a thesis nor `killCriteria`, e.g. a manually-added
   position, so gropar judges it on technical indicators alone), `rationale` (German), and
   `confidence`. A position opened from an executor/Prey signal with no narrative thesis carries
   a **kill-only** thesis block (`killCriteria` present, `summary`/entry signals absent); the
   prompt treats those criteria as authoritative falsifiable exits even without a summary, so
   `thesis_status` = `NONE` is reserved for positions with neither. When
   `thesis_status` = `INVALIDATED`, the LLM must also name which condition failed via the
   optional `violated_kill_criteria` array (verbatim entries from the fetched `thesis.killCriteria`);
   the completion handler appends them to the persisted rationale as
   `" [Verletzt: <criterion>; <criterion>]"` when present and non-empty.
4. `POST /api/gropar/complete` — completion webhook persists each signal to `dracul.exit_signals`
   (V11), scoped to the owner resolved from its `position_id`; signals with an unknown id are
   skipped. `GET /api/exit-signals` then serves each user only their own signals.
5. A Telegram push fires for SELL/TRIM signals on the single operator channel, with the owner
   email prefixed into the alert text.

Groparul is the first agent built end-to-end via the **generic add-an-agent recipe**: it uses
`AgentDefaultProvider` (`GroparDefaults` bean) for DB-driven registration and a bespoke
`GroparWebhookController` (bearer-token auth, `@ConditionalOnProperty`). No custom registrar.

**Position guard.** Groparul only does useful work over held positions, so Dracul auto-pauses
it at Vistierie whenever the held-position count across **all** users is zero and unpauses it as
soon as any user holds a position — Vistierie skips a paused agent's cron, so empty runs never
fire. The guard is
driven by watchlist changes (`WatchlistController` publishes a `WatchlistChangedEvent` after every
mutation) and reconciled once at startup; see `GroparPauseReconciler`. Groparul's pause is therefore
**system-managed**: turn the agent on or off via its `dracul.gropar.enabled` flag, not the manual
pause toggle (which the guard would overwrite on the next watchlist change).

gropar also surfaces a scale-out ladder (`profitTargets` = [+2R, +4R] with
`scaleOutFractions`) and an overextension indicator (`distToMa200InAtr`) that flags a
wide distance above the MA200 as a mean-reversion „TRIM in die Stärke" hint.

> **Deploy note:** the `violated_kill_criteria` schema field and the prompt rule that
> requires naming it on `INVALIDATED` verdicts only take effect after a definition
> reset (`AgentDefaultProvider` is insert-if-absent, so a running deploy keeps the old
> DB-stored schema/prompt). `POST /api/settings/agents/gropar/definition/reset` —
> see "Local access" in `documentation/operations.md` for reaching that endpoint
> non-interactively.

## Voievod (weekly reviewer)

Not a hunter — the referee after the battle. The Voievod runs every
Sunday evening:

1. Fetches all `prey` rows whose `time_horizon` has elapsed and that
   have not yet been outcome-assessed.
2. Compares the thesis against the actual price return over the horizon.
3. Writes outcome columns (`outcome_actual_return`,
   `outcome_thesis_validated`) back into `dracul.prey`.
4. Aggregates per-Strigoi hit-rate, average return, and sub-patterns.
5. Fires a single Opus (`reasoning`) LLM call: "What do we learn?"
6. Writes proposed `Pattern` rows with status `PENDING`.

**Patterns only activate when the user approves them** in the Pattern
Library view. Approved patterns are injected into the next Strigoi run
as additional prompt context, closing the feedback loop.

### Consensus annotation (payoff families)

When the Voievod's `fetch_consensus_clusters` tool builds a cluster (symbols
flagged by ≥2 distinct Strigoi), Dracul deterministically annotates it with a
Dracul-domain payoff taxonomy — this is investment vocabulary, never Agora
market data. Each prey's `anomalyType` is classified into a `payoffFamily`:
**DRIFT** (PEAD, quality-at-52w-low, insider clusters, spin-offs — open-ended
upside, gradual repricing, ~3–12 months) or **EVENT** (merger-arb,
index-inclusion — capped payoff, cliff downside, short and event-terminated),
falling back to **UNKNOWN** for unrecognised anomaly types. At the cluster
level, Dracul also derives `crossFamily` (true when the contributing prey span
more than one payoff family — a strong warning that the underlying theses
imply incompatible price paths) and `discoverySpreadDays` (days between the
earliest and latest `discoveredAt` in the cluster, a temporal-coherence hint
for "is this the same episode?").

**This annotation is advisory only — it never drops a cluster.** Every
detected cluster is still surfaced to the Voievod's LLM call; Java only
attaches the `payoffFamily` / `crossFamily` / `payoffFamilies` /
`discoverySpreadDays` fields as extra signal. The endorse-or-drop decision
remains entirely the LLM's.

The Voievod's system prompt applies this signal through an ordered
endorse-logic, evaluated gate by gate with the first failing gate dropping
the cluster: (1) payoff/horizon compatibility — a `crossFamily` cluster is
treated as contradictory and dropped unless a genuinely rare reinforcing
reason can be named; (2) temporal coherence — a large `discoverySpreadDays`
weakens the case that the signals describe the same episode; (3) independent
mechanism — the agreeing Strigoi must reinforce each other for different
reasons, or the agreement is redundant and dropped; (4) hunter reliability —
insider and lazarus findings are structurally robust, while index-inclusion
and merger-arb are heavily arbitraged, so agreement among only arbitraged
hunters is kept but the summary language is dampened; (5) compounding risks —
if the prey's `risks[]` confirm the same downside twice, that is grounds to
drop despite bullish agreement. The prompt is **default-skeptical**: a shared
ticker across hunters is treated as coincidence (multiple-testing / FDR
concern) until a concrete independent mechanism is named, and an empty
verdict list is treated as a valid, respectable outcome.

## Voievod-Outcome (elapsed-hunt pattern reviewer)

**Implemented 2026-07-11 (fetch and completion sides both shipped).** A second,
separate agent from Voievod above — reviews **elapsed** hunts (not consensus) and
proposes generalizable patterns. Runs weekly, Saturday morning (default cron
`0 0 7 * * 6`, UTC), reasoning tier (model_purpose `reasoning`).

`POST /api/voievod-outcome/tools/fetch-elapsed-prey` (bearer-token auth via
`DRACUL_VOIEVOD_OUTCOME_TOKEN`, only registered when `DRACUL_VOIEVOD_OUTCOME_ENABLED=true`)
returns every prey whose horizon elapsed more than 30 days ago
(`!Horizons.isOpen(discoveredAt, horizon, today.minusDays(30))`) and that has not yet been
reviewed, oldest-discovered first, capped at 25 prey per run (the response notes whether the
cap was applied). Each entry carries `symbol`, `anomalyType`, `thesis`, `killCriteria`,
`discoveredAt`, `horizon`, and `ohlc` — daily close history since discovery (via
`AgoraMarketData.dailyOhlcHistory`, window sized from `discoveredAt` to today, capped at
730 days) condensed server-side to
`firstClose` / `lastClose` / `minClose` / `maxClose` (token budget — the full daily series is
never shipped). Fetched prey are marked reviewed **at fetch time**
(`prey.outcome_reviewed_at`, migration V24) — the simplest correct v1; a re-run never
re-surfaces the same prey even if the agent run itself later fails.

The LLM judges each prey against its original thesis and kill criteria using the condensed
OHLC, and proposes a pattern only when **at least 3 separate prey** support the same
statement — see `prompts/voievod-outcome.md`. `POST /api/voievod-outcome/complete`
persists the agent's proposed lessons as PENDING `patterns` rows (bearer-token auth,
CF-Access-exempt); the agent definition's `completionPath` points at it. Requires
`status: "done"` or `"succeeded"` — any other status is acknowledged (204) without
persisting. See `documentation/api.md` for the full request/response contract.

## Daywalker (streaming guardian)

**Implemented 2026-06-04** as a Vistierie `StreamingBee` consumer (Daywalker
sub-project 2 of 4). Not a scheduled agent — Vistierie opens a window-bounded
session at market open and polls Dracul's event-source webhook every 5 minutes.

1. `POST /api/daywalker/events` runs deterministic detection over the depot's live
   positions (`HeldPositionService.openPositions("depot-1")`, no LLM) — and, only when
   `dracul.daywalker.watchlist-enabled=true` (legacy; default `false`), additionally over
   the watchlist — and returns trigger events. Each trigger type is evaluated **once per
   distinct symbol** — price/volume spikes, negative news, insider sells, and analyst
   downgrades are market-wide signals, so a single market-data fetch per symbol suffices.
2. Vistierie spawns one reasoning-tier (Sonnet) child run per triggered symbol; the
   run judges severity and returns `{severity, thesis, confidence}`.
3. `POST /api/daywalker/complete` persists the assessment. Since step 1 is depot-sourced
   (2026-07-13, A6), every trigger carries a `position_id` (the symbol, echoed back by the
   LLM), so `DaywalkerCompletionService` routes straight to the single configured
   `dracul.primary-user-email` owner (same convention as gropar) rather than resolving
   owners via a watchlist lookup — one `dracul.daywalker_alerts` row is written for that
   owner if its `(owner, symbol, trigger_type)` cooldown has not yet elapsed. The
   watchlist-owner fan-out path (`findOwnersBySymbol`, "every owner of that symbol") is
   only reachable under the legacy `watchlist-enabled=true` mode, where a watchlist-only
   symbol (no depot position) can produce a trigger with no `position_id`; under the
   default depot-only scope every event is a depot position and always carries a
   `positionId`.

Under the default depot-only scope, every depot position is a real holding, so every trigger is fanned out per position and
judged against its stored context (`position_context.active_stop`, falling back to
`initial_stop`) rather than abstract percentages, carrying a deterministic
`breached_level` (STOP/TARGET — TARGET does not currently fire, see below). A level
breach defaults to CRITICAL severity; the LLM may downgrade only with a stated reason.
A position with no open `position_context` row still gets its event (never dropped),
just without a stop to breach. **Known gap:** `HeldPosition` does not yet carry
`next_target`/`atr`, so `next_target`/`atr`/`dist_to_stop_in_atr` are always null in
the event payload and only a STOP breach can be detected today.

CRITICAL alerts also fire a best-effort Telegram push (configurable via
`DRACUL_DAYWALKER_NOTIFY_LEVEL`); the push fires **once per symbol event** on the
single operator channel (not once per owner). The delivery outcome is recorded in
`daywalker_alerts.notification_sent`.

New alerts also stream live to the Chronicle frontend over SSE (`GET /api/events`,
`alert.new`), surfaced in the live-alert panel. The SSE event fires **once per
symbol event** on the global live stream, not per owner.

A per-`(owner, symbol, trigger_type)` cooldown (default 60 min) keeps a sustained
condition from generating repeat rows for the same owner on every poll.

**Emission guard (2026-07-25).** The DB cooldown above reads `daywalker_alerts`, and
that row is only written when a run completes with `status=done`. A **failed** run
therefore leaves no trace, so before this guard existed every 5-minute poll re-emitted
the same `(symbol, trigger_type)` without limit — during the 2026-07-23/24 LLM outage
daywalker sped up from ~15 to 55–76 runs/h instead of failing quietly. `DaywalkerEventEngine`
now records the emission timestamp per `(symbol, trigger_type)` in memory and suppresses
re-emission for `dracul.daywalker.attempt-cooldown` seconds (default 600), regardless of
how the run ends. Emission time is the floor, the DB cooldown the ceiling: on the success
path the longer 60-min cooldown still dominates. The map is in-memory (expired entries are
evicted each poll) and does not survive a restart; `0` or a negative value disables the
guard. `MACRO_PORTFOLIO` has its own, independent emission guard
(`DRACUL_DAYWALKER_MACRO_COOLDOWN`).

**Same-UTC-day dedup.** Within the cooldown window, if an owner already has an
alert row for `(owner, symbol, trigger_type)` on the same UTC calendar day
(`DaywalkerAlertRepository.findSameUtcDay`), no new row is inserted. Instead
the existing row is updated in place: text/timestamp/run-id/confidence/
notification-sent all refresh to the new assessment, and severity is
**escalated, never downgraded** — the effective severity is
`max(existingSeverity, newSeverity)` by the INFO < WARNING < CRITICAL rank
order (`DaywalkerCompletionService.rank`). A later, calmer re-assessment on
the same day therefore cannot silently walk a CRITICAL alert back down to
WARNING/INFO; a fresh CRITICAL escalates a same-day WARNING row in place. The
dedup is scoped to the owner's own calendar day, so different owners of the
same symbol are deduped independently.

### Trigger types (v1)

| TriggerType | Source | Deterministic condition |
|---|---|---|
| PRICE_SPIKE | Yahoo intraday (5-min) | abs(price change) > 3% over ~1h |
| VOLUME_SPIKE | Yahoo intraday (5-min) | volume > 3× rolling average |
| INSIDER_SELL | EDGAR Form-4 | a Form-4 sale ("S") for the symbol |
| NEGATIVE_NEWS | Finnhub company-news | a new material headline (LLM judges negativity) |
| ANALYST_DOWNGRADE | Finnhub recommendation-trend | rating trend shifts toward sell |

### Tier routing (v1)

A single reasoning-tier (Sonnet) assessment per event. The documented
Haiku pre-filter and Opus critical escalation are deferred; deterministic
detection plus the cooldown already gate event volume.

### Daywalker reasoning-tier escalation (`daywalker-deep`)

**Implemented 2026-07-11.** A CRITICAL Daywalker assessment with low LLM-reported
confidence gets a second, more rigorous opinion from a dedicated one-shot reasoning-tier
agent, **asynchronously** — it never delays or suppresses the original alert (a CRITICAL
alert arriving late is worse than one that is occasionally over-cautious).

Flow, inside `DaywalkerCompletionService.persistAssessment`:

1. The original assessment is persisted + notified exactly as before (this never
   changes based on the escalation outcome).
2. After that block, `maybeEscalate` checks: `dracul.daywalker.escalation-enabled`
   (default `true`) **and** `severity == CRITICAL` **and** `confidence != null`
   **and** `confidence < dracul.daywalker.escalation-confidence` (default `0.6`).
   When all hold, it calls `VistierieClient.triggerRun("daywalker-deep", {symbol,
   trigger_type, thesis, position_id?})` — a fire-and-forget trigger; any exception is
   caught and logged at WARN, never propagated (the alert flow above has already
   completed by this point regardless). `position_id` is included only for
   position-scoped assessments (nullable pass-through of `persistAssessment`'s
   `positionId` argument).
3. `daywalker-deep` (`prompts/daywalker-deep.md`, schema
   `schemas/daywalker-deep.json`) is a **trigger-only** Vistierie agent — `schedule`
   is `null`, it is never cron-scheduled, only ever run via step 2's `triggerRun`.
   It has no tools; the trigger's `payload` (`symbol`/`trigger_type`/`thesis`/
   optional `position_id`) is its entire context — it re-scrutinizes the *existing*
   thesis for rigor rather than re-fetching market data, and confirms or downgrades
   severity. The prompt instructs it to echo `position_id` back VERBATIM (or omit it
   when absent) — never to reason about it.
4. `daywalker-deep`'s completion (`POST /api/daywalker-deep/complete`,
   `DaywalkerDeepController`) parses the echoed `position_id` (null-safe) and calls
   the same `persistAssessment` with it as the `positionId` argument, plus
   `fromEscalation=true` — the loop guard: an escalation-originated assessment can
   never trigger another escalation, however low its own reported confidence.
   Threading `position_id` end-to-end matters because `persistAssessment`'s owner
   resolution branches on it: non-null → exactly the holding owner of that position;
   null → all non-held watchers. Without the round-trip, a position-scoped follow-up
   would resolve against the wrong owner set.
5. The follow-up assessment merges into the **same alert row** via the existing
   same-UTC-day dedup/escalation-severity logic (see above) — `max(existingSeverity,
   newSeverity)`, never downgraded. **v1 acceptance:** if `daywalker-deep` downgrades
   (e.g. CRITICAL → WARNING), the already-notified CRITICAL severity on the row is
   *not* walked back down; only a same-or-higher follow-up severity is reflected. The
   user sees the original CRITICAL alert with its thesis/confidence refreshed to the
   deep run's, and can judge the revised thesis themselves. **Residual caveat:** the
   `position_id` round-trip relies on the model echoing it; if the model fails to
   echo it, the follow-up falls back to the non-held-watcher owner set (`positionId
   == null`), and the same-day merge then may not reach the holder — the original
   alert row is unaffected either way.

Config: `dracul.daywalker.escalation-enabled` / `dracul.daywalker.escalation-confidence`
(env `DRACUL_DAYWALKER_ESCALATION_ENABLED` / `DRACUL_DAYWALKER_ESCALATION_CONFIDENCE`);
`dracul.daywalker-deep.enabled` / `dracul.daywalker-deep.webhook-token` gate the agent
definition + controller the same way every other agent is gated
(`@ConditionalOnProperty`). See `documentation/configuration.md` and
`documentation/vistierie-integration.md` ("Programmatic run trigger with an input
payload") for the `triggerRun(name, input)` contract this relies on.

## Executor (guarded broker-execution agent, slices 1+2)

**Implemented (slices 1+2).** Unlike the six Strigoi, the Executor is not a
hunter — it does not scan hunting grounds or emit Prey. It is a **guarded
execution agent** that now manages the full position lifecycle: it consumes
signals (advice from Strigoi, gropar, or a human operator, injected via
`POST /api/executor/signals`) and decides whether to enter, and it reviews
open positions to decide whether to exit on a soft trigger. It is
**venue-neutral**: the prompt and tool set carry no notion of paper vs
live — `dracul.executor.connection` is an operator/config choice the agent
cannot see or influence, and the code guards below apply identically
regardless of connection.

### Entries

The signal is advice, never a command. Every signal is re-evaluated
independently by code before the LLM even gets to reason about it, and the
LLM's own request to enter a position is itself re-checked before any broker
call is made:

- **`VetoService`** (pure, deterministic, no I/O) evaluates every signal
  against the full veto catalog before the agent may act on it, including
  `SCHEMA_INVALID` (missing symbol/direction/confidence), `LOW_CONFIDENCE`
  (below `dracul.executor.min-confidence`), `MAX_POSITIONS` (open-position
  count at or above `dracul.executor.max-positions`), and `MECHANISM_BUDGET`
  (the signal's mechanism already at its share of `dracul.executor.
  total-budget`).
- **Exit profile CONVICTION (exec-v1.0).** For a `TECH_CONVICTION` signal
  CORRELATED, CONCENTRATION and HEAT_LIMIT are skipped (`veto_results`
  entries carry `"skipped":"profile"`, the trace prints `SKIPPED`); BUDGET
  and MECHANISM_BUDGET charge the actual profile notional; `openHeat` and
  CONCENTRATION count STANDARD positions only, so the basket never consumes
  other strategies' heat or sector slots.
- **`OrderGuard`** (pure, deterministic) is the final check on the LLM's own
  `place_entry` request: it requires a valid protective stop on the correct
  side of the reference price, a strictly positive quantity, and that the
  order targets the configured connection.
- The risk layer is authoritative over the stop: `place_entry` clamps the
  LLM's proposed stop into the `PositionSizer`'s risk window (`stopMin`..
  `stopMax`, derived from side/price/ATR/swing-low) before sizing and
  placement — an out-of-window stop is adjusted, not rejected; the clamp is
  audited in the `ENTER` decision's `order_json` (`stop_clamped`,
  `proposed_stop`, `stop_min`, `stop_max`). `NO_STOP` remains only as
  `OrderGuard`'s defensive guard against a broken (null) server window.
- **`place-entry` runs signal → veto → guard → broker in code.** The LLM
  cannot place an order directly — it can only call the `place_entry` tool,
  which either forwards to the broker after every check passes or returns a
  structured rejection reason. See `documentation/api.md` for the full
  reason list.

Research reads (`get_quote` / `get_indicators` for ATR/swing levels) go
through the existing read-only `AgoraClient`, the same one Strigoi and
gropar use; broker writes go through Agora's webhook trading tools
(`AgoraTrading`), scoped to whichever connection/token the operator
configured.

### Exits (slice 2)

Exits are split between code, which owns everything hard and mechanical, and
the LLM, which owns only the soft judgment call. Every call to
`fetch-open-positions` first runs, server-side, in order:

1. **`ReconcileService`** — syncs broker fills against `executor_position`,
   retires positions the broker reports closed, applies `cooldown`.
2. **`HardTriggerService`** — force-closes a position on stop-breach,
   a breached structured kill level, or giveback (fraction of peak MFE-in-R
   given back, active once MFE clears `dracul.executor.giveback-active-from-r`)
   — always enforced, never the LLM's call. Precedence when more than one
   condition is simultaneously breached: stop-breach first (`HARD_STOP`),
   then the kill level (`HARD_KILL_CRITERIA`: BUY, close strictly below
   `executor_position.kill_close_below`), then giveback (`GIVEBACK_BREACH`) —
   the first match names the `decision_log` reason code and no later check
   runs. Free-text `kill_criteria` are never parsed here (since exec-v0.9);
   they stay LLM context. For exit profile CONVICTION (`exec-v1.0`) the order
   is: a flagged catastrophe first — evaluated before the missing-close skip,
   full flatten, `HARD_CATASTROPHE`, `close`/`current_r` null when there is no
   price — then the stop (emergency stop or trail), then the target-half
   (`HARD_TARGET_HALF`: no half-sale yet, no trim pending, close ≥ entry × 1.30
   → a 0.5 partial exit through `PartialExitService`, the position stays
   open). After the half-sale (`trim_count > 0`) the stop check compares the
   close to the TIGHTER of `active_stop` and the trail
   `highest_price × (1 − trail-pct)` itself — the ratchet skips a trail
   candidate the close is already below, so after a ≥ 30 % drop between two
   ratchets `active_stop` would still sit on the old level; the breach is
   `HARD_STOP` with `measured` naming the trail
   (`STOP_BREACH: close … < trail … (highest … x (1 - 0.3); active stop …)`).
   A target-half that gets no broker verdict (`BROKER_UNAVAILABLE`, which
   includes a read timeout after the order may have reached the broker) is
   NOT retried: `trim_count` is set to 1 with `qty` untouched (the next
   reconcile's `QTY_SYNC` converges it to the broker), an
   `ESCALATE`/`TARGET_HALF_UNCONFIRMED` row carries
   `inputs_snapshot.position_id`, and a CRITICAL Telegram asks the operator to
   verify at the broker and reset `trim_count` to 0 if nothing was sold. A
   catastrophe or stop flatten while a target-half order is still queued
   (orders placed at 23:00 UTC fill at the next open) may be rejected by the
   broker; it escalates and is retried by the next run — a one-day delay. Kill level and giveback are STANDARD only; CONVICTION rows carry no
   soft trigger. Each run logs one INFO line
   `kill levels evaluated: n of m filled positions (breached: k); catastrophe flagged: c, targets hit: t`.
3. **`StopRatchetService`** — ratchets the active stop up to the chandelier
   level (`dracul.executor.chandelier-mult` × ATR below the highest price
   reached), never down. The broker confirms the modify before the book is
   updated, never the other way round. A transient broker failure (rate
   limit / HTTP 429) is retried inside the same run
   (`dracul.executor.ratchet-retry-attempts`, backoff, pass-wide time
   budget); any other failure escalates immediately. For exit profile
   CONVICTION (`exec-v1.0`) the ratchet does nothing before the half-sale
   (`trim_count == 0`) — this also covers the stale pre-trim row
   `MaintenancePipeline` hands in right after a same-pass `HARD_TARGET_HALF`,
   so a second maintenance pass never acts on it either. After the half-sale
   the candidate is `highest close × (1 − dracul.executor.profiles.conviction.trail-pct)`
   (default 0.30) instead of the chandelier — no ATR needed — through the
   same monotonic guard, the same leg rows (repointed by `PartialExitService`
   after the trim), with the broker leg resting at the trail level itself
   (no ATR buffer). `decision_log.order_json.stop_basis` reads
   `"conviction trail: highestClose x (1 - 0.30)"` for these rows.
   The one exception before the half-sale is the post-fill widening of a
   narrow entry leg (`broker_stop_narrow`): on the first pass after the fill
   every open leg is moved by name (`modify_bracket`) to the logical stop
   (`active_stop`, −35 %). On success `broker_stop` is set to that level, the
   flag is cleared and a `MODIFY_STOP / BROKER_STOP_WIDENED` row is written.
   A broker rejection keeps the flag and escalates
   `ESCALATE / BROKER_STOP_WIDEN_REJECTED` once — it is never retried, and the
   −20 % leg stays the position's effective emergency stop. A call that got no
   verdict (outage, or a rejection carrying a rate-limit signature) writes
   nothing and is tried again on the next run — one modify per leg per run,
   no in-run retry. The row is re-read from the book first and widened only
   while it is still un-trimmed there (`trim_count = 0`, no pending trim):
   after a same-pass `HARD_TARGET_HALF` the in-memory row is stale and the
   legs already belong to the remainder, which is never widened. This is the
   only move of a broker leg away from the market; it does not pass the
   ratchet guard.

Only after that does the LLM see the (now current) open positions, each
carrying a `soft_trigger` block (`chandelier_breach`, `ma_break`,
`confirm_count`, `kill_criteria_breached`). Once a soft trigger has held for
`dracul.executor.soft-confirm-min` consecutive runs, the LLM is expected to
call `exit_position(symbol, reason, confidence, reasoning)`. Unlike
`place_entry`, exits carry **no veto/order-guard gate** — they are always
permitted, since closing a position is never something code needs to guard
against.

Exits also carry an optional `fraction` for scale-out: each open position
surfaces `trim_count` and `suggested_fraction`, and the prompt instructs the
LLM to exit `0.33` on the first confirmed soft trigger, then at least
`suggested_fraction` (`0.5`, then `1.0`) on subsequent ones — code enforces
the ladder floor server-side, the LLM may only exit more aggressively, never
less. See `documentation/api.md`'s "Scale-out / trim ladder" section for the
full floor table and rejection shape.

Every partial exit goes through `PartialExitService`, shared by the LLM
scale-out and the CONVICTION target-half. An accepted partial exit repoints
the `executor_position_leg` rows to the broker's restored stop ids (a leg no
restored leg replaces is CLOSED with `exit_reason = TRIM`; if the restored legs
replace none of the recorded legs the rows are left alone and
`TRIM_LEGS_UNMATCHED` is escalated); when the broker reports no fill price the
order id is stored in `executor_position.pending_trim_order_id` and the TRIM
row's `order_json.order_id`, and reconcile writes a `TRIM_FILL` row once the
fill is visible.

A pending trim (`executor_position.pending_trim_order_id`) is settled at the
top of every reconcile pass: WORKING → the broker/legs gap is tolerated (no
`LEG_QTY_DESYNC`, no `QTY_SYNC`); filled → `TRIM_FILL` row, marker cleared;
neither, and the broker holds more than the book → decided only once the
TRIM row is at least 1 h old and from an earlier run (until then the gap is
tolerated, so a fill that is not yet visible anywhere can never cause a second
sale): broker exactly at the pre-trim size (book + the TRIM row's
`qty_closed`) → `TRIM_ORDER_LOST` (CRITICAL, book restored, `trim_count` − 1);
any other size above the book → `TRIM_ORDER_PARTIAL` (CRITICAL, marker
cleared, `trim_count` unchanged, the book converges to the broker through the
ordinary `QTY_SYNC`/leg sync); neither, shares gone, older than 72 h →
`TRIM_FILL_UNRESOLVED`. CLOSED rows still carrying the marker are swept the
same way. Without a fill history in that pass nothing is decided.

A CONVICTION position (mechanism `TECH_CONVICTION`) is
code-managed: `exit_position` answers `PROFILE_MANAGED` without a broker call
and writes a `SOFT_TRIGGER/REJECT/PROFILE_MANAGED` decision row.

**MAE (adverse-excursion) tracking.** Every maintenance pass also updates
`executor_position.lowest_price` for BUY positions: the new floor is
`min(previous lowest_price (or entry price if never set), current close)`,
written only when the close is a new low. SELL positions never write
`lowest_price` — their adverse extreme is the *highest* close, already
tracked as `highest_price` by the ratchet step, so `mae_r` for a SELL
position derives from `highest_price` instead. This groundwork feeds the R
distribution / backtest work that reads `mae_r` off the closed-position
history.

`kill_criteria_breached` carries at most one entry, the structured level breach
(`KILL_LEVEL: close X < kill_close_below Y`), computed from the same rule as the
hard trigger; it is mostly visible on positions the hard trigger could not flatten
yet (unfilled, pending exit, failed flatten). `KillCriteriaEvaluator`
(`de.visterion.dracul.criteria`) is no longer used by the executor; it remains
only for `VerdictKillCriteriaWatcher`.

**Kill level at entry (`KillLevelGuard`, exec-v0.9).** place-entry evaluates the
signal's `kill_close_below` after the adoption decision. A fresh placement whose
level is at or above the order price is rejected `KILL_LEVEL_BREACHED` (terminal)
before any broker call. A level closer to the entry than 0.5 × `atr_effective`
is not armed (`kill_close_below_dropped = too_tight`, the trade is kept). An
adopted working order or filled entry is never rejected: a breached level is
dropped as `breached_at_adoption` (basis = fill price, else the order price).
The outcome is recorded in the SIGNAL row's `inputs_snapshot`
(`kill_close_below`, `kill_close_below_dropped`) and on the position.
On a SIGNAL decision row, `kill_close_below_dropped` records the guard's
outcome for that place-entry attempt and can appear on a throttle-reject,
`BROKER_ERROR` or `BROKER_RETRY_EXHAUSTED` row just as on an `ENTER` row —
none of those book a position.

Every decision point (entry, hard exit, stop-ratchet, soft exit) writes a
`decision_log` row tagged with the active `dracul.executor.rule-version`,
giving a single, richer audit trail across the whole lifecycle (see
`documentation/architecture.md` for the table shapes).

### Scope

The injection seam (`POST /api/executor/signals`) is still the only way
signals reach the executor — there is no automatic wiring from a Strigoi's
Prey or gropar's exit signal into the executor's queue. `RejectReason`
declares `MAX_TRANCHE`, and it is now enforced (was declared-only): the
`add-tranche` tool rejects with `MAX_TRANCHE` (writing a `decision_log`
entry, same as the other reject paths) once a position's `tranche` count
reaches `dracul.executor.max-tranche` (default 2), so tranching beyond the
configured cap is blocked before any eligibility/sizing work runs.
`VetoService` also now enforces `CORRELATED`: an entry is rejected when an
open position already exists in the same sector (case-insensitive) with the
same `mechanism` (anomaly type) as the candidate signal — this blocks
doubling up on one anomaly within a sector even below the `CONCENTRATION`
cap; a null sector or mechanism passes (fail-soft). The fuller veto catalog
(kill-criteria monitoring) remains out of scope and lands in later slices.

See `documentation/architecture.md` for the doctrine note on why guarded
execution is the one deliberate exception to Dracul's read-only design, and
`documentation/configuration.md` for the full `dracul.executor.*` property
reference.
