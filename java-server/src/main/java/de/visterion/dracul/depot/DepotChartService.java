package de.visterion.dracul.depot;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Chart data for the GUI: raw instrument OHLC/intraday series ({@link #instrumentChart}), and a
 * composed depot performance curve ({@link #depotCurve}) — {@code value(t) = Σ qty_i × close_i(t)
 * + cash} — built from per-symbol close series aligned by date/time INTERSECTION (a date missing
 * for any successfully-fetched symbol is dropped from the curve entirely). A symbol whose series
 * can't be fetched (Agora failure) is skipped — the curve is still built from the rest — and
 * flips {@link DepotCurve#partial()} to {@code true}.
 *
 * <p>Range → lookback mapping: {@code 1d} uses {@code get_intraday} (5m bars over the current
 * day); {@code 1w/1m/1y/max} use {@code get_ohlc} with {@code days = 7/31/365/1825}. Any other
 * range value is a {@code 400 BAD_REQUEST}.
 */
@Service
public class DepotChartService {

    private static final Logger log = LoggerFactory.getLogger(DepotChartService.class);
    private static final Map<String, Integer> RANGE_DAYS = Map.of(
            "1w", 7, "1m", 31, "1y", 365, "max", 1825);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int SCALE = 2;

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public DepotChartService(AgoraClient agora) {
        this.agora = agora;
    }

    /** One chart point: {@code t} is an ISO date ({@code get_ohlc}) or ISO instant ({@code get_intraday}). */
    public record ChartPoint(String t, BigDecimal value) {
    }

    /** One relative-performance point: {@code pct} is {@code (value/first − 1) × 100}, scale 2 HALF_UP. */
    public record RelativePoint(String t, BigDecimal pct) {
    }

    public record InstrumentChart(String symbol, String range, List<ChartPoint> points) {
    }

    public record DepotCurve(List<ChartPoint> points, List<RelativePoint> relative, boolean partial) {
    }

    /** Raw close series for one instrument over {@code range}. Throws 400 for an invalid range. */
    public InstrumentChart instrumentChart(String symbol, String range) {
        List<ChartPoint> points = fetchSeries(symbol, range);
        return new InstrumentChart(symbol, range, points);
    }

    /**
     * Composed depot performance curve for {@code positions} (already gated/resolved by the
     * caller) plus {@code cash}. Positions without a symbol or qty are ignored.
     */
    public DepotCurve depotCurve(String range, List<DepotPosition> positions, BigDecimal cash) {
        validateRange(range);
        BigDecimal safeCash = cash != null ? cash : BigDecimal.ZERO;

        List<DepotPosition> withQty = positions.stream()
                .filter(p -> p.symbol() != null && p.qty() != null)
                .toList();

        boolean partial = false;
        Map<String, TreeMap<String, BigDecimal>> perSymbol = new LinkedHashMap<>();
        for (DepotPosition p : withQty) {
            try {
                TreeMap<String, BigDecimal> closes = new TreeMap<>();
                for (ChartPoint pt : fetchSeries(p.symbol(), range)) {
                    closes.put(pt.t(), pt.value());
                }
                perSymbol.put(p.symbol(), closes);
            } catch (AgoraUnavailableException e) {
                log.warn("depot chart: skipping symbol {} ({})", p.symbol(), e.toString());
                partial = true;
            }
        }

        if (perSymbol.isEmpty()) {
            return new DepotCurve(List.of(), List.of(), partial);
        }

        Set<String> commonDates = null;
        for (TreeMap<String, BigDecimal> closes : perSymbol.values()) {
            if (commonDates == null) {
                commonDates = new TreeSet<>(closes.keySet());
            } else {
                commonDates.retainAll(closes.keySet());
            }
        }

        List<ChartPoint> points = new ArrayList<>();
        for (String t : commonDates) {
            BigDecimal value = safeCash;
            for (DepotPosition p : withQty) {
                TreeMap<String, BigDecimal> closes = perSymbol.get(p.symbol());
                if (closes == null) continue;
                BigDecimal close = closes.get(t);
                if (close == null) continue;
                value = value.add(p.qty().multiply(close));
            }
            points.add(new ChartPoint(t, value.setScale(SCALE, RoundingMode.HALF_UP)));
        }

        List<RelativePoint> relative = new ArrayList<>();
        if (!points.isEmpty()) {
            BigDecimal first = points.get(0).value();
            for (ChartPoint pt : points) {
                BigDecimal pct = first.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP)
                        : pt.value().divide(first, 10, RoundingMode.HALF_UP)
                                .subtract(BigDecimal.ONE)
                                .multiply(HUNDRED)
                                .setScale(SCALE, RoundingMode.HALF_UP);
                relative.add(new RelativePoint(pt.t(), pct));
            }
        }

        return new DepotCurve(points, relative, partial);
    }

    private List<ChartPoint> fetchSeries(String symbol, String range) {
        if ("1d".equals(range)) return fetchIntraday(symbol);
        Integer days = RANGE_DAYS.get(range);
        if (days == null) throw invalidRange(range);
        return fetchOhlc(symbol, days);
    }

    private List<ChartPoint> fetchOhlc(String symbol, int days) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol).put("days", days);
        JsonNode res = agora.callTool("get_ohlc", args);
        List<ChartPoint> points = new ArrayList<>();
        for (JsonNode b : res.path("bars")) {
            String date = textOrNull(b, "date");
            BigDecimal close = decimalOrNull(b, "close");
            if (date == null || close == null) continue;
            points.add(new ChartPoint(date, close));
        }
        return points;
    }

    private List<ChartPoint> fetchIntraday(String symbol) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol).put("interval", "5m").put("range", "1d");
        JsonNode res = agora.callTool("get_intraday", args);
        List<ChartPoint> points = new ArrayList<>();
        for (JsonNode b : res.path("bars")) {
            String time = textOrNull(b, "time");
            BigDecimal close = decimalOrNull(b, "close");
            if (time == null || close == null) continue;
            points.add(new ChartPoint(time, close));
        }
        return points;
    }

    private void validateRange(String range) {
        if (!"1d".equals(range) && !RANGE_DAYS.containsKey(range)) throw invalidRange(range);
    }

    private ResponseStatusException invalidRange(String range) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid range: " + range);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        try {
            return new BigDecimal(v.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
