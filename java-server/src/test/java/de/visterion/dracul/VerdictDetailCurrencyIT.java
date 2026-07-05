package de.visterion.dracul;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import de.visterion.dracul.marketdata.StubFxServiceConfig;
import de.visterion.dracul.verdict.ContributingStrigoiDetail;
import de.visterion.dracul.verdict.VerdictRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ContainerConfig.class, StubFxServiceConfig.class})
@ActiveProfiles("dev")
class VerdictDetailCurrencyIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired VerdictRepository verdictRepo;
    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> {
                    c.clear();
                    c.add(new JacksonJsonHttpMessageConverter(objectMapper));
                })
                .build();
    }

    @Test
    void servesNativeCurrencyFields() throws Exception {
        String id = verdictRepo.insertSynthesized(
                "AVGO", "Broadcom", List.of("strigoi-spin"), 0.8, "summary",
                List.of("SPIN"), BigDecimal.valueOf(100.0), "USD", 0.7, "90d",
                List.of(), List.of(), List.<ContributingStrigoiDetail>of(),
                List.of(), "default");

        JsonNode json = rest.get().uri("/api/verdict/" + id).retrieve().body(JsonNode.class);

        // StubFxService = identity, so converted == native numerically, but codes must round-trip.
        assertThat(json.get("currency").asText()).isEqualTo("EUR"); // default display currency
        assertThat(json.get("nativeCurrency").asText()).isEqualTo("USD");
        assertThat(json.get("nativeCurrentPrice").asDouble()).isEqualTo(100.0);
    }
}
