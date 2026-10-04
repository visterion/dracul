package de.visterion.dracul.strigoi.tech;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import de.visterion.dracul.hunting.agora.RecommendationTrend;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.InstrumentSearchHit;
import de.visterion.dracul.marketdata.InstrumentSearchService;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.strigoi.lazarus.BasicFinancials;
import de.visterion.dracul.strigoi.lazarus.BasicFinancialsExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code check_tech_candidate} (spec 2026-10-03 §4.2): one object per symbol with profile, quote,
 * technicals, fundamentals summary, analyst estimates, recent headlines and the code verdict.
 * Every Agora read is contained: a failing section is null in the payload, and a field the
 * eligibility rules need turns into {@code data_unavailable:<field>}.
 */
@Component
@ConditionalOnProperty(value = "dracul.strigoi.tech.enabled", havingValue = "true")
public class TechCandidateService {

    private static final Logger log = LoggerFactory.getLogger(TechCandidateService.class);
    private static final int NEWS_LOOKBACK_DAYS = 14;
    private static final int NEWS_MAX = 10;

    /** {@code sourceUnavailable}: the profile AND the quote reads both failed with an Agora
     *  outage (SOURCE scope; an error about this one symbol does not count) — the verdict is then "data_unavailable" for a reason that has nothing to do with
     *  the symbol, and the tool must say so in {@code data_source_health} (a total outage must
     *  never look like a night of ineligible names). */
    /** News of one book position; {@code available=false} = the news read failed (any scope —
     *  either way nothing can be said about a catastrophe for this position tonight). */
    public record NewsRead(ArrayNode items, boolean available) {
    }

    public record Candidate(ObjectNode payload, TechEligibility.Verdict verdict,
            boolean sourceUnavailable) {
    }

    /** What the eligibility rules read; null = the source did not answer. {@code profileFailed}
     *  / {@code quoteFailed} distinguish "Agora did not answer" from "no data for this symbol". */
    record Facts(String instrumentType, String quoteCurrency, BigDecimal price, JsonNode profile,
            boolean profileFailed, boolean quoteFailed) {
    }

    /** Quote row, or null; {@code failed} = Agora was unreachable (SOURCE scope), not an error
     *  envelope about this one symbol (REQUEST scope — e.g. an unknown ticker). */
    private record QuoteRead(JsonNode row, boolean failed) {
    }

    private final AgoraClient agora;
    private final AgoraCompanyData companyData;
    private final InstrumentSearchService search;
    private final TechSettings settings;
    private final ObjectMapper mapper = new ObjectMapper();

    public TechCandidateService(AgoraClient agora, AgoraCompanyData companyData,
            InstrumentSearchService search, TechSettings settings) {
        this.agora = agora;
        this.companyData = companyData;
        this.search = search;
        this.settings = settings;
    }

    public Candidate check(String symbol, TechBookService.Snapshot book) {
        Facts f = facts(symbol);
        TechEligibility.Verdict v = verdict(symbol, f, book);
        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        ObjectNode profile = out.putObject("profile");
        JsonNode p = f.profile();
        profile.put("name", text(p, "name"));
        profile.put("industry", text(p, "finnhubIndustry"));
        profile.put("market_cap_millions", decimal(p, "marketCapitalization"));
        profile.put("currency", text(p, "currency"));
        profile.put("exchange", text(p, "exchange"));
        profile.put("listing_ticker", text(p, "ticker"));
        profile.put("type", f.instrumentType());
        ObjectNode quote = out.putObject("quote");
        quote.put("price", f.price());
        quote.put("currency", f.quoteCurrency());
        out.set("technicals", technicals(symbol));
        BasicFinancials bf = fundamentals(symbol);
        out.set("fundamentals", bf == null ? mapper.nullNode() : mapper.valueToTree(bf));
        out.set("analyst_estimates", analyst(symbol));
        out.set("news", news(symbol, NEWS_LOOKBACK_DAYS, NEWS_MAX));
        out.put("eligible", v.eligible());
        ArrayNode reasons = out.putArray("reasons");
        v.reasons().forEach(reasons::add);
        ArrayNode notes = out.putArray("notes");
        v.notes().forEach(notes::add);
        return new Candidate(out, v, f.profileFailed() && f.quoteFailed());
    }

    /** The lean re-validation the completion controller runs on every pick. */
    public TechEligibility.Verdict eligibility(String symbol, TechBookService.Snapshot book) {
        return verdict(symbol, facts(symbol), book);
    }

    TechEligibility.Verdict verdict(String symbol, Facts f, TechBookService.Snapshot book) {
        return TechEligibility.evaluate(new TechEligibility.Inputs(symbol, f.instrumentType(),
                        f.quoteCurrency(), text(f.profile(), "ticker"),
                        decimal(f.profile(), "marketCapitalization"), book.held(symbol),
                        book.pending(symbol), book.recentlyExited(symbol)),
                settings.minMarketCapUsdMillions(), settings.instrumentCurrency());
    }

    Facts facts(String symbol) {
        JsonNode profile;
        boolean profileFailed = false;
        try {
            profile = companyData.profileStrict(symbol);
        } catch (RuntimeException e) {
            logSwallowed(e, "get_company_profile", symbol);
            profile = null;
            profileFailed = sourceOutage(e);
        }
        // search_instruments ignores queries shorter than 2 characters (InstrumentSearchService),
        // so a 1-letter ticker never resolves a type and is data_unavailable:instrument_type —
        // accepted (documented in documentation/strigoi.md).
        String type = null;
        try {
            for (InstrumentSearchHit hit : search.search(symbol, 10)) {
                if (symbol.equalsIgnoreCase(hit.symbol())) {
                    type = hit.type();
                    break;
                }
            }
        } catch (MarketDataException e) {
            logSwallowed(e, "search_instruments", symbol);
        }
        QuoteRead qr = quote(symbol);
        JsonNode q = qr.row();
        return new Facts(type, q == null ? null : text(q, "currency"),
                q == null ? null : decimal(q, "price"), profile, profileFailed, qr.failed());
    }

    /** Last prices of {@code symbols} in one get_quote call; empty on failure. */
    public Map<String, BigDecimal> lastPrices(Collection<String> symbols) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (symbols.isEmpty()) return out;
        ObjectNode args = mapper.createObjectNode();
        ArrayNode arr = args.putArray("symbols");
        symbols.forEach(arr::add);
        try {
            for (JsonNode q : agora.callTool("get_quote", args).path("quotes")) {
                String s = text(q, "symbol");
                BigDecimal price = decimal(q, "price");
                if (s != null && price != null) out.put(s, price);
            }
        } catch (AgoraUnavailableException e) {
            logSwallowed(e, "get_quote", String.join(",", symbols));
        }
        return out;
    }

    /** Newest-first headlines over {@code lookbackDays}, at most {@code max}; an outage is an
     *  empty list (the candidate check reports source health via profile + quote instead). */
    public ArrayNode news(String symbol, int lookbackDays, int max) {
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        return headlines(companyData.news(symbol, to.minusDays(lookbackDays), to), max);
    }

    /** The book's per-position news: unlike {@link #news} an outage is reported, not swallowed —
     *  "no headlines" and "headlines could not be read" mean opposite things for the catastrophe
     *  check. Logged under the stable scope-chosen prefix. */
    public NewsRead bookNews(String symbol, int lookbackDays, int max) {
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        try {
            return new NewsRead(headlines(companyData.newsStrict(symbol, to.minusDays(lookbackDays), to),
                    max), true);
        } catch (AgoraUnavailableException e) {
            logSwallowed(e, "get_company_news", symbol);
            return new NewsRead(mapper.createArrayNode(), false);
        }
    }

    private ArrayNode headlines(List<NewsHeadline> items, int max) {
        ArrayNode arr = mapper.createArrayNode();
        items.stream()
                .sorted(Comparator.comparing(NewsHeadline::datetime,
                        Comparator.nullsLast(Comparator.<java.time.Instant>reverseOrder())))
                .limit(max)
                .forEach(n -> {
                    ObjectNode o = arr.addObject();
                    o.put("headline", n.headline());
                    o.put("source", n.source());
                    o.put("datetime", n.datetime() == null ? null : n.datetime().toString());
                    o.put("url", n.url());
                });
        return arr;
    }

    private QuoteRead quote(String symbol) {
        ObjectNode args = mapper.createObjectNode();
        args.putArray("symbols").add(symbol);
        try {
            JsonNode quotes = agora.callTool("get_quote", args).path("quotes");
            return new QuoteRead(quotes.isArray() && !quotes.isEmpty() ? quotes.get(0) : null, false);
        } catch (AgoraUnavailableException e) {
            logSwallowed(e, "get_quote", symbol);
            return new QuoteRead(null, sourceOutage(e));
        }
    }

    /** Same indicator-spec request as {@code gropar.AgoraResearch#exitTa} (minus the chandelier
     *  stop), rebuilt here because AgoraResearch is gated on {@code dracul.gropar.enabled} and
     *  cannot be injected into a hunter that runs without gropar. Keep the two in step. */
    private JsonNode technicals(String symbol) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        args.put("fetchDays", 400);
        ArrayNode indicators = args.putArray("indicators");
        ObjectNode atr = indicators.addObject();
        atr.put("name", "atr");
        atr.putObject("params").put("period", 22);
        ObjectNode ma50 = indicators.addObject();
        ma50.put("name", "sma");
        ma50.putObject("params").put("period", 50);
        ma50.put("label", "ma50");
        ObjectNode ma200 = indicators.addObject();
        ma200.put("name", "sma");
        ma200.putObject("params").put("period", 200);
        ma200.put("label", "ma200");
        ObjectNode range = indicators.addObject();
        range.put("name", "52w_range");
        range.putObject("params").put("minBars", 200);
        try {
            JsonNode r = agora.callTool("get_indicators", args);
            ObjectNode out = mapper.createObjectNode();
            out.put("current_close", decimal(r, "currentClose"));
            for (JsonNode v : r.path("values")) {
                if (!v.path("available").asBoolean(false)) continue;
                String label = v.path("label").asString("");
                switch (label) {
                    case "atr" -> out.put("atr", decimal(v, "value"));
                    case "ma50" -> out.put("ma50", decimal(v, "value"));
                    case "ma200" -> out.put("ma200", decimal(v, "value"));
                    case "52w_range" -> {
                        out.put("high_52w", decimal(v.path("value"), "high"));
                        out.put("low_52w", decimal(v.path("value"), "low"));
                    }
                    default -> { }
                }
            }
            return out;
        } catch (AgoraUnavailableException e) {
            logSwallowed(e, "get_indicators", symbol);
            return mapper.nullNode();
        }
    }

    private BasicFinancials fundamentals(String symbol) {
        JsonNode metrics = companyData.fundamentals(symbol);
        return metrics == null ? null : BasicFinancialsExtractor.extract(metrics);
    }

    private JsonNode analyst(String symbol) {
        List<RecommendationTrend> trends = companyData.recommendations(symbol);
        return trends.isEmpty() ? mapper.nullNode() : mapper.valueToTree(trends.getFirst());
    }

    /** True only for a SOURCE-scoped Agora failure — evidence that Agora is down, not that this
     *  one symbol is unknown. Anything that is not an AgoraUnavailableException is not evidence
     *  about the source either. */
    private static boolean sourceOutage(RuntimeException e) {
        AgoraUnavailableException a = AgoraUnavailableException.unwrap(e);
        return a != null && a.scope() == AgoraUnavailableException.Scope.SOURCE;
    }

    /** The stable swallow-site WARN contract (prefix chosen by scope, as in AgoraCompanyData):
     *  {@code agora source unavailable:} = Agora never answered, {@code agora request failed:} =
     *  an error about this one request. A non-Agora exception (a MarketDataException without an
     *  Agora cause, an unexpected runtime fault) is logged as a request failure. */
    private static void logSwallowed(RuntimeException e, String tool, String subject) {
        String prefix = sourceOutage(e) ? "agora source unavailable" : "agora request failed";
        log.warn("{}: tool={} subject={} — {}", prefix, tool, subject, e.getMessage());
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asString("");
        return s.isBlank() ? null : s;
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        try {
            return new BigDecimal(v.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
