package de.visterion.dracul.hunting.edgar;

import de.visterion.dracul.strigoi.echo.AccrualMetrics;
import de.visterion.dracul.strigoi.echo.FundamentalsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Sloan (1996) accrual ratio from EDGAR companyconcept facts:
 * {@code (NetIncomeLoss - NetCashProvidedByUsedInOperatingActivities) / Assets}, using the most
 * recent annual income/cash-flow values and the latest total-assets instant. A high positive
 * ratio means earnings are not cash-backed (low quality, weaker drift). Graceful: any missing
 * concept / unknown CIK / parse error → {@link AccrualMetrics#unavailable()}, never throws.
 */
@Component
public class EdgarFundamentals implements FundamentalsPort {

    private static final Logger log = LoggerFactory.getLogger(EdgarFundamentals.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final long MIN_ANNUAL_DAYS = 350;
    private static final long MAX_ANNUAL_DAYS = 380;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final RestClient http;
    private final EdgarCikResolver cik;

    @Autowired
    public EdgarFundamentals(@Value("${dracul.edgar.user-agent}") String userAgent, EdgarCikResolver cik) {
        this.http = RestClient.builder()
                .baseUrl("https://data.sec.gov")
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.cik = cik;
    }

    // Test constructor.
    EdgarFundamentals(RestClient http, EdgarCikResolver cik) { this.http = http; this.cik = cik; }

    private record Dated(LocalDate end, BigDecimal value) {}

    @Override
    public AccrualMetrics accruals(String symbol) {
        try {
            Optional<String> cikOpt = cik.cik(symbol);
            if (cikOpt.isEmpty()) return AccrualMetrics.unavailable();
            String c = cikOpt.get();

            Dated netIncome = latestAnnualDuration(c, "NetIncomeLoss");
            Dated opCashFlow = latestAnnualDuration(c, "NetCashProvidedByUsedInOperatingActivities");
            BigDecimal assets = latestInstant(c, "Assets");

            if (netIncome == null || opCashFlow == null || assets == null || assets.signum() == 0
                    || !netIncome.end().equals(opCashFlow.end())) {   // both flows must cover the same fiscal period
                return AccrualMetrics.unavailable();
            }
            BigDecimal ratio = netIncome.value().subtract(opCashFlow.value())
                    .divide(assets, MC).setScale(6, RoundingMode.HALF_UP);
            return new AccrualMetrics(ratio, true);
        } catch (Exception e) {
            log.debug("EDGAR accruals failed for {}: {}", symbol, e.getMessage());
            return AccrualMetrics.unavailable();
        }
    }

    /** Most recent ~annual (350-380d) duration fact value for the tag, by period end; null if none. */
    private Dated latestAnnualDuration(String paddedCik, String tag) {
        JsonNode body = fetch(paddedCik, tag);
        if (body == null) return null;
        LocalDate bestEnd = null;
        BigDecimal bestVal = null;
        for (JsonNode unit : body.path("units")) {
            for (JsonNode row : unit) {
                try {
                    String start = row.path("start").asText("");
                    String end = row.path("end").asText("");
                    if (start.isEmpty() || end.isEmpty() || row.path("val").isMissingNode()) continue;
                    LocalDate s = LocalDate.parse(start);
                    LocalDate e = LocalDate.parse(end);
                    long days = ChronoUnit.DAYS.between(s, e);
                    if (days < MIN_ANNUAL_DAYS || days > MAX_ANNUAL_DAYS) continue;
                    if (bestEnd == null || e.isAfter(bestEnd)) { bestEnd = e; bestVal = row.path("val").decimalValue(); }
                } catch (Exception rowError) {
                    // skip malformed row
                }
            }
        }
        return bestVal == null ? null : new Dated(bestEnd, bestVal);
    }

    /** Most recent instant fact value (no start, has end) for the tag, by end; null if none. */
    private BigDecimal latestInstant(String paddedCik, String tag) {
        JsonNode body = fetch(paddedCik, tag);
        if (body == null) return null;
        LocalDate bestEnd = null;
        BigDecimal bestVal = null;
        for (JsonNode unit : body.path("units")) {
            for (JsonNode row : unit) {
                try {
                    String end = row.path("end").asText("");
                    if (end.isEmpty() || row.path("val").isMissingNode()) continue;
                    if (!row.path("start").asText("").isEmpty()) continue; // instant only
                    LocalDate e = LocalDate.parse(end);
                    if (bestEnd == null || e.isAfter(bestEnd)) { bestEnd = e; bestVal = row.path("val").decimalValue(); }
                } catch (Exception rowError) {
                    // skip malformed row
                }
            }
        }
        return bestVal;
    }

    private JsonNode fetch(String paddedCik, String tag) {
        try {
            String raw = http.get()
                    .uri("/api/xbrl/companyconcept/CIK{cik}/us-gaap/{tag}.json", paddedCik, tag)
                    .retrieve().body(String.class);
            return raw == null ? null : MAPPER.readTree(raw);
        } catch (Exception e) {
            log.debug("EDGAR companyconcept fetch failed for {} {}: {}", paddedCik, tag, e.getMessage());
            return null;
        }
    }
}
