package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.DataSourceHealth;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.strigoi.lazarus.FundamentalScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Regulatory-filings facade backed by Agora (get_form4_transactions / search_filings /
 * get_company_concept / get_company_facts / get_eps_history over MCP). Fetch + map to neutral
 * Dracul DTOs only; all interpretation stays in the strigoi helpers. Never throws (except the
 * deliberately strict variants): Agora failure degrades to an unavailable DataSourceResult or
 * an empty ConceptSeries.
 */
@Component
public class AgoraFilings {

    private static final Logger log = LoggerFactory.getLogger(AgoraFilings.class);
    private static final String SOURCE = "agora";

    /** {@code get_form4_transactions} and {@code search_filings} both default to 100 rows, and a
     *  market-wide form/date window holds far more than that — several THOUSAND Form-4 filings per
     *  day. Taking the default silently turns a market-wide scan into an arbitrary slice of itself:
     *  a 20-day and a 90-day merger window returned the identical newest 25 candidates in
     *  production. Ask for the tool's maximum instead (Agora {@code MAX_LIMIT} on both tools). */
    private static final int WINDOW_LIMIT = 1000;

    /**
     * How far the walk's {@code to} bound recedes between two calls, in calendar days.
     *
     * <p><b>Derived from the production measurements in {@link #recentForm4}, not chosen round.</b>
     * Each call reads the newest ~272 filings inside ITS window, so what a call actually covers is
     * a stretch of filing dates ending at its own {@code to}. The three wide calls measured on
     * 2026-08-05 covered, by transaction date:
     * <pre>
     *   to=2026-08-05  ->  08-03..08-05  (419/191/57 on the dense days)   reach: 2 days
     *   to=2026-08-03  ->  07-30..08-03  (134/155/76/15/42)               reach: 4 days
     *   to=2026-08-01  ->  07-29..07-31  (193/90/98)                      reach: 3 days
     * </pre>
     * The step must not exceed the SMALLEST reach, or the walk leaves a hole between where one
     * call stopped and where the next begins — a silently swallowed day is exactly the failure
     * this whole exercise exists to prevent. The smallest measured reach is 2 days (the
     * {@code to=08-05} call, whose budget was eaten by the 419-filing day 08-03), so the step is
     * 2 and the other two calls' 3-4 day reach becomes overlap, not gap.
     *
     * <p><b>Why the step is FIXED and not derived per call from the response.</b> The oldest
     * {@code transactionDate} a call carries is observable, and using it as "how far I got" is
     * tempting and wrong: the {@code to=08-05} call carried ONE transaction dated 07-29 (a
     * straggler, late-filed) while its dense coverage stopped at 08-03. Receding to that oldest
     * date would have jumped the walk straight past 07-30..08-02 — the very days holding 134, 155,
     * 76 and 15 transactions. And a transaction dated d only proves some filing with file_date
     * >= d was read; it can never bound how far BACK the filing-date reach went. Coverage is
     * therefore not observable from the response at all, and the step rests on the measured reach
     * with a 2x margin on the typical day.
     *
     * <p><b>Residual risk, stated rather than hidden:</b> a filing day heavier than the heaviest
     * measured one could make a call's reach fall below 2 days, and the seam to the next call
     * would then hold a filing-date gap that nothing in the response reveals. The mitigation is
     * the margin (the step is half the typical measured reach), not a runtime check; a run cannot
     * prove its own continuity. If a prod run ever shows a day denser than 08-03's 419 filings,
     * this constant is what must move.
     */
    private static final int FORM4_WALK_STEP_DAYS = 2;

    /**
     * How many {@code get_form4_transactions} calls ONE {@link #recentForm4} may issue.
     *
     * <p>The receding walk makes the cost LINEAR in the caller's window (one call per
     * {@link #FORM4_WALK_STEP_DAYS} days of it), and the insider fetch tool's input schema permits
     * {@code lookback_days} up to 30 — 16 sequential calls at up to 45 s each is ~720 s inside a
     * single tool call, against a 600 s webhook timeout. So the call count is capped rather than
     * the schema narrowed: the caller keeps its contract and a walk that did not reach {@code from}
     * says so via {@code truncated=true} instead of looking complete.
     *
     * <p>Ten calls cover {@code 1 + 2 x 9 = 19} days of window. The hunter's default
     * {@code lookback_days=7} is an INCLUSIVE 8-day window and costs FOUR calls
     * ({@code to}, {@code to-2}, {@code to-4}, {@code to-6}; {@code to-8} would precede
     * {@code from}), i.e. 4 x 45 s = 180 s worst case.
     *
     * <p>The arithmetic that fixes the number, from the outside in:
     * <pre>
     *   1800 s  Vistierie max_run_seconds for strigoi-insider (the whole run: fetch + reasoning)
     *    600 s  InsiderDefaults.FETCH_TIMEOUT_SECONDS — a third of the run budget, so the fetch
     *           cannot starve the reasoning turns even at its worst case
     *    450 s  10 calls x 45 s (dracul.agora.tool-timeout-ms[get_form4_transactions]) — the
     *           worst case; the 150 s left inside the webhook timeout is not spare, it is where
     *           InsiderEnrichmentService's own calls (same request) live
     * </pre>
     * Pinned by {@code InsiderToolTimeoutBudgetTest}: {@code MAX_WINDOW_SLICES x the CONFIGURED
     * Agora budget} must stay below {@code FETCH_TIMEOUT_SECONDS}, so the three numbers cannot
     * drift apart.
     */
    public static final int MAX_WINDOW_SLICES = 10;

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraFilings(AgoraClient agora) { this.agora = agora; }

    /**
     * Market-wide Form-4 transactions in [from, to]; callers filter by ticker client-side.
     *
     * <p>Fetched as a RECEDING WALK: {@code from} stays fixed at the start of the lookback and
     * only the {@code to} bound steps back by {@link #FORM4_WALK_STEP_DAYS} per call, until
     * receding further would precede {@code from}. Each call reads the newest ~272 filings inside
     * its own window (Agora fetches one EDGAR archive document per hit under a 30 s aggregate
     * deadline), and because those filings all sit inside {@code [from, to]}, every transaction
     * they carry survives the transaction-date filter and is kept.
     *
     * <p><b>Measured on prod Agora {@code 4586c51} (limit=1000, ~35 s per call), 2026-08-05.</b>
     * These four numbers are the whole design rationale — reproduce them before changing it:
     * <pre>
     *   from=2026-08-04 to=2026-08-04 ->  13 tx, truncated=true, 0 open-market buys
     *                                     by txDate {08-04: 13}
     *   from=2026-07-29 to=2026-08-05 -> 676 tx, truncated=true, 16 open-market buys
     *                                     by txDate {07-29:1, 07-30:2, 07-31:6,
     *                                                08-03:419, 08-04:191, 08-05:57}
     *   from=2026-07-29 to=2026-08-03 -> 424 tx, truncated=true
     *                                     by txDate {07-29:2, 07-30:134, 07-31:155,
     *                                                08-01:76, 08-02:15, 08-03:42}
     *   from=2026-07-29 to=2026-08-01 -> 381 tx, truncated=true
     *                                     by txDate {07-29:193, 07-30:90, 07-31:98}
     * </pre>
     * A ONE-DAY window for 2026-08-04 yields <b>13</b> transactions; the wide window finds
     * <b>191</b> for that same date. That is structural, not noise: EFTS orders by
     * {@code file_date} DESCENDING and SEC §16(a) gives filers two business days, so the filings
     * filed ON day D overwhelmingly report trades of D-1/D-2 — which the transaction-date filter
     * then discards. A narrow window spends its entire budget on the lowest-yield filings that
     * exist. Eight day-slices would have returned ~104 transactions where ONE wide call returns
     * 676; the day-slicing this replaced (BUG-S1b, first attempt) was worse than the single call
     * it was meant to fix. The three wide calls above union to ~1,400 transactions.
     *
     * <p>Overlap between consecutive calls is DELIBERATE, not waste: the step is smaller than the
     * measured reach precisely so that no day falls between two calls. The duplicate rows that
     * follow are removed by the record-wide {@link java.util.LinkedHashSet} below — an identical
     * transaction returned by the {@code to=08-05} and the {@code to=08-03} call is one element,
     * and this access pattern is exactly what that set was built for (same {@link Form4Filing}
     * values from two windows). First-seen order is kept, so the merged list stays deterministic:
     * newest-window rows first.
     *
     * <p><b>Truncation semantics: NOT the OR of the calls' own flags.</b> Every call in this walk
     * comes back {@code truncated=true} by construction — each one IS a deliberate cut of its own
     * window, that is how the strategy works — so OR-ing them would set the flag on every single
     * answer and tell the hunter nothing. The question the flag must answer is whether the WALK
     * covered what was asked for, so {@code truncated} means: the walk did NOT reach {@code from}
     * (the slice budget ran out first), or one of its calls failed and left a hole. A walk that
     * receded past {@code from} within budget is reported clean, and the per-call flags are
     * deliberately ignored — recorded here rather than dropped silently. {@code partial} IS still
     * OR-ed: it means Agora could not cover the window it was given, which stays meaningful.
     *
     * <p>A call that throws keeps the other calls' rows and marks the result truncated; only ALL
     * calls failing degrades to {@code unavailable} — see {@link #recentForm4Failure}.
     *
     * <p>This overload spends the NIGHTLY HUNTER's budget. A caller on a tighter clock must state
     * its own — see {@link #recentForm4(LocalDate, LocalDate, int)}; inheriting this one silently
     * is how the daywalker poll lost its 60 s budget.
     */
    public DataSourceResult<Form4Filing> recentForm4(LocalDate from, LocalDate to) {
        return recentForm4(from, to, MAX_WINDOW_SLICES);
    }

    /**
     * Like {@link #recentForm4(LocalDate, LocalDate)} but with the CALLER's own slice budget:
     * at most {@code maxSlices} calls of the receding walk, reporting {@code truncated=true} when
     * the budget ran out before the walk reached {@code from}. With {@code maxSlices=1} this is
     * exactly one wide call over the caller's whole window — the pre-slicing behaviour, and the
     * highest-yield single call available.
     *
     * <p>Why this is a parameter and not one shared constant: the two callers are on different
     * clocks. {@code StrigoiInsiderWebhookController} is a NIGHTLY market-wide scan inside a 600 s
     * webhook timeout and wants breadth; {@code DaywalkerEventEngine} is an INTRADAY trigger
     * engine whose whole poll — positions, watchlist, this fetch, and every per-symbol detector —
     * lives inside a 60 s budget (`dracul.daywalker.poll-budget-ms`) and can afford exactly one
     * call. Without this parameter daywalker would inherit the hunter's budget and any poll whose
     * window spanned several days would issue several sequential ~33 s calls, blow
     * {@code planFuture.get(60 s)} and log "skipping all symbols this poll" — a SILENT
     * zero-trigger poll, the worst failure class this codebase has. A slice budget stated at the
     * call site makes the cost bounded by construction rather than by how the window falls.
     *
     * <p>{@code maxSlices} below 1 is clamped to 1: no caller may turn this into a zero-call
     * "clean empty" answer.
     */
    public DataSourceResult<Form4Filing> recentForm4(LocalDate from, LocalDate to, int maxSlices) {
        int sliceBudget = Math.max(1, maxSlices);
        // Insertion-ordered SET, not a list: consecutive calls overlap ON PURPOSE, so the merged
        // answer must be deterministic AND duplicate-free. See the duplicate analysis on
        // recentForm4Slice.
        java.util.LinkedHashSet<Form4Filing> merged = new java.util.LinkedHashSet<>();
        boolean partial = false;
        boolean aCallFailed = false;
        boolean anyCallSucceeded = false;
        boolean walkReachedFrom = false;
        String lastFailure = null;

        // The receding walk: `from` is FIXED, only `to` steps back. An inverted window makes one
        // verbatim call and is done — Agora decides what it means.
        LocalDate callTo = to;
        for (int call = 0; call < sliceBudget; call++) {
            try {
                JsonNode res = recentForm4Slice(from, callTo);
                anyCallSucceeded = true;
                partial |= res.path("partial").asBoolean(false);
                // res.truncated is deliberately NOT OR-ed here — every call of this walk is a cut
                // of its own window by construction, so the flag would always be true and say
                // nothing. See the truncation paragraph on recentForm4.
                mapForm4Rows(res, merged);
            } catch (AgoraUnavailableException e) {
                lastFailure = e.getMessage();
                aCallFailed = true;   // a hole in the walk — never report that as clean
            }
            LocalDate next = callTo.minusDays(FORM4_WALK_STEP_DAYS);
            if (next.isBefore(from)) {
                // Receding further would precede `from`: the last call's window already started at
                // `from` and its measured reach covers the remaining <= FORM4_WALK_STEP_DAYS days.
                walkReachedFrom = true;
                break;
            }
            callTo = next;
        }
        if (!anyCallSucceeded) return recentForm4Failure(lastFailure);
        return degradation(partial, !walkReachedFrom || aCallFailed,
                List.copyOf(merged), "Form-4 transactions");
    }

    /**
     * One {@code get_form4_transactions} call of the receding walk: fixed {@code from}, receding
     * {@code to}.
     *
     * <p>On duplicates. Consecutive calls of the walk OVERLAP by design — the step (2 days) is
     * smaller than a call's measured reach (2-4 days), because a step larger than the reach would
     * leave a day uncovered between two calls. Overlap is therefore not waste to be eliminated
     * but the mechanism that closes the seams, and it means:
     * <ul>
     *   <li>(a) YES, the same accession is fetched by more than one call, and so is the same
     *       transaction: the {@code to=08-05} and the {@code to=08-03} call both cover
     *       transaction dates 07-30..08-03 in the measured week. The duplicated archive-GET
     *       budget is the price of continuity. Agora's own late-filing pad
     *       ({@code FORM4_LATE_FILING_PAD_DAYS} = 10 filing-days past each {@code to}) adds more
     *       of the same, and is what lets a call see trades filed after its window closed.</li>
     *   <li>(b) So YES, duplicate TRANSACTIONS reach us — unlike the day-slicing this replaced,
     *       where Agora's transaction-date filter ({@code parseForm4}: drop if {@code txDate} is
     *       outside the caller's window) made the slices disjoint by construction. Here the
     *       windows are nested, not disjoint, so the same row genuinely arrives twice.</li>
     *   <li>(c) Handling: the {@link java.util.LinkedHashSet} below is therefore LOAD-BEARING,
     *       not defensive. A duplicated transaction would add a second filer row to a ticker and
     *       manufacture a cluster that never happened. The dedup key is the whole
     *       {@link Form4Filing}
     *       record (ticker + filer name + role + transaction date + shares + dollar value +
     *       transaction code): the DTO carries no accession number, and that tuple is what
     *       identifies a Form-4 non-derivative line — the same insider filing the same code for
     *       the same size and the same value on the same day in the same ticker is one line,
     *       reported twice. The residual risk is NOT symmetric, and the asymmetry is the reason
     *       this trade is acceptable: a genuinely distinct pair (a direct and an indirect holding
     *       line of the same size, price and day) collapses into one row, which for a BUY only
     *       UNDER-states the cluster (conservative — a cluster we miss, never one we invent),
     *       while for a SELL row it removes a subtrahend and RAISES {@code netInsiderDollar},
     *       flattering the cluster. Sells are advisory-only in the insider screen (they never
     *       drop a cluster), so the flattering direction cannot by itself create a signal — but
     *       it is the side to watch if sells ever become a gate.</li>
     * </ul>
     */
    private JsonNode recentForm4Slice(LocalDate from, LocalDate to) {
        ObjectNode args = mapper.createObjectNode();
        args.put("from", from.toString()).put("to", to.toString()).put("limit", WINDOW_LIMIT);
        return agora.callTool("get_form4_transactions", args);
    }

    /** Every slice threw: a genuine outage, reported as {@code unavailable} rather than as a
     *  quiet empty answer — the hunters' "if status is unavailable, return exactly {@code
     *  {"prey": []}}" clause depends on telling the two apart. */
    private static DataSourceResult<Form4Filing> recentForm4Failure(String lastFailure) {
        return DataSourceResult.unavailable(SOURCE, "agora: " + lastFailure);
    }

    private static void mapForm4Rows(JsonNode res, java.util.Collection<Form4Filing> into) {
        for (JsonNode t : res.path("transactions")) {
            try {
                JsonNode date = t.path("transactionDate");
                if (date.isMissingNode() || date.isNull()) continue;   // consumers require a date
                String ticker = t.path("ticker").asString("").toUpperCase();
                if (ticker.isEmpty()) continue;
                into.add(new Form4Filing(
                        ticker,
                        t.path("filerName").asString(""),
                        t.path("filerRole").asString(""),
                        LocalDate.parse(date.asString()),
                        bd(t.path("shares")),
                        bd(t.path("dollarValue")),
                        t.path("code").asString("")));
            } catch (RuntimeException ignored) { /* skip malformed row */ }
        }
    }

    /** Spin-off registrations (forms=10-12B). Ticker may be empty on fresh registrations. */
    public DataSourceResult<SpinoffFiling> searchSpinoffs(LocalDate from, LocalDate to) {
        JsonNode res;
        try {
            res = agora.callTool("search_filings", searchArgs(List.of("10-12B"), from, to));
        } catch (AgoraUnavailableException e) {
            return DataSourceResult.unavailable(SOURCE, "agora: " + e.getMessage());
        }
        List<SpinoffFiling> out = new ArrayList<>();
        for (JsonNode f : res.path("filings")) {
            try {
                String ticker = f.path("ticker").asString("").toUpperCase();
                String company = f.path("company").asString("");
                JsonNode filed = f.path("filedDate");
                if ((ticker.isEmpty() && company.isEmpty()) || filed.isMissingNode() || filed.isNull()) continue;
                String url = f.path("url").asString("");
                out.add(new SpinoffFiling(ticker, company,
                        f.path("form").asString("10-12B"),
                        LocalDate.parse(filed.asString()),
                        url,
                        CikExtractor.fromFilingUrl(url)));   // spin-co registrant CIK; null if unparseable
            } catch (RuntimeException ignored) { /* skip malformed row */ }
        }
        return reportingDegradation(res, out, "spin-off registrations");
    }

    /** Merger deal filings (forms=DEFM14A, SC TO-T). */
    public DataSourceResult<MergerFiling> searchMergers(LocalDate from, LocalDate to) {
        JsonNode res;
        try {
            res = agora.callTool("search_filings", searchArgs(List.of("DEFM14A", "SC TO-T"), from, to));
        } catch (AgoraUnavailableException e) {
            return DataSourceResult.unavailable(SOURCE, "agora: " + e.getMessage());
        }
        List<MergerFiling> out = new ArrayList<>();
        for (JsonNode f : res.path("filings")) {
            try {
                String ticker = f.path("ticker").asString("").toUpperCase();
                String company = f.path("company").asString("");
                JsonNode filed = f.path("filedDate");
                if ((ticker.isEmpty() && company.isEmpty()) || filed.isMissingNode() || filed.isNull()) continue;
                out.add(new MergerFiling(ticker, company,
                        f.path("form").asString("DEFM14A"),
                        LocalDate.parse(filed.asString()),
                        f.path("url").asString("")));
            } catch (RuntimeException ignored) { /* skip malformed row */ }
        }
        return reportingDegradation(res, out, "merger deal filings");
    }

    /**
     * Wraps a mapped market-wide result in health that carries Agora's {@code partial} /
     * {@code truncated} flags, mirroring {@code AgoraEarnings.recent}. The items are kept
     * deliberately — {@link DataSourceResult#unavailable} would drop them, and a cut result is
     * still worth screening. A clean response stays plain {@code healthy} with a null detail, so
     * an untouched payload looks exactly as it did before.
     */
    private static <T> DataSourceResult<T> reportingDegradation(JsonNode res, List<T> out, String what) {
        return degradation(res.path("partial").asBoolean(false),
                res.path("truncated").asBoolean(false), out, what);
    }

    /** {@link #reportingDegradation} over flags already OR-ed across several responses (the
     *  day-sliced Form-4 fetch); same contract, same wording. */
    private static <T> DataSourceResult<T> degradation(boolean partial, boolean truncated,
                                                       List<T> out, String what) {
        if (!partial && !truncated) return DataSourceResult.healthy(SOURCE, out);
        return new DataSourceResult<>(out, DataSourceHealth.degraded(SOURCE,
                (partial ? "partial: " + what + " window not fully covered" : "")
                        + (partial && truncated ? "; " : "")
                        + (truncated ? "truncated: more " + what + " exist than were returned" : ""),
                partial, truncated));
    }

    /** XBRL concept datapoints (us-gaap tag) for a symbol; empty series on any failure. */
    public ConceptSeries concept(String symbol, String tag) {
        try {
            return conceptStrict(symbol, tag);
        } catch (AgoraUnavailableException e) {
            // Same rationale as AgoraCompanyData.news: the empty series is the kept contract,
            // the log line is what makes a source outage distinguishable from "not filed".
            log.warn("agora source unavailable: tool={} subject={} — {}",
                    "get_company_concept", symbol + ":" + tag, e.getMessage());
            return ConceptSeries.empty(tag);
        }
    }

    /** Like {@link #concept} but propagates {@link AgoraUnavailableException} instead of
     *  degrading to an empty series, so batch callers (e.g. the lazarus Altman-Z enrichment)
     *  can tell "Agora/EDGAR is down" apart from "concept not filed" (which still comes back
     *  as an empty {@code datapoints} array) and short-circuit the source for the rest of the
     *  batch rather than burning their latency budget on further dead calls. */
    public ConceptSeries conceptStrict(String symbol, String tag) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol).put("tag", tag);
        return series(tag, agora.callTool("get_company_concept", args).path("datapoints"));
    }

    /** Like {@link #conceptStrict(String, String)} but resolves the company by {@code symbol}
     *  OR {@code cik} — Agora's {@code get_company_concept} accepts a CIK as an alternative to a
     *  ticker, which lets a spin-off's XBRL balance sheet be fetched by its registrant CIK BEFORE
     *  a ticker exists (the pre-distribution REGISTERED stage). Whichever of {@code symbol}/{@code cik}
     *  is non-blank is forwarded (both when both are set); at least one must be non-blank (both
     *  blank throws {@link IllegalArgumentException} — the tool requires an identifier). Propagates
     *  {@link AgoraUnavailableException} for the same batch-guard reason as
     *  {@link #conceptStrict(String, String)}. */
    public ConceptSeries conceptStrict(String symbol, String cik, String tag) {
        boolean hasSymbol = symbol != null && !symbol.isBlank();
        boolean hasCik = cik != null && !cik.isBlank();
        if (!hasSymbol && !hasCik) {
            throw new IllegalArgumentException("conceptStrict requires a non-blank symbol or cik");
        }
        ObjectNode args = mapper.createObjectNode();
        if (hasSymbol) args.put("symbol", symbol);
        if (hasCik) args.put("cik", cik);
        args.put("tag", tag);
        return series(tag, agora.callTool("get_company_concept", args).path("datapoints"));
    }

    /** Bulk XBRL fetch: MANY us-gaap tags for a symbol in ONE cached Agora call
     *  ({@code get_company_facts}), each tag's {@code datapoints} in the exact same shape as
     *  {@link #conceptStrict(String, String)}. Collapses what would otherwise be one remote
     *  call per tag into a single round trip — the lazarus Altman-Z path uses this to fetch
     *  all balance-sheet, flow and revenue-fallback tags at once.
     *
     *  <p>The returned {@link java.util.LinkedHashMap} preserves the requested {@code tags}
     *  order and always carries an entry for EVERY requested tag: a tag the company never filed
     *  comes back as an EMPTY series (present in the map, NOT missing), exactly like a
     *  {@code get_company_concept} call with an empty {@code datapoints} array.
     *
     *  <p>STRICT: {@link AgoraUnavailableException} is deliberately NOT caught here — it
     *  propagates for the same batch-guard reason as {@link #conceptStrict(String, String)},
     *  letting a batch caller tell "Agora/EDGAR is down" apart from "tag not filed" (empty
     *  series) and short-circuit a down source for the rest of the batch. */
    public java.util.Map<String, ConceptSeries> companyFactsStrict(String symbol, List<String> tags) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        ArrayNode ta = args.putArray("tags");
        tags.forEach(ta::add);
        JsonNode facts = agora.callTool("get_company_facts", args).path("facts");
        java.util.Map<String, ConceptSeries> out = new java.util.LinkedHashMap<>();
        for (String tag : tags) {
            out.put(tag, series(tag, facts.path(tag).path("datapoints")));
        }
        return out;
    }

    /** Non-US concept fetch: MANY {@link FundamentalConcept}s for a symbol in ONE Agora call
     *  ({@code get_fundamental_concepts}), each concept's {@code datapoints} in the exact same
     *  point shape ({@code periodStart}/{@code periodEnd}/{@code value}/{@code filed}) as
     *  {@link #companyFactsStrict}, plus the reporting {@code unit} (ISO-4217 currency) the
     *  concept's values are expressed in.
     *
     *  <p>Instant concepts (balance-sheet: assets, liabilities, retained earnings) carry a null
     *  {@code periodStart}; flow concepts (EBIT, revenue) carry both dates (~annual span). A
     *  requested concept the company never filed comes back as an EMPTY series with a null unit
     *  (present in the map, NOT missing) — exactly like {@code companyFactsStrict}'s absent-tag
     *  contract; only a genuine unavailable envelope throws.
     *
     *  <p>STRICT: {@link AgoraUnavailableException} is deliberately NOT caught here — it
     *  propagates for the same batch-guard reason as {@link #conceptStrict(String, String)},
     *  letting the lazarus enrichment tell "Agora is down" apart from "concept not filed" (empty
     *  series) and short-circuit a down source for the rest of the batch. */
    public ConceptSeries.MultiConcept conceptsStrict(String symbol, FundamentalConcept... concepts) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        JsonNode conceptsNode = agora.callTool("get_fundamental_concepts", args).path("concepts");
        java.util.Map<FundamentalConcept, ConceptSeries> series = new java.util.LinkedHashMap<>();
        java.util.Map<FundamentalConcept, String> units = new java.util.LinkedHashMap<>();
        for (FundamentalConcept c : concepts) {
            JsonNode node = conceptsNode.path(c.name());
            series.put(c, series(c.name(), node.path("datapoints")));
            JsonNode unit = node.path("unit");
            units.put(c, (unit.isMissingNode() || unit.isNull()) ? null : unit.asString());
        }
        return new ConceptSeries.MultiConcept(series, units);
    }

    /** Reported EPS datapoints for a symbol; empty series on any failure. */
    public ConceptSeries epsHistory(String symbol) {
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("symbol", symbol);
            res = agora.callTool("get_eps_history", args);
        } catch (AgoraUnavailableException e) {
            log.warn("agora source unavailable: tool={} subject={} — {}",
                    "get_eps_history", symbol, e.getMessage());
            return ConceptSeries.empty("eps");
        }
        return series("eps", res.path("eps"));
    }

    /** Piotroski F-Score for a symbol via get_fundamental_score; unavailable on any failure. */
    public FundamentalScore fundamentalScore(String symbol) {
        try {
            return fundamentalScoreStrict(symbol);
        } catch (AgoraUnavailableException e) {
            log.warn("agora source unavailable: tool={} subject={} — {}",
                    "get_fundamental_score", symbol, e.getMessage());
            return FundamentalScore.unavailable();
        }
    }

    /** Like {@link #fundamentalScore} but propagates {@link AgoraUnavailableException} (same
     *  rationale as {@link #conceptStrict}: lets batch callers — the lazarus enrichment —
     *  short-circuit a down source instead of burning one dead call per candidate). */
    public FundamentalScore fundamentalScoreStrict(String symbol) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        JsonNode res = agora.callTool("get_fundamental_score", args);
        JsonNode p = res.path("scores").path("piotroskiF");
        if (p.isMissingNode() || p.isNull()) return FundamentalScore.unavailable();
        JsonNode cfoGtNi = p.path("criteria").path("cfoExceedsNetIncome");
        JsonNode accrNode = p.path("raw").path("accrualRatio");
        BigDecimal accr = accrNode.isNumber() ? new BigDecimal(accrNode.asString("")) : null;
        return new FundamentalScore(
                p.path("score").asInt(0),
                p.path("criteriaAvailable").asInt(0),
                accr,
                cfoGtNi.path("met").asBoolean(false),
                cfoGtNi.path("available").asBoolean(false),
                true);
    }

    /** Fetch a filing's summary/term-sheet text via Agora's get_filing_text. Fail-soft:
     *  a blank url or any Agora failure yields a not-available {@link FilingText}. The failure
     *  KIND is preserved: Agora refusing an oversized document ({@link
     *  AgoraUnavailableException#filingTooLarge()}) becomes {@link FilingText#tooLarge()}, so the
     *  merger enrichment can report "this one proxy is too big to parse" instead of blaming an
     *  outage that did not happen. */
    public FilingText filingText(String url) {
        if (url == null || url.isBlank()) return FilingText.unavailable();
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("url", url);
            JsonNode res = agora.callTool("get_filing_text", args);
            return new FilingText(res.path("text").asString(""), true);
        } catch (AgoraUnavailableException e) {
            log.warn("agora source unavailable: tool={} subject={} — {}",
                    "get_filing_text", url, e.getMessage());
            return e.filingTooLarge() ? FilingText.tooLarge() : FilingText.unavailable();
        }
    }

    /**
     * Like {@link #filingText(String)} but asks Agora to resolve a named exhibit off the filing's
     * index page (e.g. {@code "EX-99.1"}) and to extract in the given mode (e.g. {@code "LEADING"}).
     * {@code FilingText.resolvedExhibit()} carries back which exhibit Agora actually found, or
     * {@code null} when it fell back to the primary document.
     *
     * <p>Agora resolves the named exhibit off the filing's index page and returns
     * {@code resolved_exhibit} in the tool response; when the exhibit is not present it falls
     * back to the primary document and {@code resolved_exhibit} is read as {@code null} (see
     * {@link FilingText#resolvedExhibit()}).
     */
    public FilingText filingText(String url, String exhibitType, String extractMode) {
        if (url == null || url.isBlank()) return FilingText.unavailable();
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("url", url);
            if (exhibitType != null && !exhibitType.isBlank()) args.put("exhibit_type", exhibitType);
            if (extractMode != null && !extractMode.isBlank()) args.put("extract_mode", extractMode);
            JsonNode res = agora.callTool("get_filing_text", args);
            JsonNode resolved = res.path("resolved_exhibit");
            String resolvedExhibit = resolved.isTextual() ? resolved.asString() : null;
            return new FilingText(res.path("text").asString(""), true, FilingText.Failure.NONE, resolvedExhibit);
        } catch (AgoraUnavailableException e) {
            log.warn("agora source unavailable: tool={} subject={} — {}",
                    "get_filing_text", url, e.getMessage());
            return e.filingTooLarge() ? FilingText.tooLarge() : FilingText.unavailable();
        }
    }

    /** Multi-year Form-4 owner history for one company (Agora {@code get_form4_owner_history}),
     *  grouped per reporting owner, for the routine/opportunistic classification. Propagates
     *  {@link AgoraUnavailableException} (strict, mirroring {@link #conceptStrict} /
     *  {@code AgoraCompanyData.recommendationsStrict}) so the insider enrichment's per-batch
     *  source-down guard can short-circuit a dead source instead of burning one ~16s dead call
     *  per remaining cluster. The wire's tri-state {@code aff10b5One} is mapped to a nullable
     *  {@link Boolean} (null = pre-2023 filing, i.e. unknown, NOT false) and {@code truncated}
     *  is passed through verbatim. Uses the tool's default window (3 years). */
    public Form4OwnerHistory ownerHistoryStrict(String symbol) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        JsonNode res = agora.callTool("get_form4_owner_history", args);

        List<Form4OwnerHistory.Owner> owners = new ArrayList<>();
        for (JsonNode o : res.path("owners")) {
            try {
                List<Form4OwnerHistory.Transaction> txs = new ArrayList<>();
                for (JsonNode t : o.path("transactions")) {
                    try {
                        txs.add(new Form4OwnerHistory.Transaction(
                                date(t.path("transactionDate")),
                                t.path("code").asString(""),
                                t.path("acquiredDisposedCode").asString(""),
                                t.path("form").asString(""),
                                bdOrNull(t.path("shares")),
                                bdOrNull(t.path("price")),
                                bdOrNull(t.path("dollarValue")),
                                bdOrNull(t.path("sharesOwnedFollowing")),
                                triState(t.path("aff10b5One"))));
                    } catch (RuntimeException ignored) { /* skip one malformed transaction row */ }
                }
                owners.add(new Form4OwnerHistory.Owner(
                        o.path("name").asString(""),
                        o.path("cik").asString(""),
                        o.path("role").asString(""),
                        List.copyOf(txs)));
            } catch (RuntimeException ignored) { /* skip one malformed owner */ }
        }
        return new Form4OwnerHistory(
                res.path("cik").asString(""),
                date(res.path("from")), date(res.path("to")),
                List.copyOf(owners),
                res.path("truncated").asBoolean(false));
    }

    private ObjectNode searchArgs(List<String> forms, LocalDate from, LocalDate to) {
        ObjectNode args = mapper.createObjectNode();
        ArrayNode fa = args.putArray("forms");
        forms.forEach(fa::add);
        args.put("from", from.toString()).put("to", to.toString()).put("limit", WINDOW_LIMIT);
        return args;
    }

    private static ConceptSeries series(String tag, JsonNode rows) {
        List<ConceptSeries.Point> points = new ArrayList<>();
        for (JsonNode r : rows) {
            try {
                JsonNode v = r.path("value");
                if (v.isMissingNode() || v.isNull()) continue;
                points.add(new ConceptSeries.Point(
                        date(r.path("periodStart")), date(r.path("periodEnd")),
                        new BigDecimal(v.asString()), date(r.path("filed"))));
            } catch (RuntimeException ignored) { /* skip malformed row */ }
        }
        return new ConceptSeries(tag, List.copyOf(points));
    }

    private static LocalDate date(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asString("");
        return s.isEmpty() ? null : LocalDate.parse(s);
    }

    private static BigDecimal bd(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return BigDecimal.ZERO;
        try { return new BigDecimal(n.asString("0")); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    /** Like {@link #bd} but keeps "absent" distinct from "zero" (null on missing/unparsable). */
    private static BigDecimal bdOrNull(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        try { return new BigDecimal(n.asString("")); } catch (NumberFormatException e) { return null; }
    }

    /** Tri-state boolean: null on missing/null/non-boolean (preserves "unknown" vs "false"). */
    private static Boolean triState(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode() || !n.isBoolean()) return null;
        return n.asBoolean(false);
    }
}
