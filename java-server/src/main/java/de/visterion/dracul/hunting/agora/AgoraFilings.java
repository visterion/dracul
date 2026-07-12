package de.visterion.dracul.hunting.agora;

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

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraFilings(AgoraClient agora) { this.agora = agora; }

    /** Market-wide Form-4 transactions in [from, to]; callers filter by ticker client-side. */
    public DataSourceResult<Form4Filing> recentForm4(LocalDate from, LocalDate to) {
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("from", from.toString()).put("to", to.toString());
            res = agora.callTool("get_form4_transactions", args);
        } catch (AgoraUnavailableException e) {
            return DataSourceResult.unavailable(SOURCE, "agora: " + e.getMessage());
        }
        List<Form4Filing> out = new ArrayList<>();
        for (JsonNode t : res.path("transactions")) {
            try {
                JsonNode date = t.path("transactionDate");
                if (date.isMissingNode() || date.isNull()) continue;   // consumers require a date
                String ticker = t.path("ticker").asString("").toUpperCase();
                if (ticker.isEmpty()) continue;
                out.add(new Form4Filing(
                        ticker,
                        t.path("filerName").asString(""),
                        t.path("filerRole").asString(""),
                        LocalDate.parse(date.asString()),
                        bd(t.path("shares")),
                        bd(t.path("dollarValue")),
                        t.path("code").asString("")));
            } catch (RuntimeException ignored) { /* skip malformed row */ }
        }
        return DataSourceResult.healthy(SOURCE, out);
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
        return DataSourceResult.healthy(SOURCE, out);
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
        return DataSourceResult.healthy(SOURCE, out);
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
     *  a blank url or any Agora failure yields {@link FilingText#unavailable()}. */
    public FilingText filingText(String url) {
        if (url == null || url.isBlank()) return FilingText.unavailable();
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("url", url);
            JsonNode res = agora.callTool("get_filing_text", args);
            return new FilingText(res.path("text").asString(""), true);
        } catch (AgoraUnavailableException e) {
            return FilingText.unavailable();
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
        args.put("from", from.toString()).put("to", to.toString());
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
