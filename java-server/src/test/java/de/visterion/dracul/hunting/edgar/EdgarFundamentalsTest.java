package de.visterion.dracul.hunting.edgar;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.dracul.strigoi.echo.AccrualMetrics;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class EdgarFundamentalsTest {

    static WireMockServer wm;
    EdgarFundamentals adapter;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        // CIK resolver stub (ticker ACME -> 0000000123)
        wm.stubFor(get(urlPathEqualTo("/files/company_tickers.json")).willReturn(okJson("""
            {"0":{"cik_str":123,"ticker":"ACME","title":"Acme Inc"}}
            """)));
        var cikClient = RestClient.builder().baseUrl(wm.baseUrl()).build();
        var dataClient = RestClient.builder().baseUrl(wm.baseUrl()).build();
        adapter = new EdgarFundamentals(dataClient, new EdgarCikResolver(cikClient));
    }

    private static String annualFact(String end, String start, double val) {
        return "{\"start\":\"" + start + "\",\"end\":\"" + end + "\",\"val\":" + val + ",\"form\":\"10-K\"}";
    }
    private static String instantFact(String end, double val) {
        return "{\"end\":\"" + end + "\",\"val\":" + val + ",\"form\":\"10-K\"}";
    }

    @Test
    void computesSloanAccrualRatio() {
        // NI 200, OCF 150, Assets 1000 -> (200-150)/1000 = 0.05
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000000123/us-gaap/NetIncomeLoss.json"))
                .willReturn(okJson("{\"units\":{\"USD\":[" + annualFact("2025-12-31","2025-01-01",200.0) + "]}}")));
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000000123/us-gaap/NetCashProvidedByUsedInOperatingActivities.json"))
                .willReturn(okJson("{\"units\":{\"USD\":[" + annualFact("2025-12-31","2025-01-01",150.0) + "]}}")));
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000000123/us-gaap/Assets.json"))
                .willReturn(okJson("{\"units\":{\"USD\":[" + instantFact("2025-12-31",1000.0) + "]}}")));

        AccrualMetrics m = adapter.accruals("ACME");

        assertThat(m.available()).isTrue();
        assertThat(m.accrualRatio()).isEqualByComparingTo("0.05");
    }

    @Test
    void unknownTickerIsUnavailable() {
        assertThat(adapter.accruals("ZZZZ").available()).isFalse();
    }

    @Test
    void missingConceptIsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000000123/us-gaap/NetIncomeLoss.json"))
                .willReturn(aResponse().withStatus(404)));
        assertThat(adapter.accruals("ACME").available()).isFalse();
    }

    @Test
    void mismatchedAnnualPeriodsAreUnavailable() {
        // NI for FY2025, OCF only for FY2024 -> periods differ -> unavailable
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000000123/us-gaap/NetIncomeLoss.json"))
                .willReturn(okJson("{\"units\":{\"USD\":[" + annualFact("2025-12-31","2025-01-01",200.0) + "]}}")));
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000000123/us-gaap/NetCashProvidedByUsedInOperatingActivities.json"))
                .willReturn(okJson("{\"units\":{\"USD\":[" + annualFact("2024-12-31","2024-01-01",150.0) + "]}}")));
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000000123/us-gaap/Assets.json"))
                .willReturn(okJson("{\"units\":{\"USD\":[" + instantFact("2025-12-31",1000.0) + "]}}")));
        assertThat(adapter.accruals("ACME").available()).isFalse();
    }

    @Test
    void durationRowInAssetsIsIgnoredForInstant() {
        // Assets has a duration row (with start) that must be ignored; only the instant counts.
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000000123/us-gaap/NetIncomeLoss.json"))
                .willReturn(okJson("{\"units\":{\"USD\":[" + annualFact("2025-12-31","2025-01-01",200.0) + "]}}")));
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000000123/us-gaap/NetCashProvidedByUsedInOperatingActivities.json"))
                .willReturn(okJson("{\"units\":{\"USD\":[" + annualFact("2025-12-31","2025-01-01",150.0) + "]}}")));
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000000123/us-gaap/Assets.json"))
                .willReturn(okJson("{\"units\":{\"USD\":[" + annualFact("2025-12-31","2025-01-01",999999.0) + "," + instantFact("2025-12-31",1000.0) + "]}}")));
        assertThat(adapter.accruals("ACME").accrualRatio()).isEqualByComparingTo("0.05");
    }

    @Test
    void zeroAssetsIsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000000123/us-gaap/NetIncomeLoss.json"))
                .willReturn(okJson("{\"units\":{\"USD\":[" + annualFact("2025-12-31","2025-01-01",200.0) + "]}}")));
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000000123/us-gaap/NetCashProvidedByUsedInOperatingActivities.json"))
                .willReturn(okJson("{\"units\":{\"USD\":[" + annualFact("2025-12-31","2025-01-01",150.0) + "]}}")));
        wm.stubFor(get(urlPathEqualTo("/api/xbrl/companyconcept/CIK0000000123/us-gaap/Assets.json"))
                .willReturn(okJson("{\"units\":{\"USD\":[" + instantFact("2025-12-31",0.0) + "]}}")));
        assertThat(adapter.accruals("ACME").available()).isFalse();
    }
}
