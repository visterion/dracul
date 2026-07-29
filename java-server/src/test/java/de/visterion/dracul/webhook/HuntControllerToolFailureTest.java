package de.visterion.dracul.webhook;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Coverage for the failure envelope introduced on {@link HuntController}: {@link
 *  HuntController#ok}, {@link HuntController#unavailable}, and {@link
 *  HuntController#GUARD_MARKER}. No Spring context needed — these are static/protected
 *  helpers exercised directly. */
class HuntControllerToolFailureTest {

    @Test
    void unavailableEnvelopeCarriesAllFourHealthKeysAndTruncatesDetail() {
        String longDetail = "x".repeat(2000);
        Map<String, Object> out = HuntController.unavailable(
                Map.of("candidates", List.of()), "strigoi-test", HuntController.GUARD_MARKER + longDetail);

        assertThat(out).containsKey("candidates");
        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) out.get("data_source_health");
        assertThat(health).containsOnlyKeys("status", "source", "detail", "checked_at");
        assertThat(health.get("status")).isEqualTo("unavailable");
        assertThat(health.get("source")).isEqualTo("strigoi-test");
        assertThat((String) health.get("detail")).startsWith("tool-guard: ").hasSize(500);
    }

    @Test
    void okWrapsOutputInTheEnvelope() {
        var response = HuntController.ok(Map.of("candidates", List.of()));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsOnlyKeys("output");
    }
}
