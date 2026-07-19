package de.visterion.dracul.hivemem;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HiveMemResearchServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // --- writeThesisMemory -------------------------------------------------

    @Test void writeThesisMemorySendsRealmSignalTopicTagsAndValidFrom() {
        HiveMemClient client = mock(HiveMemClient.class);
        when(client.callToolWrite(eq("add_cell"), any()))
                .thenReturn(mapper.readTree("{\"inserted\":true,\"id\":\"cell-123\"}"));

        HiveMemResearchService service = new HiveMemResearchService(client, mapper);
        Optional<String> result = service.writeThesisMemory(
                "pead", "ACME", "earnings-surprise", "Strong beat, thesis text",
                List.of("sue-high"), List.of("guidance-cut"), List.of("price<200"),
                "3m", "echo", 0.8, "ref-1");

        assertThat(result).contains("cell-123");

        ArgumentCaptor.captureAddCellArgs(client).ifPresentOrElse(args -> {
            assertThat(args.path("realm").asText()).isEqualTo("dracul-research");
            assertThat(args.path("signal").asText()).isEqualTo("events");
            assertThat(args.path("topic").asText()).isEqualTo("ACME");
            assertThat(args.path("valid_from").asText()).isNotBlank();
            List<String> tags = new java.util.ArrayList<>();
            args.path("tags").forEach(t -> tags.add(t.asText()));
            assertThat(tags).containsExactlyInAnyOrder("ACME", "earnings-surprise", "echo", "pead");
        }, () -> org.junit.jupiter.api.Assertions.fail("add_cell was not called"));
    }

    @Test void writeThesisMemoryReturnsEmptyOnUnavailable() {
        HiveMemClient client = mock(HiveMemClient.class);
        when(client.callToolWrite(eq("add_cell"), any()))
                .thenThrow(new HiveMemUnavailableException("down"));

        HiveMemResearchService service = new HiveMemResearchService(client, mapper);
        Optional<String> result = service.writeThesisMemory(
                "pead", "ACME", "earnings-surprise", "thesis",
                List.of(), List.of(), List.of(), "3m", "echo", 0.5, "ref-1");

        assertThat(result).isEmpty();
    }

    @Test void writeThesisMemoryDegradesOnBrokenMapper() {
        HiveMemClient client = mock(HiveMemClient.class);
        ObjectMapper broken = new ObjectMapper() {
            @Override public String writeValueAsString(Object value) {
                throw new RuntimeException("mapping bug");
            }
        };
        HiveMemResearchService service = new HiveMemResearchService(client, broken);
        Optional<String> result = service.writeThesisMemory(
                "pead", "ACME", "earnings-surprise", "thesis",
                List.of(), List.of(), List.of(), "3m", "echo", 0.5, "ref-1");

        assertThat(result).isEmpty();
    }

    // --- writeOutcomeCell ----------------------------------------------------

    @Test void writeOutcomeCellCallsAddCellOnlyAndDerivesWinFromPositiveRealizedR() {
        HiveMemClient client = mock(HiveMemClient.class);
        when(client.callToolWrite(eq("add_cell"), any()))
                .thenReturn(mapper.readTree("{\"inserted\":true,\"id\":\"outcome-1\"}"));

        HiveMemResearchService service = new HiveMemResearchService(client, mapper);
        boolean ok = service.writeOutcomeCell("thesis-1", "ACME", "earnings-surprise",
                new BigDecimal("1.5"), new BigDecimal("-0.3"), new BigDecimal("2.1"), 12);

        assertThat(ok).isTrue();
        verify(client, times(1)).callToolWrite(eq("add_cell"), any());
        verify(client, never()).callToolWrite(eq("revise_cell"), any());

        JsonNode args = ArgumentCaptor.captureAddCellArgs(client).orElseThrow();
        assertThat(args.path("content").asText()).contains("\"result\":\"win\"");
    }

    @Test void writeOutcomeCellDerivesLossFromNegativeRealizedR() {
        HiveMemClient client = mock(HiveMemClient.class);
        when(client.callToolWrite(eq("add_cell"), any()))
                .thenReturn(mapper.readTree("{\"inserted\":true,\"id\":\"outcome-2\"}"));

        HiveMemResearchService service = new HiveMemResearchService(client, mapper);
        service.writeOutcomeCell("thesis-1", "ACME", "earnings-surprise",
                new BigDecimal("-0.8"), new BigDecimal("-1.1"), new BigDecimal("0.2"), 5);

        JsonNode args = ArgumentCaptor.captureAddCellArgs(client).orElseThrow();
        assertThat(args.path("content").asText()).contains("\"result\":\"loss\"");
    }

    @Test void writeOutcomeCellDerivesScratchFromZeroRealizedR() {
        HiveMemClient client = mock(HiveMemClient.class);
        when(client.callToolWrite(eq("add_cell"), any()))
                .thenReturn(mapper.readTree("{\"inserted\":true,\"id\":\"outcome-3\"}"));

        HiveMemResearchService service = new HiveMemResearchService(client, mapper);
        service.writeOutcomeCell("thesis-1", "ACME", "earnings-surprise",
                BigDecimal.ZERO, new BigDecimal("-0.5"), new BigDecimal("0.5"), 8);

        JsonNode args = ArgumentCaptor.captureAddCellArgs(client).orElseThrow();
        assertThat(args.path("content").asText()).contains("\"result\":\"scratch\"");
    }

    @Test void writeOutcomeCellReturnsFalseOnUnavailableAndDoesNotThrow() {
        HiveMemClient client = mock(HiveMemClient.class);
        when(client.callToolWrite(eq("add_cell"), any()))
                .thenThrow(new HiveMemUnavailableException("down"));

        HiveMemResearchService service = new HiveMemResearchService(client, mapper);
        boolean ok = service.writeOutcomeCell("thesis-1", "ACME", "earnings-surprise",
                new BigDecimal("1.0"), null, null, null);

        assertThat(ok).isFalse();
    }

    @Test void writeOutcomeCellDegradesOnBrokenMapper() {
        HiveMemClient client = mock(HiveMemClient.class);
        ObjectMapper broken = new ObjectMapper() {
            @Override public String writeValueAsString(Object value) {
                throw new RuntimeException("mapping bug");
            }
        };
        HiveMemResearchService service = new HiveMemResearchService(client, broken);
        boolean ok = service.writeOutcomeCell("thesis-1", "ACME", "earnings-surprise",
                new BigDecimal("1.0"), null, null, null);

        assertThat(ok).isFalse();
    }

    // --- searchForInput ------------------------------------------------------

    @Test void searchForInputFiltersByRealmAndReturnsHits() {
        HiveMemClient client = mock(HiveMemClient.class);
        when(client.callToolRead(eq("search"), any())).thenReturn(mapper.readTree(
                "[{\"id\":\"cell-1\",\"summary\":\"s1\",\"content\":\"c1\"},"
                        + "{\"id\":\"cell-2\",\"summary\":\"s2\",\"content\":\"c2\"}]"));

        HiveMemResearchService service = new HiveMemResearchService(client, mapper);
        List<MemoryHit> hits = service.searchForInput("ACME", 5);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0)).isEqualTo(new MemoryHit("cell-1", "s1", "c1"));

        ArgumentCaptor.captureSearchArgs(client).ifPresentOrElse(args ->
                assertThat(args.path("where").path("realm").asText()).isEqualTo("dracul-research"),
                () -> org.junit.jupiter.api.Assertions.fail("search was not called"));
    }

    @Test void searchForInputDegradesToEmptyListOnUnavailable() {
        HiveMemClient client = mock(HiveMemClient.class);
        when(client.callToolRead(eq("search"), any()))
                .thenThrow(new HiveMemUnavailableException("down"));

        HiveMemResearchService service = new HiveMemResearchService(client, mapper);
        List<MemoryHit> hits = service.searchForInput("ACME", 5);

        assertThat(hits).isEmpty();
    }

    @Test void searchForInputDegradesToEmptyListOnBrokenMapper() {
        HiveMemClient client = mock(HiveMemClient.class);
        ObjectMapper broken = new ObjectMapper() {
            @Override public <T extends JsonNode> T valueToTree(Object value) {
                throw new RuntimeException("mapping bug");
            }
        };
        // Force the args-building path through valueToTree by having the real client
        // never actually be reached in a way that matters here: broken mapper alone
        // must degrade the call before it even reaches the client mock.
        HiveMemResearchService service = new HiveMemResearchService(client, broken);
        List<MemoryHit> hits = service.searchForInput("ACME", 5);

        assertThat(hits).isEmpty();
    }

    /** Small helper to pull the JsonNode arg actually passed to callToolWrite/callToolRead,
     *  using Mockito's ArgumentCaptor without importing it at class scope everywhere. */
    private static final class ArgumentCaptor {
        static Optional<JsonNode> captureAddCellArgs(HiveMemClient client) {
            org.mockito.ArgumentCaptor<JsonNode> captor =
                    org.mockito.ArgumentCaptor.forClass(JsonNode.class);
            verify(client, times(1)).callToolWrite(eq("add_cell"), captor.capture());
            return Optional.ofNullable(captor.getValue());
        }

        static Optional<JsonNode> captureSearchArgs(HiveMemClient client) {
            org.mockito.ArgumentCaptor<JsonNode> captor =
                    org.mockito.ArgumentCaptor.forClass(JsonNode.class);
            verify(client, times(1)).callToolRead(eq("search"), captor.capture());
            return Optional.ofNullable(captor.getValue());
        }
    }
}
