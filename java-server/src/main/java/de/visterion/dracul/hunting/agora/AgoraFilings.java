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
 * get_company_concept / get_eps_history over MCP). Fetch + map to neutral Dracul DTOs only;
 * all interpretation stays in the strigoi helpers. Never throws: Agora failure degrades to
 * an unavailable DataSourceResult or an empty ConceptSeries.
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
                out.add(new SpinoffFiling(ticker, company,
                        f.path("form").asString("10-12B"),
                        LocalDate.parse(filed.asString()),
                        f.path("url").asString("")));
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
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("symbol", symbol).put("tag", tag);
            res = agora.callTool("get_company_concept", args);
        } catch (AgoraUnavailableException e) {
            return ConceptSeries.empty(tag);
        }
        return series(tag, res.path("datapoints"));
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
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("symbol", symbol);
            res = agora.callTool("get_fundamental_score", args);
        } catch (AgoraUnavailableException e) {
            return FundamentalScore.unavailable();
        }
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
                        new BigDecimal(v.asString())));
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
}
