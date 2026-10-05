package de.visterion.dracul.strigoi.momentum;

import de.visterion.dracul.marketdata.AgoraClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code get_indicators_batch} call for a chunk of symbols (spec 2026-10-04 §5.2, §8 V1):
 * lowercase {@code roc} with period {@code rocPeriod} (label {@code roc<period>}) and period 1
 * (label {@code roc1}), {@code series} trailing values (oldest → newest, percent, JSON null for a
 * NaN bar), {@code fetchDays} of history. Every requested symbol is a key of the result — a
 * symbol the batch did not answer is {@link Status#NO_DATA}, never silently absent. An outage is
 * NOT swallowed: {@link de.visterion.dracul.marketdata.AgoraUnavailableException} reaches the
 * caller, which decides by its scope.
 */
@Component
@ConditionalOnProperty(value = "dracul.strigoi.momentum.enabled", havingValue = "true")
public class MomentumBarsClient {

    public enum Status { OK, NO_DATA, INSUFFICIENT }

    public record Series(String symbol, Status status, BigDecimal lastClose, LocalDate asOf,
            List<BigDecimal> rocLong, List<BigDecimal> roc1) {}

    static final String ROC1 = "roc1";

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public MomentumBarsClient(AgoraClient agora) {
        this.agora = agora;
    }

    static String rocLabel(int period) {
        return "roc" + period;
    }

    public Map<String, Series> fetch(List<String> symbols, int rocPeriod, int series, int fetchDays) {
        ObjectNode args = mapper.createObjectNode();
        ArrayNode syms = args.putArray("symbols");
        symbols.forEach(syms::add);
        ArrayNode indicators = args.putArray("indicators");
        ObjectNode roc = indicators.addObject();
        roc.put("name", "roc");
        roc.putObject("params").put("period", rocPeriod);
        roc.put("label", rocLabel(rocPeriod));
        ObjectNode one = indicators.addObject();
        one.put("name", "roc");
        one.putObject("params").put("period", 1);
        one.put("label", ROC1);
        args.put("series", series);
        args.put("fetchDays", fetchDays);

        JsonNode res = agora.callTool("get_indicators_batch", args);
        Map<String, JsonNode> bySymbol = new HashMap<>();
        for (JsonNode entry : res.path("results")) {
            String s = entry.path("symbol").asString("");
            if (!s.isEmpty()) bySymbol.put(s, entry);
        }
        Map<String, Series> out = new LinkedHashMap<>();
        for (String s : symbols) out.put(s, classify(s, bySymbol.get(s), rocLabel(rocPeriod)));
        return out;
    }

    static Series classify(String symbol, JsonNode entry, String longLabel) {
        if (entry == null) return noData(symbol);
        JsonNode longValue = null;
        JsonNode oneValue = null;
        for (JsonNode v : entry.path("values")) {
            String label = v.path("label").asString("");
            if (longLabel.equals(label)) longValue = v;
            else if (ROC1.equals(label)) oneValue = v;
        }
        BigDecimal close = decimal(entry.path("lastCompletedClose"));
        if (close == null) close = decimal(entry.path("currentClose"));
        LocalDate asOf = date(entry.path("asOf"));
        // roc1 needs two bars: without it there is no usable history at all.
        if (oneValue == null || !oneValue.path("available").asBoolean(false) || close == null) {
            return noData(symbol);
        }
        List<BigDecimal> roc1 = list(oneValue.path("series"));
        if (longValue == null || !longValue.path("available").asBoolean(false)) {
            return new Series(symbol, Status.INSUFFICIENT, close, asOf, List.of(), roc1);
        }
        return new Series(symbol, Status.OK, close, asOf, list(longValue.path("series")), roc1);
    }

    private static Series noData(String symbol) {
        return new Series(symbol, Status.NO_DATA, null, null, List.of(), List.of());
    }

    private static List<BigDecimal> list(JsonNode arr) {
        List<BigDecimal> out = new ArrayList<>();
        for (JsonNode v : arr) out.add(decimal(v));
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

    private static LocalDate date(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return null;
        try {
            return LocalDate.parse(v.asString().substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
