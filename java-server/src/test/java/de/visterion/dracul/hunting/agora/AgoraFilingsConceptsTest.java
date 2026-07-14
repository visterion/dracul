package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.LocalDate;

import static de.visterion.dracul.hunting.agora.FundamentalConcept.CURRENT_ASSETS;
import static de.visterion.dracul.hunting.agora.FundamentalConcept.CURRENT_LIABILITIES;
import static de.visterion.dracul.hunting.agora.FundamentalConcept.EBIT;
import static de.visterion.dracul.hunting.agora.FundamentalConcept.RETAINED_EARNINGS;
import static de.visterion.dracul.hunting.agora.FundamentalConcept.REVENUE;
import static de.visterion.dracul.hunting.agora.FundamentalConcept.TOTAL_ASSETS;
import static de.visterion.dracul.hunting.agora.FundamentalConcept.TOTAL_LIABILITIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgoraFilingsConceptsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The real deployed-Agora get_fundamental_concepts payload for SAP.DE, exactly as
     *  {@link AgoraClient#callTool} returns it (already unwrapped from the tool "output"). */
    private static JsonNode fixture(String name) {
        try (InputStream in = AgoraFilingsConceptsTest.class.getResourceAsStream("/lazarus-concepts/" + name)) {
            return MAPPER.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load fixture " + name, e);
        }
    }

    @Test void parsesRealSapDeConceptsWithUnitsAndInstantVsDurationShape() {
        AgoraClient client = mock(AgoraClient.class);
        when(client.callTool(eq("get_fundamental_concepts"), any())).thenReturn(fixture("sapde.json"));
        AgoraFilings filings = new AgoraFilings(client);

        ConceptSeries.MultiConcept mc = filings.conceptsStrict("SAP.DE",
                TOTAL_ASSETS, CURRENT_ASSETS, CURRENT_LIABILITIES, TOTAL_LIABILITIES,
                RETAINED_EARNINGS, EBIT, REVENUE);

        // balance-sheet concept (INSTANT): non-empty, unit EUR, every point has a null periodStart
        ConceptSeries assets = mc.series(TOTAL_ASSETS);
        assertThat(assets.isEmpty()).isFalse();
        assertThat(mc.unit(TOTAL_ASSETS)).isEqualTo("EUR");
        assertThat(assets.points()).allSatisfy(p -> {
            assertThat(p.periodStart()).isNull();          // INSTANT fact
            assertThat(p.periodEnd()).isNotNull();
            assertThat(p.value()).isNotNull();
        });

        // liabilities present directly on the concept path, unit EUR
        assertThat(mc.series(TOTAL_LIABILITIES).isEmpty()).isFalse();
        assertThat(mc.unit(TOTAL_LIABILITIES)).isEqualTo("EUR");

        // flow concept (DURATION): every point carries a non-null periodStart (~365d span)
        ConceptSeries ebit = mc.series(EBIT);
        assertThat(ebit.isEmpty()).isFalse();
        assertThat(mc.unit(EBIT)).isEqualTo("EUR");
        assertThat(ebit.points()).allSatisfy(p -> {
            assertThat(p.periodStart()).isNotNull();       // DURATION fact
            assertThat(p.periodEnd()).isNotNull();
        });

        // a concrete datapoint round-trips (the latest reported total-assets instant)
        assertThat(assets.points()).anySatisfy(p -> {
            assertThat(p.periodEnd()).isEqualTo(LocalDate.parse("2024-12-31"));
            assertThat(p.value()).isNotNull();
        });

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        verify(client).callTool(eq("get_fundamental_concepts"), args.capture());
        assertThat(args.getValue().path("symbol").asString()).isEqualTo("SAP.DE");
    }

    @Test void requestedConceptAbsentFromResponseComesBackAsEmptySeriesNotError() {
        AgoraClient client = mock(AgoraClient.class);
        // a payload that filed TOTAL_ASSETS but never TOTAL_LIABILITIES
        when(client.callTool(eq("get_fundamental_concepts"), any())).thenReturn(json(
                "{\"symbol\":\"X.DE\",\"source\":\"SPARSE\",\"concepts\":{"
                + "\"TOTAL_ASSETS\":{\"unit\":\"EUR\",\"datapoints\":["
                + "{\"periodStart\":null,\"periodEnd\":\"2024-12-31\",\"value\":1000,\"filed\":\"2025-03-01\"}]}"
                + "}}"));
        AgoraFilings filings = new AgoraFilings(client);

        ConceptSeries.MultiConcept mc = filings.conceptsStrict("X.DE", TOTAL_ASSETS, TOTAL_LIABILITIES);

        assertThat(mc.series(TOTAL_ASSETS).isEmpty()).isFalse();
        assertThat(mc.series(TOTAL_LIABILITIES).isEmpty()).isTrue();   // absent -> empty, NOT error
        assertThat(mc.unit(TOTAL_LIABILITIES)).isNull();
    }

    @Test void propagatesAgoraUnavailableForTheBatchGuard() {
        AgoraClient client = mock(AgoraClient.class);
        when(client.callTool(eq("get_fundamental_concepts"), any()))
                .thenThrow(new AgoraUnavailableException("down"));
        AgoraFilings filings = new AgoraFilings(client);

        assertThatThrownBy(() -> filings.conceptsStrict("SAP.DE", TOTAL_ASSETS))
                .isInstanceOf(AgoraUnavailableException.class);
    }

    private static JsonNode json(String raw) {
        return MAPPER.readTree(raw);
    }
}
