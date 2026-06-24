package de.visterion.dracul.hunting.edgar;

import de.visterion.dracul.strigoi.echo.QuarterlyEps;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EdgarEpsHistoryTest {

    @Test
    void returnsQuarterlyEpsNewestFirstAndDropsAnnual() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://data.sec.gov");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String json = new String(new ClassPathResource("fixtures/edgar-eps-aapl.json")
                .getInputStream().readAllBytes());
        server.expect(anything()).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var resolver = new StubCik(Optional.of("0000320193"));
        var history = new EdgarEpsHistory(builder.build(), resolver);
        List<QuarterlyEps> q = history.quarterlyEps("AAPL", 10);

        assertThat(q).hasSize(6); // the FY (≈365d) entry is excluded
        assertThat(q.get(0).eps()).isEqualByComparingTo("1.80");   // newest first (2026-03-28)
        assertThat(q.get(0).periodEnd()).hasToString("2026-03-28");
        assertThat(q).extracting(e -> e.eps().toPlainString())
                .containsExactly("1.80", "2.55", "1.55", "1.40", "1.65", "2.40");
    }

    @Test
    void emptyWhenCikUnknown() {
        var history = new EdgarEpsHistory(RestClient.builder().build(), new StubCik(Optional.empty()));
        assertThat(history.quarterlyEps("ZZZ", 10)).isEmpty();
    }

    static final class StubCik extends EdgarCikResolver {
        private final Optional<String> cik;
        StubCik(Optional<String> cik) { super(RestClient.builder().build()); this.cik = cik; }
        @Override public Optional<String> cik(String ticker) { return cik; }
    }
}
