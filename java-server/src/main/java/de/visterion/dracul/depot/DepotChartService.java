package de.visterion.dracul.depot;

import de.visterion.dracul.marketdata.AgoraClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chart data for the GUI: raw instrument OHLC/intraday series ({@link #instrumentChart}).
 *
 * <p>Range → lookback mapping: {@code 1d} uses {@code get_intraday} (5m bars over the current
 * day); {@code 1w/1m/1y/max} use {@code get_ohlc} with {@code days = 7/31/365/1825}. Any other
 * range value is a {@code 400 BAD_REQUEST}.
 */
@Service
public class DepotChartService {

    private static final Map<String, Integer> RANGE_DAYS = Map.of(
            "1w", 7, "1m", 31, "1y", 365, "max", 1825);

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public DepotChartService(AgoraClient agora) {
        this.agora = agora;
    }

    /** One chart point: {@code t} is an ISO date ({@code get_ohlc}) or ISO instant ({@code get_intraday}). */
    public record ChartPoint(String t, BigDecimal value) {
    }

    public record InstrumentChart(String symbol, String range, List<ChartPoint> points) {
    }

    /** Raw close series for one instrument over {@code range}. Throws 400 for an invalid range. */
    public InstrumentChart instrumentChart(String symbol, String range) {
        List<ChartPoint> points = fetchSeries(symbol, range);
        return new InstrumentChart(symbol, range, points);
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
