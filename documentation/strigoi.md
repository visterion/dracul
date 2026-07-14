# Strigoi

A Strigoi is a specialised hunter agent. Each Strigoi targets exactly
one documented market anomaly. Strigoi are registered with Vistierie as
scheduled agents; Vistierie owns the runtime, Dracul owns the domain
logic and the hunt pattern.

## Roster

| # | Name | Anomaly | Tier | Academic source |
|---|------|---------|------|-----------------|
| 1 | strigoi-spin | Spin-offs, forced selling | reasoning | Greenblatt 1997 |
| 2 | strigoi-insider | Insider cluster buys | routine | Lakonishok & Lee 2001 |
| 3 | strigoi-echo | Post-earnings drift (PEAD) | routine | Bernard & Thomas 1989 |
| 4 | strigoi-lazarus | Quality at 52w low | reasoning | Piotroski 2000 |
| 5 | strigoi-index | Index-inclusion drift | routine | S&P / Russell studies |
| 6 | strigoi-merger | M&A arbitrage | reasoning | Mitchell & Pulvino 2001 |

## Implementation status

| Strigoi | Status |
|---|---|
| strigoi-spin | **implemented 2026-06-05; term-sheet enrichment 2026-07-08; structured distribution fields 2026-07-11; full lifecycle persistence 2026-07-12** — EDGAR Form-10-12B spin-off registrations (last 60 days), reasoning tier (model_purpose `reasoning`), agent registered with Vistierie on startup; deterministic pre-screen surfaces recent spin-co registrations, the LLM assesses the Greenblatt forced-selling thesis (only tradeable tickers persisted). Each candidate carries `termSheet` / `termSheetAvailable` — the filing's cleaned summary-term-sheet text via Agora's `get_filing_text` tool (`AgoraFilings.filingText(filingUrl)`); the LLM extracts parent/ratio/record-date/size from it, fail-soft (conservative judgement) when unavailable. A deterministic `SpinTermsParser` regex-extracts `distributionRatio` / `recordDate` / `distributionDate` from `termSheet` server-side (and a best-effort `parentSymbol` from an exchange-qualified parenthetical); the LLM prefers these server-extracted fields (verifying rather than recomputing) and falls back to reading `termSheet` itself when any is `null`. **As of the 2026-07-12 lifecycle rebuild** the hunter is no longer single-shot/stateless: every 10-12B registration is persisted to `spin_candidate` (V26) and tracked through a REGISTERED → WHEN_ISSUED → DISTRIBUTED → SETTLED/ABANDONED state machine across hunts, with stage-appropriate enrichment (pre-distribution balance sheet, post-distribution size/forced-selling read, post-settlement valuation) and prey promotion gated to the DISTRIBUTED forced-selling window. Prompt bumped to `1.3.0` (new nullable stage fields, confidence rubric, 3m/6-12m horizon split). See "Strigoi-Spin: lifecycle persistence" below for the full flow |
| strigoi-insider | **implemented 2026-05-25; context enrichment 2026-07-12** — Form-4 cluster screener, Haiku tier (model_purpose `routine`), agent registered with Vistierie on Dracul startup, deterministic pre-screen (≥3 distinct filers, 30-day window, total > $500k purchases). Each clustered filer now carries its free-text Form-4 officer `role` (empty for non-officers), so the LLM's CEO/CFO-diversity rubric can actually weigh it. `fetch_recent_clusters` also annotates each cluster with `netInsiderDollar` (purchases minus concurrent insider sales in the window) and `concurrentInsiderSells` (distinct selling insiders); this is advisory only — the LLM weighs it into confidence, no cluster is dropped for it. Each cluster is further enriched by `InsiderEnrichmentService` (added 2026-07-12, fail-soft with per-group availability flags, never gates): `marketCap` / `adv` + `metricsAvailable` (via `EquityMetricsExtractor` and Agora `get_ohlc`, 20-day average dollar volume — same mechanism as strigoi-index), `analystCoverage` + `coverageAvailable` (Finnhub recommendation-trend analyst count, semantics as in echo SP3, fetched via `AgoraCompanyData.recommendationsStrict` — the outage-propagating variant, so the source-down guard below is real for this source; the swallowing default `recommendations()` stays with echo), `ytdReturn` + `ytdReturnAvailable` (calendar-YTD fraction from Agora OHLC), and `nextEarningsDate` / `daysToEarnings` + `earningsDateAvailable` (Agora earnings calendar via `AgoraEarnings`, informational only — no hard gate, unlike echo). Latency guard: clusters are sorted by `totalDollarValue` descending before the 25-cluster cap (truncation is logged, the smallest are dropped); a source failing with an availability error (`AgoraUnavailableException` / `MarketDataException(UNAVAILABLE)`, as opposed to symbol-specific failures) is skipped for the rest of the batch, and with ≥2 sources down enrichment is skipped entirely for the remaining clusters — a dead Agora tool cannot blow the 30s webhook budget. The prompt's rubric (v2, `1.2.0`) up-weights small/mid-cap + low-coverage names (Lakonishok-Lee information asymmetry), dampens well-covered large caps, backs the value-trap risk with the `ytdReturn` number, adds a `daysToEarnings` < ~10 timing caveat to risks, and demotes CEO/CFO role diversity to a secondary refinement of the cluster/conviction picture. **Routine/opportunistic classification (added 2026-07-12, prompt `1.3.0`)**: each filer is classified `OPPORTUNISTIC` / `ROUTINE` / `UNKNOWN` (Cohen, Malloy & Pomorski 2012) from their multi-year Form-4 history fetched via `AgoraFilings.ownerHistoryStrict` (`get_form4_owner_history`) — ONE call per cluster (the tool returns every reporting owner of the company at once), obeying the same availability source-down guard as the other enrichment sources. `RoutineClassifier` rule: ROUTINE when ≥2 distinct prior calendar years each carry an open-market purchase (code `P`) in the same month ±1 as the current buy (Dec/Jan wrap included; a positive cadence wins even on a `truncated` history); UNKNOWN when the history is `truncated` or carries fewer than 3 purchases (absence of a cadence is not trusted — never scored as opportunistic); OPPORTUNISTIC otherwise. The cluster carries `opportunisticShare` (opportunistic ÷ classifiable), `classifiedFilers`, `unknownFilers`, `classificationAvailable`; each filer also carries `sharesOwnedFollowing`, `purchaseAsPctOfHoldings` (relative conviction) and `planned10b5_1` (tri-state Rule 10b5-1(c) plan flag — derived from the same owner history, `null` = unknown ≠ false). 10b5-1-planned buys are **marked, not dropped** (aff10b5One is tri-state; `null` is not `false`, so a hard drop would silently discard unknown-plan buys). The `1.3.0` prompt makes `opportunisticShare` the PRIMARY criterion — a routine-dominated cluster is skipped/dampened regardless of dollar size or filer count — uses `purchaseAsPctOfHoldings` as an amplifier, dampens 10b5-1 buys, treats UNKNOWN/`classificationAvailable=false` conservatively (never as opportunistic), and reworks the confidence bands around the opportunistic read rather than raw filer-count + dollar. |
| strigoi-echo | **implemented 2026-06-02; signal upgrade (v2 SP1) 2026-06-24; market-reaction signals (v2 SP2) 2026-06-27; kill-criteria polish 2026-07-13** — Haiku tier (model_purpose `routine`), agent registered with Vistierie on Dracul startup, deterministic long-only pre-screen (current price ≥ $5). Earnings announcements come from **Finnhub `/calendar/earnings`** (primary), with the Yahoo earnings calendar demoted to a config-selectable fallback. A deterministic enrichment layer replaces the old raw-5%-surprise signal with academic PEAD signals: **time-series SUE** (Foster seasonal-random-walk, from SEC EDGAR quarterly diluted-EPS history with date-based seasonal alignment) ranked cross-sectionally into **deciles** (z-band fallback for thin batches), **revenue-surprise / double-beat**, and **consecutive seasonal beats**. SP2 further enriches surviving candidates with market-reaction signals from daily OHLC and Finnhub metrics (see SP2 section below). The LLM applies a SUE-based confidence rubric (not a fixed 5% threshold); each emitted prey echoes its numeric **SUE + decile** in `signals`. Long-only. Prompt bumped to `1.2.0` (2026-07-13): the kill-criteria section now includes a worked, concrete price example (a close below `currentPrice` — the price at the time the thesis was flagged — stated as a dollar level, e.g. "dead if it closes below $54.20"), aligning it with the concrete-price-example style already used by strigoi-lazarus/merger/spin; no field renames, thesis unchanged. |
| strigoi-lazarus | **implemented 2026-06-05; real Piotroski F-score (Slice 2b) 2026-07-07** — watchlist-scoped; screens watchlist names within ~10% of their 52-week low with a light solvency gate (positive ROA or free cash flow, modest leverage), plus a **cheapness (valuation) gate** (must be cheap by price-to-book or price/FCF-per-share) and a hard drop on high Sloan accruals — both deterministic and applied server-side. The F-Score is no longer judged qualitatively by the LLM: it is computed deterministically via Agora's `get_fundamental_score` tool (strict scoring + a `fScoreCriteriaAvailable` coverage count from SEC companyfacts) and attached to each surviving candidate. The reasoning-tier LLM then applies the ranking/confidence rubric — rank by `fScore`, skip below 6, dampen confidence when `fScoreCriteriaAvailable` is thin — and narrates the thesis, rather than scoring the F-Score itself, and emits `QUALITY_52W_LOW` Prey. Each enriched candidate also carries `cfoExceedsNetIncome` **plus `cfoExceedsNetIncomeAvailable`** (added 2026-07-12): because the accruals hard-drop already removes every candidate with an available-but-false signal server-side, a wire-level `false` only ever means "not computable" — the availability flag makes that explicit, and the prompt treats unavailable as unknown (mild confidence dampening), not as a quality warning. **Timing/stabilization signals (added 2026-07-12)**: each surviving candidate additionally carries three deterministic signals computed server-side from one Agora daily-OHLC query (~260 trading days) — `priceVs50dMa` (last close vs the 50-day MA, decimal fraction), `weeksSinceNewLow` (full weeks since the ~52-week closing low; 0 = fresh low), `momentum3m` (~63-bar price change, decimal fraction) — plus `timingAvailable` (false only when all three are null; individual fields may still be null on short history). The prompt uses them for a "no falling knife" rule: a fresh low (≤ ~2 weeks) with the price clearly below the 50-day MA means skip or dampen hard regardless of `fScore`; ≥ ~4 weeks since the low or price above the 50d MA reinforces the setup; `momentum3m` near zero after a decline reads as base building. Fail-soft per candidate; an availability-kind OHLC failure disables the OHLC source for the remaining candidates of the batch (symbol-specific NOT_FOUND does not). **Altman-Z distress screen (added 2026-07-12)**: each surviving candidate also carries `zScore` (classic Altman Z, 1968; scale 2) + `zScoreAvailable`, computed server-side by `AltmanZCalculator` from SEC XBRL concepts via Agora's `get_company_concept` (Assets, AssetsCurrent, LiabilitiesCurrent, Liabilities, RetainedEarningsAccumulatedDeficit as same-date balance-sheet instants; OperatingIncomeLoss as EBIT and Revenues — with the F-score's fallback tag chain — as latest-fiscal-year annual flows) plus the Finnhub market cap already fetched for the screen (USD millions, converted ×10⁶ to USD for X4). No partial Z: any missing input, date misalignment, or non-positive liabilities → `zScoreAvailable=false`, `zScore=null`. Z is only attempted when the candidate's F-score itself resolved (both read the same EDGAR companyfacts, so a resolved F-score proves the symbol is known to EDGAR); an Agora availability failure during the concept fetches then genuinely means "source down" and disables the Z enrichment for the remaining candidates of the batch. The prompt applies a distress VETO: Z < 1.8 → do not emit, regardless of `fScore`/timing; 1.8–3.0 → grey zone (dampened confidence, Z named in `risks`); > 3.0 → solid; `zScoreAvailable=false` → unknown, judge conservatively, never invent a Z. Caveat in the prompt: Z is calibrated on industrials and unreliable for financials (banks/insurers, recognized by `companyName` patterns since the payload has no sector field) — there it is ignored in either direction. **Batch cap + F-score guard (added 2026-07-12)**: the enrichment sorts candidates by `pctAboveLow` ascending (closest to the 52w low first — the only meaningful priority available before any enrichment data is fetched) and caps at 25 per batch (log line on truncation, mirroring the insider cap). The F-score fetch itself now uses the strict variant (`fundamentalScoreStrict`): an Agora availability failure disables the fetch for the remaining candidates of the batch (candidates ride through score-less/fail-soft, exactly as with an unavailable score). **Forward revisions + analyst coverage (added 2026-07-12)**: each surviving candidate also carries `netEstimateRevisionsProxy` / `netEstimateRevisionsDirection` (the echo SP3 recommendation-trend delta, reused via `RevisionsProxy` — latest-period net minus previous-period net of strongBuy+buy−sell−strongSell; `up`/`down`/`flat`) and `analystCoverage` (latest-period analyst count via `AnalystCoverage`, from the SAME `get_analyst_estimates` response — no extra call), plus ONE shared `revisionsAvailable` flag (echo's two flags are always equal by construction, so lazarus carries one; false ⇒ all three fields null). Costs one additional Agora call per candidate, fail-soft, with the same availability-kind source-down guard as the OHLC/concept fetches (symbol-specific failures do not disable the source) — fetched via `AgoraCompanyData.recommendationsStrict`, the outage-propagating variant (the default `recommendations()` swallows outages into an empty list, which would make the guard dead code and burn a dead ~16s call per remaining candidate). The prompt uses it as a forward-looking check on the backward-looking TTM fundamentals: a clearly negative revisions direction = value-trap warning → dampen confidence + name it in `risks` — explicitly a DAMPENER, not a veto (severity ladder: fScore<6 skip > Z<1.8 veto > falling-knife veto > revisions dampener); `up`/`flat` near the low = quiet reinforcer; low `analystCoverage` = mild advisory neglect up-weight, high = mild dampener; `revisionsAvailable` false = unknown/conservative, never invented. **Depot dedup (added 2026-07-13)**: the candidate universe (still the user's watchlist names) is filtered against the live depot-1 positions (`HeldPositionService.openPositions`, by symbol) before screening — a watchlist name already held is not a "new" quality-at-low candidate. A depot-down fetch (fail-soft, empty position list) excludes nothing rather than erroring. |
| strigoi-index | **implemented 2026-06-06; liquidity enrichment 2026-07-11; announcement-anchored lifecycle 2026-07-12** — routine tier (model_purpose `routine`), agent registered with Vistierie on startup. **As of the 2026-07-12 lifecycle rebuild** the hunter no longer reads the Wikipedia `Date added` column (effective-date-only, i.e. already too late). It ingests announced constituent changes from Agora's `get_index_constituent_changes` (S&P press-release RSS + Russell reconstitution — each change carrying both an **announcement date** and an **effective date**), persists every change to `index_event` (V27) and tracks it through an ANNOUNCED → EFFECTIVE → POST → CLOSED / ABANDONED state machine across hunts. The logic is flipped: the LLM judges whether the **today → `effectiveDate`** forced-buy window is still open (not whether an addition already happened) and emits `INDEX_INCLUSION` Prey **only** from ANNOUNCED rows; EFFECTIVE/POST rows are informational (run-up/reversal observation only). Prey promotion is hard-gated to the still-open ANNOUNCED window (source-aware: S&P 5 trading days, Russell 20). Prompt bumped to `2.0.0` (logic-flip). See "Strigoi-Index: announcement-anchored lifecycle" below for the full flow |
| strigoi-merger | **implemented 2026-06-05; term-sheet enrichment 2026-07-08; structured deal terms + server-computed spread 2026-07-11; expected-value data (Mitchell & Pulvino) 2026-07-12** — EDGAR EFTS `forms=DEFM14A,SC TO-T` (definitive merger proxies + tender offers, last 45 days), reasoning tier (model_purpose `reasoning`), agent registered with Vistierie on startup; surfaces recent SEC deal filings (DEFM14A definitive merger proxies + SC TO-T tender offers); the reasoning-tier LLM judges the spread and closing probability and emits `MERGER_ARB` Prey. Each candidate now carries `termSheet` / `termSheetAvailable` — the filing's cleaned summary-term-sheet text via Agora's `get_filing_text` tool (`AgoraFilings.filingText(filingUrl)`) — plus `lastPrice` / `priceAvailable`; the LLM extracts offer/consideration/conditions/termination-fee from the term sheet and computes the spread vs `lastPrice`, fail-soft (conservative judgement) when unavailable. A deterministic `DealTermsParser` now regex-extracts `offerPrice` / `considerationType` (cash/stock/mixed) / `exchangeRatio` / `breakFee` from `termSheet` server-side, and `MergerEnrichmentService` computes `spreadPercent = (offerPrice − lastPrice) / lastPrice × 100` when both are available; the LLM prefers these server-extracted fields (verifying rather than recomputing) and falls back to reading `termSheet` itself when any is `null`. `DealTermsParser` also extracts the deal time-axis dates — `agreementDate` (the announcement anchor; the feed's DEFM14A/SC TO-T land weeks/months after announcement, so `lastPrice` is already the arb price), `expectedCloseDate` (quarter/half estimates mapped conservatively to the period end), and a separate `outsideDate` (End Date, never used as the close estimate). `MergerEnrichmentService` then adds the Mitchell & Pulvino (2001) expected-value inputs: `unaffectedPrice` / `unaffectedPriceAvailable` (close of the last trading day before `agreementDate`, from ONE ~400-day Agora daily-OHLC query per candidate, same latency-guard/source-down short-circuit as Lazarus), `daysToClose`, `annualizedSpreadPercent` (`spreadPercent × 365 / daysToClose`, guarded to `daysToClose ≥ 1`), and `breakDownsidePercent` (`(lastPrice − unaffectedPrice) / lastPrice × 100`, the deal-break cliff). The prompt (v1.2.0) reframes the judgement around expected value — weigh `annualizedSpreadPercent` against `breakDownsidePercent`, don't chase wide spreads, dampen stock/mixed deals (unhedged acquirer risk), couple the horizon to `expectedCloseDate`/form type, and treat the payoff as negatively-skewed with an event-based (not trailing-stop) exit |

### Strigoi-Echo SP2: market-reaction signals

**SP2 market-reaction signals (deterministic, added 2026-06-27).** Each surviving
candidate is further enriched from daily OHLC and Finnhub metrics:

- `announcementCar1d` / `announcementCar3d` — market-adjusted abnormal return around the
  report day, computed vs the market proxy (default SPY) and beta-adjusted when beta is
  known. A positive CAR with the same sign as the surprise is the strongest confirming
  signal; a negative CAR (the market already faded the beat) is a hard counter-argument.
- `abnormalVolume` — report-day volume / trailing 20-day average volume.
- `momentum6_12m` — price return over the 6-12 month window (price + earnings momentum
  compound).
- `adv`, `marketCap`, `beta`, `sector` — liquidity and size, used to dampen confidence on
  large, heavily-arbitraged names.

Every SP2 field carries an availability flag (`carAvailable`, `metricsAvailable`); missing
OHLC or metrics degrade the affected field conservatively and never abort the run.

### Strigoi-Echo SP3: earnings-quality + event/timing gate

**SP3 earnings-quality + event/timing gate (deterministic, added 2026-06-27).** Before a
candidate reaches the LLM it must pass a server-side hard gate:

- **Sloan accrual ratio** `(netIncome − operatingCashFlow) / totalAssets` from EDGAR. Above
  `echo.gate.max-accrual-ratio` (default 0.10) the beat is accrual-driven (not cash-backed) and
  the candidate is dropped.
- **Confounder screen** over Finnhub company news since the report date (M&A, restatement,
  guidance cut, dilution, investigation). Any hit drops the candidate — the announcement-CAR is
  then not the drift signal. (EDGAR 8-K item-code parsing is a deferred refinement.)
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
   NOTHING` on the natural key `COALESCE(cik, lower(company_name))`, so a re-run never
   duplicates a spin-co nor resets its lifecycle. The spin-co's registrant CIK is
   parsed from the EDGAR filing URL (`CikExtractor.fromFilingUrl`) and preserved.
2. **RECONCILE** — `SpinLifecycleReconciler` recomputes the desired state from the
   persisted non-terminal rows and applies forward-only transitions via guarded
   compare-and-set. Two phases: calendar transitions (pure SQL/Java, **zero** Agora
   calls) and **one** batched `AgoraMarketData.quotes()` probe across every
   symbol-bearing pre-distribution row.
3. **ENRICH** — `SpinCandidateEnricher` fetches stage-appropriate data for a bounded
   work-set (rows that transitioned this run first, then non-terminal rows
   oldest-checked, deduped and capped at **25/run** to hold the webhook latency
   budget) and persists it as per-stage JSONB snapshots.
4. **RESPOND** — the LLM payload (`EnrichedSpinCandidate` rows) is rebuilt from the
   persisted columns + snapshots of the **active, unpromoted** window {`REGISTERED`,
   `WHEN_ISSUED`, `DISTRIBUTED`}, newest-discovered first — not read straight from
   the live search. The ingest search's data-source health rides the response as
   before.

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

- **`REGISTERED` / `WHEN_ISSUED`** — the term sheet is captured once (fetch +
  `SpinTermsParser` → `distribution_ratio`/`record_date`/`distribution_date`, the raw
  `term_sheet_text` prose, and a best-effort `parent_symbol`), then the
  pre-distribution **balance sheet by CIK** (`SpinBalanceSheetSnapshotter`):
  `totalAssets`, `totalLiabilities`, `retainedEarnings` (XBRL concepts anchored to a
  single balance-sheet instant, no cross-date mixing) plus Finnhub `industry` when a
  ticker exists. No market-cap ratios — there is no market capitalisation before the
  distribution.
- **`DISTRIBUTED`** — the size / forced-selling read (`SpinDistributionSnapshotter`):
  `spincoMarketCapMillions` / `parentMarketCapMillions` (Finnhub via
  `EquityMetricsExtractor`, parent keyed on the best-effort `parent_symbol`),
  `sizeRatio` (spinco ÷ parent market cap, the small-spin-off effect; null unless both
  caps resolve), `daysSinceDistribution`, and `postSpinInsiderBuying` (any Form-4
  open-market purchase — code `P` — on or after the distribution date, from one
  `ownerHistoryStrict` call).
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
non-null `spincoMarketCapMillions`, and `daysSinceDistribution ≤ promotion-window-days`
(default 90). **`sizeRatio` is NOT a hard condition** — parent/sizeRatio are often
unresolvable and gating on them would silence the hunter, so `sizeRatio` is a
confidence booster in the prompt instead. Exactly-once is layered: the delivery-level
filter in `complete()` (only newly-inserted prey reach the hook), the row-level
`promoted_at IS NULL` CAS, and the prey same-day natural-key unique index (V21) as the
final backstop. A prey matching no promotable row is skipped fail-soft — it is already
persisted regardless.

**Horizon.** The prompt (`1.3.0`) splits the thesis into a ~3-month
forced-selling/index-drop compression window and a 6-12-month fundamental re-rating;
the controller's default horizon is `6m`.

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
  announcement). The single strict source is the Agora price feed; an availability
  outage propagates so the enricher can short-circuit the source for the rest of the
  batch.
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

## Hunt Pattern

Every Strigoi follows the same three-step shape:

1. **Pre-screen** (deterministic, no LLM) — pulls candidates from the
   appropriate hunting-ground adapter (EDGAR, prices, news, calendar)
   and filters to the ones worth spending tokens on.

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
`AAPL`) and `dracul.fundamentals.non-us-suffixes` (the non-US venue whitelist) — see
`documentation/configuration.md`.

**Cache-expiry caveat:** `handleFetch` responses are served through
`ToolFetchCache` (per-tool TTL). A pattern approved or rejected after a tool's
cache entry was populated only becomes visible in `active_patterns` once that
cache entry expires — acceptable for v1; there is no cache-invalidation hook
on pattern-status changes.

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
   positions (`HeldPositionService.openPositions("depot-1")`, no LLM) and returns
   trigger events. Each trigger type is evaluated **once per distinct symbol** —
   price/volume spikes, negative news, insider sells, and analyst downgrades are
   market-wide signals, so a single market-data fetch per symbol suffices.
2. Vistierie spawns one reasoning-tier (Sonnet) child run per triggered symbol; the
   run judges severity and returns `{severity, thesis, confidence}`.
3. `POST /api/daywalker/complete` persists the assessment. Since step 1 is depot-sourced
   (2026-07-13, A6), every trigger carries a `position_id` (the symbol, echoed back by the
   LLM), so `DaywalkerCompletionService` routes straight to the single configured
   `dracul.primary-user-email` owner (same convention as gropar) rather than resolving
   owners via a watchlist lookup — one `dracul.daywalker_alerts` row is written for that
   owner if its `(owner, symbol, trigger_type)` cooldown has not yet elapsed. The
   watchlist-owner fan-out path (`findOwnersBySymbol`, "every owner of that symbol") only
   still fires for triggers with no `position_id` at all, which the depot-sourced engine
   never produces.

Every depot position is a real holding, so every trigger is fanned out per position and
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
  against three checks before the agent may act on it: `SCHEMA_INVALID`
  (missing symbol/direction/confidence), `LOW_CONFIDENCE` (below
  `dracul.executor.min-confidence`), `MAX_POSITIONS` (open-position count at
  or above `dracul.executor.max-positions`).
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
   measurable kill-criteria breach, or giveback (fraction of peak MFE-in-R
   given back, active once MFE clears `dracul.executor.giveback-active-from-r`)
   — always enforced, never the LLM's call. Precedence when more than one
   condition is simultaneously breached: stop-breach first (`HARD_STOP`),
   then measurable kill-criteria (`HARD_KILL_CRITERIA`), then giveback
   (`GIVEBACK_BREACH`) — the first match names the `decision_log` reason
   code and no later check runs. Kill-criteria here reuses the same
   `KillCriteriaEvaluator` described below, so only measurable price-level
   criteria can hard-close a position; qualitative criteria never do and
   stay surfaced via `kill_criteria_breached` for the LLM to judge.
3. **`StopRatchetService`** — ratchets the active stop up to the chandelier
   level (`dracul.executor.chandelier-mult` × ATR below the highest price
   reached), never down.

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

**MAE (adverse-excursion) tracking.** Every maintenance pass also updates
`executor_position.lowest_price` for BUY positions: the new floor is
`min(previous lowest_price (or entry price if never set), current close)`,
written only when the close is a new low. SELL positions never write
`lowest_price` — their adverse extreme is the *highest* close, already
tracked as `highest_price` by the ratchet step, so `mae_r` for a SELL
position derives from `highest_price` instead. This groundwork feeds the R
distribution / backtest work that reads `mae_r` off the closed-position
history.

`kill_criteria_breached` is populated by `KillCriteriaEvaluator`
(`de.visterion.dracul.criteria`), a deterministic, stateless, best-effort
parser that recognizes only absolute **price-level** kill criteria (e.g.
"Close below $90.00", "Price rises above 120") and evaluates them against
the position's daily close. It is v1-scoped: percent thresholds (e.g.
"widens above 12%") and qualitative criteria (e.g. "Merger terminated") are
left unparsed — those stay in the raw `kill_criteria` list for the LLM to
judge itself. Anything the evaluator can't confidently parse is silently
skipped rather than raising an error, so a malformed or free-form criterion
never blocks the pipeline.

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
