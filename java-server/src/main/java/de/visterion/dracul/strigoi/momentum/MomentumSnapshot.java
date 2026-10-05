package de.visterion.dracul.strigoi.momentum;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The completion's view of a stored ranking snapshot (spec 2026-10-04 §5.3). Payload layout
 * (written by {@link MomentumRankingService}): {@value #LLM} = the tool answer the LLM saw,
 * {@value #OFFERED} = the 20 offered names with facts, {@value #RANKED_ALL} = every ranked symbol
 * with its rank, {@value #UNIVERSE} = the universe after exclusions, {@value #UNRANKED} = symbol →
 * unranked reason, {@value #RANKED_COUNT}.
 */
public record MomentumSnapshot(String runId, YearMonth month, boolean rebalanceDue, String health,
        int rankedCount, List<Offered> offered, Map<String, Integer> rankBySymbol,
        Set<String> universe, Map<String, String> unranked) {

    public static final String LLM = "llm";
    public static final String OFFERED = "offered";
    public static final String RANKED_ALL = "ranked_all";
    public static final String UNIVERSE = "universe";
    public static final String UNRANKED = "unranked";
    public static final String RANKED_COUNT = "ranked_count";

    public static final String NOT_DUE = "not_due";
    public static final String HEALTHY = "healthy";
    public static final String PARTIAL = "partial";
    public static final String UNAVAILABLE = "unavailable";

    public record Offered(int rank, String symbol, String companyName, BigDecimal momentumPct,
            BigDecimal return1mPct) {}

    /** A due snapshot whose ranking may drive exits and entries. */
    public boolean usable() {
        return rebalanceDue && month != null && (HEALTHY.equals(health) || PARTIAL.equals(health));
    }

    public Integer rankOf(String symbol) {
        return rankBySymbol.get(MomentumBook.norm(symbol));
    }

    public boolean inUniverse(String symbol) {
        return universe.contains(MomentumBook.norm(symbol));
    }

    public static MomentumSnapshot of(MomentumRepository.StoredSnapshot s) {
        JsonNode p = s.payload();
        List<Offered> offered = new ArrayList<>();
        for (JsonNode o : p.path(OFFERED)) {
            offered.add(new Offered(o.path("rank").asInt(), MomentumBook.norm(o.path("symbol").asString("")),
                    o.path("company_name").asString(""), decimal(o.path("momentum_12_1_pct")),
                    decimal(o.path("return_1m_pct"))));
        }
        Map<String, Integer> ranks = new HashMap<>();
        for (JsonNode r : p.path(RANKED_ALL)) {
            ranks.put(MomentumBook.norm(r.path("symbol").asString("")), r.path("rank").asInt());
        }
        Set<String> universe = new HashSet<>();
        for (JsonNode u : p.path(UNIVERSE)) universe.add(MomentumBook.norm(u.asString("")));
        Map<String, String> unranked = new LinkedHashMap<>();
        p.path(UNRANKED).properties().forEach(e ->
                unranked.put(MomentumBook.norm(e.getKey()), e.getValue().asString("")));
        return new MomentumSnapshot(s.runId(), s.month() == null ? null : YearMonth.parse(s.month()),
                s.rebalanceDue(), s.health(), p.path(RANKED_COUNT).asInt(0), List.copyOf(offered),
                Collections.unmodifiableMap(ranks), Collections.unmodifiableSet(universe),
                Collections.unmodifiableMap(unranked));
    }

    /** Symbols that were in the universe without a rank in {@code payload} (carry counter). */
    public static Set<String> unrankedSymbols(JsonNode payload) {
        Set<String> out = new HashSet<>();
        payload.path(UNRANKED).properties().forEach(e -> out.add(MomentumBook.norm(e.getKey())));
        return out;
    }

    private static BigDecimal decimal(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return null;
        try {
            return new BigDecimal(v.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
