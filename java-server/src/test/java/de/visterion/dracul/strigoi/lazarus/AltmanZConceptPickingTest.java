package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.marketdata.AgoraClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** End-to-end non-US picking over the REAL deployed-Agora SAP.DE concept payload: the same
 *  latest-instant anchor, FY-end match and latest-filed dedup helpers the US path uses, driven
 *  off the Yahoo/EDGAR concept shape instead of us-gaap tags. */
class AltmanZConceptPickingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AltmanZCalculator calculatorFor(JsonNode conceptsPayload) {
        AgoraClient client = mock(AgoraClient.class);
        when(client.callTool(eq("get_fundamental_concepts"), any())).thenReturn(conceptsPayload);
        return new AltmanZCalculator(new AgoraFilings(client),
                new InstrumentClassifier(List.of("DE", "T", "HK")));
    }

    private static JsonNode fixture() {
        try (InputStream in = AltmanZConceptPickingTest.class
                .getResourceAsStream("/lazarus-concepts/sapde.json")) {
            return MAPPER.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Pinned against the FY2025 line items picked from the fixture (TA 70362M, WC = 20256-17416M,
     *  RE 47345M, EBIT 10762M, REV 36800M, TL 25288M) with a 100000M-EUR cap:
     *  Z = 4.39 (independently hand-computed). Proves anchor=latest instant, FY-end=latest annual
     *  duration, and REVENUE picked at that same FY end. */
    @Test void computesZFromRealSapDeConcepts() {
        AltmanZCalculator calc = calculatorFor(fixture());

        AltmanZCalculator.AltmanZ z = calc.zScore("SAP.DE", 100_000.0, "EUR");

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("4.39");
    }

    @Test void currencyMismatchBetweenCapAndLiabilitiesYieldsUnavailable() {
        AltmanZCalculator calc = calculatorFor(fixture());

        // liabilities are in EUR; a USD cap must not be divided into EUR liabilities
        assertThat(calc.zScore("SAP.DE", 100_000.0, "USD").available()).isFalse();
    }

    @Test void nullReportingCurrencyYieldsUnavailableOnTheConceptPath() {
        AltmanZCalculator calc = calculatorFor(fixture());

        assertThat(calc.zScore("SAP.DE", 100_000.0, null).available()).isFalse();
    }

    @Test void missingTotalLiabilitiesYieldsUnavailable() {
        ObjectNode payload = (ObjectNode) fixture();
        ((ObjectNode) payload.path("concepts")).remove("TOTAL_LIABILITIES");
        AltmanZCalculator calc = calculatorFor(payload);

        // no derivation fallback on the concept path -> unavailable, not a guess
        assertThat(calc.zScore("SAP.DE", 100_000.0, "EUR").available()).isFalse();
    }

    /** Latest-filed dedup + latest-instant anchor on the Yahoo shape: an EARLIER-filed
     *  restatement of the SAME anchor date must lose to the later-filed value. Here TOTAL_ASSETS
     *  gets a bogus later period-end scrubbed and instead a duplicate at the real anchor: the
     *  later-filed one wins, keeping the score available and unchanged. */
    @Test void latestFiledRestatementWinsAtTheAnchorDate() {
        ObjectNode payload = (ObjectNode) fixture();
        ObjectNode ta = (ObjectNode) payload.path("concepts").path("TOTAL_ASSETS");
        // append an EARLIER-filed absurd restatement at the same 2025-12-31 anchor; it must lose
        ((tools.jackson.databind.node.ArrayNode) ta.path("datapoints")).addObject()
                .put("periodStart", (String) null)
                .put("periodEnd", "2025-12-31")
                .put("value", 1)               // absurd 1-EUR restatement, earlier filed
                .put("filed", "2000-01-01");
        AltmanZCalculator calc = calculatorFor(payload);

        AltmanZCalculator.AltmanZ z = calc.zScore("SAP.DE", 100_000.0, "EUR");

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("4.39"); // real later-filed value, not the 1-EUR stub
    }
}
