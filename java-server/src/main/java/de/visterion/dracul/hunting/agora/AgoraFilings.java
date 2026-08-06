package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.DataSourceHealth;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.strigoi.lazarus.FundamentalScore;
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

    private static final String SOURCE = "agora";

    /** {@code get_form4_transactions} and {@code search_filings} both default to 100 rows, and a
     *  market-wide form/date window holds far more than that — several THOUSAND Form-4 filings per
     *  day. Taking the default silently turns a market-wide scan into an arbitrary slice of itself:
     *  a 20-day and a 90-day merger window returned the identical newest 25 candidates in
     *  production. Ask for the tool's maximum instead (Agora {@code MAX_LIMIT} on both tools). */
    private static final int WINDOW_LIMIT = 1000;

    /**
     * How many day-sized {@code get_form4_transactions} calls ONE {@link #recentForm4} may issue.
     *
     * <p>Day-slicing made the cost LINEAR in the caller's window, and the insider fetch tool's
     * input schema permits {@code lookback_days} up to 30 — 31 sequential calls at up to 45 s each
     * is ~1395 s inside a single tool call, against Vistierie's 1800 s {@code max_run_seconds} for
     * the WHOLE run. So the slice count is capped rather than the schema narrowed: the caller keeps
     * its contract and an answer that covered fewer days than asked says so via
     * {@code truncated=true} instead of looking complete.
     *
     * <p>The arithmetic that fixes the number, from the outside in:
     * <pre>
     *   1800 s  Vistierie max_run_seconds for strigoi-insider (the whole run: fetch + reasoning)
     *    600 s  InsiderDefaults.FETCH_TIMEOUT_SECONDS — a third of the run budget, so the fetch
     *           cannot starve the reasoning turns even at its worst case
     *    450 s  10 slices x 45 s (dracul.agora.tool-timeout-ms[get_form4_transactions]) — the
     *           worst case; the 150 s left inside the webhook timeout is not spare, it is where
     *           InsiderEnrichmentService's own calls (same request) live
     * </pre>
     * Pinned by {@code InsiderToolTimeoutBudgetTest}: {@code MAX_WINDOW_SLICES x the CONFIGURED
     * Agora budget} must stay below {@code FETCH_TIMEOUT_SECONDS}, so the three numbers cannot
     * drift apart. Ten days covers the hunter's default lookback — which is EIGHT slices, not
     * seven: {@code StrigoiInsiderWebhookController} asks for {@code to.minusDays(lookback)..to},
     * an inclusive window — and matches the 10-day late-filing pad Agora searches per slice.
     */
    public static final int MAX_WINDOW_SLICES = 10;

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraFilings(AgoraClient agora) { this.agora = agora; }

    /**
     * Market-wide Form-4 transactions in [from, to]; callers filter by ticker client-side.
     *
     * <p>Sliced into ONE Agora call PER DAY and merged. One call for a whole week does not fit
     * Agora's budget: it reads one EDGAR archive document per filing under a 30 s aggregate
     * deadline at ~110 ms spacing, i.e. <b>~272 filings per call</b> (derived from Agora's own
     * pacing arithmetic since its BUG-S1a rate-limiter fix; before it, ~159). A market-wide week
     * holds ~1,697 Form-4 filings and a market-wide DAY ~243 (measured live 2026-08-04 on window
     * 2026-07-20..07-27), so a week-sized call read 139 of them — 51 open-market buys, 6 above
     * 500k USD, zero tickers with three filers — and the insider hunter's cluster threshold never
     * fired: {@code items=0 (partial=false truncated=true status=healthy)}, every round.
     *
     * <p><b>That a day-sized call COVERS its day is derived, not measured.</b> Both the ~272 per
     * call and the ~243 per day come from arithmetic (Agora's pacing constants; the weekly hit
     * count divided by seven), and Agora is separately being fixed for a pad defect that makes a
     * 1-day slice read the wrong late filings. Treat "a day fits" as the design intent until a
     * prod run reports a measured figure; what is certain is that a day-sized window is an order
     * of magnitude closer to the budget than a week-sized one.
     *
     * <p>The cut is still reported honestly: EVERY slice's {@code partial}/{@code truncated} is
     * OR-ed into the merged health, so one cut day marks the whole week truncated. A day whose
     * call throws keeps the other days but likewise marks the result truncated (that day IS
     * missing data); only ALL days failing degrades to {@code unavailable} — see
     * {@link #recentForm4Failure}.
     *
     * <p>Cost: the per-CALL Agora budget ({@code dracul.agora.tool-timeout-ms
     * [get_form4_transactions]}, 45 s) is unchanged, but the fetch endpoint's TOTAL wall clock is
     * now LINEAR in the window — which is why at most {@link #MAX_WINDOW_SLICES} days are fetched
     * (see there for the 1800/600/450 s arithmetic). A longer window keeps its NEWEST
     * {@code MAX_WINDOW_SLICES} days and is reported {@code truncated=true}.
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
     * at most {@code maxSlices} day-sized Agora calls, keeping the newest {@code maxSlices} days
     * of the window and reporting {@code truncated=true} when the rest went unread.
     *
     * <p>Why this is a parameter and not one shared constant: the two callers are on different
     * clocks. {@code StrigoiInsiderWebhookController} is a NIGHTLY market-wide scan inside a 600 s
     * webhook timeout and wants breadth; {@code DaywalkerEventEngine} is an INTRADAY trigger
     * engine whose whole poll — positions, watchlist, this fetch, and every per-symbol detector —
     * lives inside a 60 s budget (`dracul.daywalker.poll-budget-ms`) and only wants TODAY's
     * filings. Before this parameter existed, daywalker inherited the hunter's budget and any
     * poll whose window spanned two UTC dates (the first poll of a trading day; four dates after
     * a weekend) issued 2-4 sequential ~33 s calls, blew {@code planFuture.get(60 s)} and logged
     * "skipping all symbols this poll" — a SILENT zero-trigger poll, the worst failure class this
     * codebase has. A slice budget stated at the call site makes the cost bounded by
     * construction rather than by how the window happens to fall.
     *
     * <p>{@code maxSlices} below 1 is clamped to 1: no caller may turn this into a zero-call
     * "clean empty" answer.
     */
    public DataSourceResult<Form4Filing> recentForm4(LocalDate from, LocalDate to, int maxSlices) {
        int sliceBudget = Math.max(1, maxSlices);
        // Insertion-ordered SET, not a list: the merged answer must be deterministic AND
        // duplicate-free. See the duplicate analysis on recentForm4Slice.
        java.util.LinkedHashSet<Form4Filing> merged = new java.util.LinkedHashSet<>();
        boolean partial = false;
        boolean truncated = false;
        boolean anySliceSucceeded = false;
        String lastFailure = null;

        // An inverted window is not sliced (the loop would issue zero calls and report a clean
        // empty answer); it is forwarded verbatim and Agora decides what it means.
        LocalDate lastDay = to.isBefore(from) ? from : to;
        // Over the cap the OLDEST days are dropped, not the newest: the hunter looks for RECENT
        // insider clusters, so the days nearest `to` are the ones its threshold can still fire on,
        // and Agora's own cut inside a slice already works this way (EFTS returns file_date
        // descending, so a deadline cut drops the oldest filings of the range). Dropping newest
        // instead would hand the hunter a stale window while its freshest signal went unread.
        LocalDate firstDay = from;
        if (from.plusDays(sliceBudget - 1L).isBefore(lastDay)) {
            firstDay = lastDay.minusDays(sliceBudget - 1L);
            truncated = true;   // days the caller asked for that were never looked at
        }
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            JsonNode res;
            try {
                res = recentForm4Slice(day, day.isBefore(to) ? day : to);
            } catch (AgoraUnavailableException e) {
                lastFailure = e.getMessage();
                truncated = true;   // this day's filings are missing — never report that as clean
                continue;
            }
            anySliceSucceeded = true;
            partial |= res.path("partial").asBoolean(false);
            truncated |= res.path("truncated").asBoolean(false);
            mapForm4Rows(res, merged);
        }
        if (!anySliceSucceeded) return recentForm4Failure(lastFailure);
        return degradation(partial, truncated, List.copyOf(merged), "Form-4 transactions");
    }

    /**
     * One day-sized {@code get_form4_transactions} call.
     *
     * <p>On the late-filing pad and why slicing is still safe. Agora's {@code fetchForm4} runs TWO
     * EFTS searches per call: the caller's exact filing-date window, then
     * {@code FORM4_LATE_FILING_PAD_DAYS} = 10 filing-days after it, for trades made inside the
     * window but filed after it closed. With one call per day those pads overlap the following
     * slices' own windows, so:
     * <ul>
     *   <li>(a) YES, the same accession is fetched by more than one call — day D's pad covers
     *       filing dates D+1..D+10, which are day D+1..D+10's own windows. That is duplicated
     *       archive-GET budget, and it is the price of slicing: the pad exists so that a day's
     *       LATE filings are seen at all, and a day whose late filings are never read loses
     *       exactly the transactions the cluster screen is looking for. It costs nothing in the
     *       common case, because a slice's hits are ordered window-first and a market-wide day
     *       (~243 filings) already consumes most of the ~272-filing deadline before the pad hits
     *       are reached.</li>
     *   <li>(b) NO, it cannot produce duplicate TRANSACTIONS. Agora filters every parsed
     *       transaction on its {@code transactionDate} against the CALLER's window
     *       ({@code parseForm4}: skip if {@code txDate} is before {@code from} or after
     *       {@code to}), so the day-D call can only ever emit transactions dated D — whether the
     *       filing was found by its window search or by its pad. The slices' outputs are disjoint
     *       by construction, which is why they may simply be concatenated.</li>
     *   <li>(c) Handling: rely on the transaction-date filter, but do not TRUST it — collect into
     *       a {@link java.util.LinkedHashSet}. A duplicated transaction would add a second filer
     *       row to a ticker and manufacture a cluster that never happened, which is far worse
     *       than dropping a byte-identical row. The dedup key is the whole {@link Form4Filing}
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
            return ConceptSeries.empty("eps");
        }
        return series("eps", res.path("eps"));
    }

    /** Piotroski F-Score for a symbol via get_fundamental_score; unavailable on any failure. */
    public FundamentalScore fundamentalScore(String symbol) {
        try {
            return fundamentalScoreStrict(symbol);
        } catch (AgoraUnavailableException e) {
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
