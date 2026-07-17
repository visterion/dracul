package de.visterion.dracul;

import tools.jackson.databind.json.JsonMapper;
import de.visterion.dracul.report.MorningReport;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
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
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class MorningReportScopeIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired WatchlistRepository watchlist;
    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear();
                    c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build();
    }

    @Test
    void reportIsScopedToCurrentUser() {
        // Seed a HELD position for alice
        WatchlistItem aliceItem = watchlist.insert("alice@x.com", "MRA", "Mra Inc",
                100.0, List.of(), "TRACKING", "manual", null, "USD");
        watchlist.updatePosition(aliceItem.id(), 90.0, 10.0, "USD");
        watchlist.updateTag(aliceItem.id(), "HELD");
        watchlist.updateRiskSnapshot(aliceItem.id(), new BigDecimal("80"),
                new BigDecimal("160"), new BigDecimal("95"), null, Instant.now());

        // Seed a HELD position for bob
        WatchlistItem bobItem = watchlist.insert("bob@x.com", "MRB", "Mrb Inc",
                200.0, List.of(), "TRACKING", "manual", null, "USD");
        watchlist.updatePosition(bobItem.id(), 180.0, 5.0, "USD");
        watchlist.updateTag(bobItem.id(), "HELD");
        watchlist.updateRiskSnapshot(bobItem.id(), new BigDecimal("160"),
                new BigDecimal("320"), new BigDecimal("190"), null, Instant.now());

        // Request the morning report AS alice
        MorningReport r = rest.get().uri("/api/morning-report")
                .header("X-Dev-User", "alice@x.com")
                .retrieve().body(MorningReport.class);

        // Alice sees her own position but not bob's
        assertThat(r.positions()).extracting("symbol").contains("MRA");
        assertThat(r.positions()).extracting("symbol").doesNotContain("MRB");
    }
}
