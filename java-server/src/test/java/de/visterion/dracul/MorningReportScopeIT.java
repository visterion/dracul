package de.visterion.dracul;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.visterion.dracul.report.MorningReport;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
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
    @Autowired ObjectMapper objectMapper;
    @Autowired WatchlistRepository watchlist;
    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear();
                    c.add(new MappingJackson2HttpMessageConverter(objectMapper)); })
                .build();
    }

    @Test
    void reportContainsOnlyHeldPositions() {
        // dev profile: no X-Dev-User header → CurrentUserHolder.get() returns "default"
        WatchlistItem item = watchlist.insert("default", "MRA", "Mra Inc",
                100.0, List.of(), "WATCHED", null, "USD");
        // isHeld() requires tag=HELD + entryPrice != null + shareCount != null
        watchlist.updatePosition(item.id(), 90.0, 10.0, "USD");
        watchlist.updateTag(item.id(), "HELD");
        watchlist.updateRiskSnapshot(item.id(), new BigDecimal("80"),
                new BigDecimal("160"), new BigDecimal("95"), Instant.now());

        MorningReport r = rest.get().uri("/api/morning-report")
                .retrieve().body(MorningReport.class);

        assertThat(r.positions()).extracting("symbol").contains("MRA");
    }
}
