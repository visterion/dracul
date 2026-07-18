package de.visterion.dracul.strigoi.echo;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serialized-key snapshot (T1.5 spec §7, "no-logic-change regression"): pins the exact field
 * set the {@code fetch-candidates} tool output carries for one {@link EnrichedPeadCandidate},
 * so a future change to this record must deliberately update this test — proving today's change
 * added ONLY {@code recentNews} and touched no pre-existing field name.
 */
class EnrichedPeadCandidateSerializationTest {

    private static final Set<String> PRE_EXISTING_KEYS = Set.of(
            "symbol", "companyName", "reportDate", "daysSinceReport",
            "epsActual", "epsEstimate", "epsSurprisePercent",
            "sue", "sueDecile", "sueApproximate", "sueAvailable",
            "revenueSurprisePercent", "doubleBeat", "consecutiveBeats", "currentPrice",
            "announcementCar1d", "announcementCar3d", "carAvailable",
            "abnormalVolume", "momentum6_12m", "adv",
            "marketCap", "beta", "sector", "metricsAvailable",
            "accrualRatio", "accrualsAvailable",
            "netEstimateRevisionsProxy", "netEstimateRevisionsDirection", "revisionsAvailable",
            "nextEarningsDate", "daysToNextEarnings",
            "analystCoverage", "coverageAvailable");

    @Test
    void onlyRecentNewsWasAddedToTheSerializedShape() {
        var candidate = new EnrichedPeadCandidate(
                "AAPL", "Apple Inc.", LocalDate.now().minusDays(2), 2,
                new BigDecimal("1.65"), new BigDecimal("1.50"), new BigDecimal("10.0"),
                2.1, 9, false, true,
                new BigDecimal("11.111100"), true, 3, new BigDecimal("190.00"),
                new BigDecimal("0.031000"), new BigDecimal("0.045000"), true,
                new BigDecimal("2.100000"), new BigDecimal("0.150000"), new BigDecimal("120000000.00"),
                2_500_000.0, 1.1, "Technology", true,
                new BigDecimal("0.040000"), true, 5, "up", true,
                LocalDate.now().plusDays(40), 40, 12, true,
                List.of(new EchoNewsItem("Acme beats and raises", "solid quarter", "src", 0.8, Instant.now())));

        JsonNode node = new ObjectMapper().valueToTree(candidate);
        List<String> actualKeys = new ArrayList<>(node.propertyNames());

        assertThat(actualKeys).containsAll(PRE_EXISTING_KEYS);
        assertThat(actualKeys)
                .as("only recentNews may be a NEW key beyond the pre-existing field set")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.Stream.concat(PRE_EXISTING_KEYS.stream(), java.util.stream.Stream.of("recentNews"))
                                .toList());

        JsonNode recentNews = node.path("recentNews").get(0);
        assertThat(recentNews.path("headline").asText()).isEqualTo("Acme beats and raises");
        assertThat(recentNews.path("summary").asText()).isEqualTo("solid quarter");
        assertThat(recentNews.path("source").asText()).isEqualTo("src");
        assertThat(recentNews.path("credibility").asDouble()).isEqualTo(0.8);
        assertThat(recentNews.has("datetime")).isTrue();
    }
}
