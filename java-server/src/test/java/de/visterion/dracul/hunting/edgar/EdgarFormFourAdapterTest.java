package de.visterion.dracul.hunting.edgar;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.dracul.hunting.DataSourceResult;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class EdgarFormFourAdapterTest {

    static WireMockServer wm;
    EdgarFormFourAdapter adapter;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        var http = RestClient.builder()
                .baseUrl(wm.baseUrl())
                .defaultHeader("User-Agent", "Dracul Test contact@test.example")
                .build();
        adapter = new EdgarFormFourAdapter(http, wm.baseUrl());
    }

    @Test
    void returnsHealthyWithFilings() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("""
                    {"hits":{"total":{"value":1},"hits":[
                        {"_id":"0000320193-26-000001:doc1.xml",
                         "_source":{
                             "display_names":["Apple Inc. (CIK 0000320193)","Doe John (CIK 0001234567)"],
                             "file_date":"2026-05-20",
                             "tickers":["AAPL"],
                             "form":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathMatching("/Archives/edgar/data/.+/doc1\\.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml")
                        .withBody("""
                            <ownershipDocument>
                              <reportingOwner>
                                <reportingOwnerId><rptOwnerName>Doe John</rptOwnerName></reportingOwnerId>
                                <reportingOwnerRelationship><officerTitle>CFO</officerTitle></reportingOwnerRelationship>
                              </reportingOwner>
                              <nonDerivativeTable>
                                <nonDerivativeTransaction>
                                  <transactionDate><value>2026-05-19</value></transactionDate>
                                  <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                                  <transactionAmounts>
                                    <transactionShares><value>1000</value></transactionShares>
                                    <transactionPricePerShare><value>150.00</value></transactionPricePerShare>
                                  </transactionAmounts>
                                </nonDerivativeTransaction>
                              </nonDerivativeTable>
                            </ownershipDocument>
                            """)));

        DataSourceResult<Form4Filing> result = adapter.recentFilings(LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 21));

        assertThat(result.health().status()).isEqualTo("healthy");
        assertThat(result.health().source()).isEqualTo("edgar");
        assertThat(result.items()).hasSize(1);
        var f = result.items().get(0);
        assertThat(f.ticker()).isEqualTo("AAPL");
        assertThat(f.filerName()).isEqualTo("Doe John");
        assertThat(f.transactionCode()).isEqualTo("P");
        assertThat(f.sharesAcquired()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(f.dollarValue()).isEqualByComparingTo(new BigDecimal("150000"));
    }

    @Test
    void emptySearchReturnsHealthyEmpty() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}")));

        DataSourceResult<Form4Filing> result = adapter.recentFilings(LocalDate.now().minusDays(7), LocalDate.now());

        assertThat(result.health().status()).isEqualTo("healthy");
        assertThat(result.items()).isEmpty();
    }

    @Test
    void skipsFilingWithMalformedXml() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("""
                    {"hits":{"total":{"value":1},"hits":[
                        {"_id":"0000000001-26-000001:bad.xml",
                         "_source":{"display_names":["X (CIK 1)"],"file_date":"2026-05-20","tickers":["X"],"form":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathMatching("/Archives/.+/bad\\.xml"))
                .willReturn(aResponse().withStatus(200).withBody("<<not xml>>")));

        DataSourceResult<Form4Filing> result = adapter.recentFilings(LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 21));

        assertThat(result.health().status()).isEqualTo("healthy");
        assertThat(result.items()).isEmpty();
    }

    @Test
    void searchFailureIsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(aResponse().withStatus(503)));

        DataSourceResult<Form4Filing> result = adapter.recentFilings(LocalDate.now().minusDays(7), LocalDate.now());

        assertThat(result.health().status()).isEqualTo("unavailable");
        assertThat(result.health().source()).isEqualTo("edgar");
        assertThat(result.items()).isEmpty();
    }

    @Test
    void sendsUserAgentHeader() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}")));
        adapter.recentFilings(LocalDate.now().minusDays(1), LocalDate.now());
        wm.verify(getRequestedFor(urlPathEqualTo("/LATEST/search-index"))
                .withHeader("User-Agent", equalTo("Dracul Test contact@test.example")));
    }

    @Test
    void parsesSaleTransaction() {
        wm.stubFor(get(urlPathEqualTo("/LATEST/search-index"))
                .willReturn(okJson("""
                    {"hits":{"total":{"value":1},"hits":[
                        {"_id":"0000000002-26-000001:sell.xml",
                         "_source":{"display_names":["MSFT Issuer (CIK 1)","Sue Jane (CIK 2)"],
                                    "file_date":"2026-05-20","tickers":["MSFT"],"form":"4"}}
                    ]}}
                    """)));
        wm.stubFor(get(urlPathMatching("/Archives/.+/sell\\.xml"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type","text/xml").withBody("""
                    <ownershipDocument>
                      <reportingOwner>
                        <reportingOwnerId><rptOwnerName>Sue Jane</rptOwnerName></reportingOwnerId>
                        <reportingOwnerRelationship><officerTitle>VP</officerTitle></reportingOwnerRelationship>
                      </reportingOwner>
                      <nonDerivativeTable>
                        <nonDerivativeTransaction>
                          <transactionDate><value>2026-05-20</value></transactionDate>
                          <transactionCoding><transactionCode>S</transactionCode></transactionCoding>
                          <transactionAmounts>
                            <transactionShares><value>500</value></transactionShares>
                            <transactionPricePerShare><value>400</value></transactionPricePerShare>
                          </transactionAmounts>
                        </nonDerivativeTransaction>
                      </nonDerivativeTable>
                    </ownershipDocument>
                    """)));

        DataSourceResult<Form4Filing> result = adapter.recentFilings(LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 21));

        assertThat(result.health().status()).isEqualTo("healthy");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).transactionCode()).isEqualTo("S");
    }
}
