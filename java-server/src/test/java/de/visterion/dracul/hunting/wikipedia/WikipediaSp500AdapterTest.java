package de.visterion.dracul.hunting.wikipedia;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.visterion.dracul.hunting.DataSourceResult;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class WikipediaSp500AdapterTest {

    static WireMockServer wm;
    WikipediaSp500Adapter adapter;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    // Representative MediaWiki action=parse&prop=wikitext content (the raw wikitext string).
    private static final String WIKITEXT = String.join("\n",
            "{| class=\"wikitable sortable\" id=\"constituents\"",
            "|-",
            "! Symbol", "! Security", "! GICS Sector", "! GICS Sub-Industry",
            "! Headquarters Location", "! Date added", "! CIK", "! Founded",
            "|-",
            "| [[MMM]]", "| [[3M]]", "| Industrials", "| Industrial Conglomerates",
            "| [[Saint Paul, Minnesota]]", "| 1957-03-04", "| 0000066740", "| 1902",
            "|-",
            "| [[NEWO]]", "| [[NewCo]]", "| Information Technology", "| Software",
            "| [[Austin, Texas]]", "| 2026-05-20<ref>cite</ref>", "| 0001234567", "| 2015",
            "|-",
            "| [[BRK.B|BRK.B]]", "| [[Berkshire Hathaway]]", "| Financials", "| Insurance",
            "| [[Omaha, Nebraska]]", "| 2010-02-16", "| 0001067983", "| 1839",
            "|-",
            "| [[NODATE]]", "| [[NoDate Inc]]", "| Energy", "| Oil",
            "| [[Nowhere]]", "| ", "| 0009999999", "| 2000",
            "|}");

    @BeforeEach
    void setUp() {
        wm.resetAll();
        adapter = new WikipediaSp500Adapter(
                RestClient.builder().baseUrl(wm.baseUrl()).build(), "List of S&P 500 companies");
    }

    @Test
    void returnsHealthyWithConstituents() {
        wm.stubFor(get(urlPathEqualTo("/w/api.php")).willReturn(okJson(
                "{\"parse\":{\"title\":\"List of S&P 500 companies\",\"pageid\":1,\"wikitext\":"
                + jsonString(WIKITEXT) + "}}")));

        DataSourceResult<Sp500Constituent> r = adapter.recentConstituents();

        assertThat(r.health().status()).isEqualTo("healthy");
        assertThat(r.health().source()).isEqualTo("wikipedia");
        assertThat(r.items()).extracting(Sp500Constituent::symbol)
                .containsExactlyInAnyOrder("MMM", "NEWO", "BRK.B");
        var newo = r.items().stream().filter(c -> c.symbol().equals("NEWO")).findFirst().orElseThrow();
        assertThat(newo.companyName()).isEqualTo("NewCo");
        assertThat(newo.dateAdded()).isEqualTo(LocalDate.of(2026, 5, 20));   // <ref> stripped
        var brk = r.items().stream().filter(c -> c.symbol().equals("BRK.B")).findFirst().orElseThrow();
        assertThat(brk.dateAdded()).isEqualTo(LocalDate.of(2010, 2, 16));
        assertThat(r.items()).noneMatch(c -> c.symbol().equals("NODATE"));
    }

    @Test
    void fetchErrorIsUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/w/api.php")).willReturn(aResponse().withStatus(500)));

        DataSourceResult<Sp500Constituent> r = adapter.recentConstituents();

        assertThat(r.health().status()).isEqualTo("unavailable");
        assertThat(r.health().detail()).contains("wikipedia");
        assertThat(r.items()).isEmpty();
    }

    @Test
    void parseFailureIsUnavailable() {
        // Non-textual wikitext node: JSON object instead of string triggers the non-textual branch.
        wm.stubFor(get(urlPathEqualTo("/w/api.php")).willReturn(okJson(
                "{\"parse\":{\"title\":\"x\"}}")));

        DataSourceResult<Sp500Constituent> r = adapter.recentConstituents();

        assertThat(r.health().status()).isEqualTo("unavailable");
        assertThat(r.health().detail()).contains("wikipedia");
        assertThat(r.items()).isEmpty();
    }

    // Minimal JSON string encoder for the wikitext literal.
    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.append("\"").toString();
    }
}
