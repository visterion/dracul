package de.visterion.dracul.hunting.edgar;

import de.visterion.dracul.strigoi.echo.EpsHistoryPort;
import de.visterion.dracul.strigoi.echo.QuarterlyEps;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Quarterly diluted EPS history from EDGAR companyconcept. Falls back to Basic EPS. */
@Component
public class EdgarEpsHistory implements EpsHistoryPort {

    private static final long MIN_QUARTER_DAYS = 80;
    private static final long MAX_QUARTER_DAYS = 100;
    private static final String[] TAGS = {"EarningsPerShareDiluted", "EarningsPerShareBasic"};

    // Parse JSON floats as BigDecimal so reported EPS scale (e.g. "2.40") is preserved exactly.
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final RestClient http;
    private final EdgarCikResolver cik;

    @Autowired
    public EdgarEpsHistory(@Value("${dracul.edgar.user-agent}") String userAgent, EdgarCikResolver cik) {
        this.http = RestClient.builder()
                .baseUrl("https://data.sec.gov")
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.cik = cik;
    }

    // Test constructor.
    EdgarEpsHistory(RestClient http, EdgarCikResolver cik) { this.http = http; this.cik = cik; }

    @Override
    public List<QuarterlyEps> quarterlyEps(String symbol, int maxQuarters) {
        Optional<String> cikOpt = cik.cik(symbol);
        if (cikOpt.isEmpty()) return List.of();
        for (String tag : TAGS) {
            List<QuarterlyEps> q = fetch(cikOpt.get(), tag, maxQuarters);
            if (!q.isEmpty()) return q;
        }
        return List.of();
    }

    private List<QuarterlyEps> fetch(String paddedCik, String tag, int maxQuarters) {
        JsonNode body;
        try {
            String raw = http.get()
                    .uri("/api/xbrl/companyconcept/CIK{cik}/us-gaap/{tag}.json", paddedCik, tag)
                    .retrieve().body(String.class);
            body = raw == null ? null : MAPPER.readTree(raw);
        } catch (Exception e) {
            return List.of();
        }
        if (body == null) return List.of();

        Map<LocalDate, BigDecimal> byEnd = new LinkedHashMap<>();
        for (JsonNode unit : body.path("units")) {        // iterate unit arrays (e.g. "USD/shares")
            for (JsonNode row : unit) {
                try {
                    String start = row.path("start").asText("");
                    String end = row.path("end").asText("");
                    if (start.isEmpty() || end.isEmpty() || row.path("val").isMissingNode()) continue;
                    LocalDate s = LocalDate.parse(start);
                    LocalDate e = LocalDate.parse(end);
                    long days = ChronoUnit.DAYS.between(s, e);
                    if (days < MIN_QUARTER_DAYS || days > MAX_QUARTER_DAYS) continue; // quarterly only
                    byEnd.put(e, row.path("val").decimalValue());
                } catch (Exception rowError) {
                    // skip malformed row, keep the rest
                }
            }
        }
        List<QuarterlyEps> out = new ArrayList<>();
        byEnd.forEach((end, val) -> out.add(new QuarterlyEps(end, val)));
        out.sort(Comparator.comparing(QuarterlyEps::periodEnd).reversed());
        return out.size() > maxQuarters ? out.subList(0, maxQuarters) : out;
    }
}
