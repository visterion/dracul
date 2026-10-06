package de.visterion.dracul.executor.pnl;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.depot.DepotService;
import de.visterion.dracul.depot.DepotUnavailableException;
import de.visterion.dracul.executor.DecisionLog;
import de.visterion.dracul.executor.DecisionLogRepository;
import de.visterion.dracul.executor.ExecutorIndicators;
import de.visterion.dracul.executor.ExecutorPositionFixtures;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.FakeExecutionGateway;
import de.visterion.dracul.marketdata.FxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/** Spec 2026-10-06: both P&L endpoints over a real Postgres. Synthetic symbols/agents only. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ContainerConfig.class, StrigoiPnlControllerIT.FakeGatewayConfig.class})
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class StrigoiPnlControllerIT {

    @TestConfiguration
    static class FakeGatewayConfig {
        @Bean
        @Primary
        FakeExecutionGateway fakeExecutionGateway() {
            return new FakeExecutionGateway();
        }
    }

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired FakeExecutionGateway gateway;
    @Autowired ExecutorPositionRepository positions;
    @Autowired DecisionLogRepository decisionLog;
    @MockitoBean DepotService depots;
    @MockitoBean FxService fx;
    @MockitoBean ExecutorIndicators indicators;

    RestClient rest;
    String conn;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> {
                    c.clear();
                    c.add(new JacksonJsonHttpMessageConverter(objectMapper));
                })
                .build();
        conn = "pnl-" + System.nanoTime();
        when(depots.isVisible(eq(conn), any())).thenReturn(true);
        when(fx.hasRate("USD", "EUR")).thenReturn(true);
        when(fx.convert(any(), eq("USD"), eq("EUR")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("0.9")));
        when(indicators.levels(anyString(), anyInt(), anyInt()))
                .thenReturn(new ExecutorIndicators.Levels(false, null, null, null, null));
    }

    private long insert(String agent, String symbol, String status, String qty, String entry,
            String entryFilledAt) {
        return positions.insert(ExecutorPositionFixtures.withoutKillLevel(null, conn, symbol, "BUY",
                new BigDecimal(qty), new BigDecimal(entry), new BigDecimal("1"), new BigDecimal("1"), 1,
                null, List.of(), "sig-" + symbol + "-" + conn, agent, null, null, status,
                "brk-" + symbol + "-" + conn, new BigDecimal(entry), null, 0, null, null, null, null,
                null, null, null, null, null, 0, null, null, null, null, null, null, false, null,
                entryFilledAt));
    }

    private void trim(String symbol, long positionId, String qtyClosed, String price) {
        decisionLog.insert(new DecisionLog(null, "run-" + conn, "exec-test", "MAINTENANCE", null, null, null,
                symbol, null, null, "TRIM", "RECONCILE_TRIM",
                objectMapper.readTree("{\"qty_closed\":" + qtyClosed + ",\"price\":" + price
                        + ",\"position_id\":" + positionId + "}"),
                null, null, null, null));
    }

    /** syna: one closed win (PNLA) + one open trimmed position (PNLB) + one unfilled entry;
     *  synb: only a cancelled entry; unknown: one closed loss (PNLE). */
    private void seedBook() {
        long a = insert("strigoi-syna", "PNLA", "OPEN", "10", "100", "2026-09-01T15:00:00Z");
        positions.close(a, new BigDecimal("110"), new BigDecimal("0.5"), "HARD_STOP", new BigDecimal("20"));
        long b = insert("strigoi-syna", "PNLB", "OPEN", "5", "50", "2026-09-02T15:00:00Z");
        trim("PNLB", b, "2", "55");
        gateway.seedPosition(new BrokerPosition("PNLB", "BUY", new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("60"), 0));
        insert("strigoi-syna", "PNLC", "OPEN", "7", "30", null);              // unfilled entry
        long d = insert("strigoi-synb", "PNLD", "OPEN", "4", "20", null);
        positions.markCancelled(d);                                           // never filled
        long e = insert(null, "PNLE", "OPEN", "1", "20", "2026-09-03T15:00:00Z");
        positions.close(e, new BigDecimal("18"), new BigDecimal("-1"), "HARD_STOP", new BigDecimal("2"));
    }

    private JsonNode get(String path) {
        return rest.get().uri(path).retrieve().body(JsonNode.class);
    }

    private int status(String path) {
        return rest.get().uri(path).exchange((req, res) -> res.getStatusCode().value());
    }

    @Test
    void overviewGroupsPerStrigoiInEur() {
        seedBook();

        JsonNode body = get("/api/executor/pnl/strigoi?connection=" + conn);

        assertThat(body.path("connection").asString()).isEqualTo(conn);
        assertThat(body.path("currency").asString()).isEqualTo("EUR");
        assertThat(body.path("fxBasis").asString()).isEqualTo("current");
        JsonNode rows = body.path("strigoi");
        assertThat(rows).hasSize(2);

        JsonNode syna = rows.get(0);
        assertThat(syna.path("strigoi").asString()).isEqualTo("strigoi-syna");
        assertThat(syna.path("closedTrades").asInt()).isEqualTo(1);
        assertThat(syna.path("wins").asInt()).isEqualTo(1);
        assertThat(syna.path("losses").asInt()).isZero();
        assertThat(syna.path("hitRate").decimalValue()).isEqualByComparingTo("1");
        assertThat(syna.path("realizedEur").decimalValue()).isEqualByComparingTo("99.00"); // (100 + 10) * 0.9
        assertThat(syna.path("unrealizedEur").decimalValue()).isEqualByComparingTo("45.00"); // 50 * 0.9
        assertThat(syna.path("totalEur").decimalValue()).isEqualByComparingTo("144.00");
        assertThat(syna.path("sumR").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(syna.path("openPositions").asInt()).isEqualTo(1);
        assertThat(syna.path("openCostEur").decimalValue()).isEqualByComparingTo("225.00");
        assertThat(syna.path("flaggedTrades").asInt()).isZero();

        JsonNode unknown = rows.get(1);
        assertThat(unknown.path("strigoi").asString()).isEqualTo("unknown");
        assertThat(unknown.path("losses").asInt()).isEqualTo(1);
        assertThat(unknown.path("realizedEur").decimalValue()).isEqualByComparingTo("-1.80");
    }

    @Test
    void detailListsTradesOpenFirst() {
        seedBook();

        JsonNode body = get("/api/executor/pnl/strigoi/strigoi-syna?connection=" + conn);

        assertThat(body.path("summary").path("strigoi").asString()).isEqualTo("strigoi-syna");
        JsonNode trades = body.path("trades");
        assertThat(trades).hasSize(2);
        assertThat(trades.get(0).path("symbol").asString()).isEqualTo("PNLB");
        assertThat(trades.get(0).path("status").asString()).isEqualTo("OPEN");
        assertThat(trades.get(0).path("unrealizedEur").decimalValue()).isEqualByComparingTo("45.00");
        assertThat(trades.get(0).path("exitDate").isNull()).isTrue();
        assertThat(trades.get(1).path("symbol").asString()).isEqualTo("PNLA");
        assertThat(trades.get(1).path("realizedEur").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(trades.get(1).path("r").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(trades.get(1).path("exitReason").asString()).isEqualTo("HARD_STOP");
        assertThat(trades.get(1).path("currency").asString()).isEqualTo("USD");
        assertThat(trades.get(1).path("flags")).isEmpty();
    }

    @Test
    void openTradeWithoutAnyPriceIsNullAndFlagged() {
        insert("strigoi-sync", "PNLF", "OPEN", "3", "40", "2026-09-04T15:00:00Z");

        JsonNode trade = get("/api/executor/pnl/strigoi/strigoi-sync?connection=" + conn).path("trades").get(0);

        assertThat(trade.path("unrealizedEur").isNull()).isTrue();
        assertThat(trade.path("flags").get(0).asString()).isEqualTo("NO_PRICE");
    }

    @Test
    void unknownStrigoiIsAnEmptyDetailNot404() {
        JsonNode body = get("/api/executor/pnl/strigoi/strigoi-none?connection=" + conn);

        assertThat(body.path("trades")).isEmpty();
        assertThat(body.path("summary").path("closedTrades").asInt()).isZero();
        assertThat(body.path("summary").path("hitRate").isNull()).isTrue();
    }

    @Test
    void invisibleConnectionIs404AndAgoraOutageIs503() {
        when(depots.isVisible(eq(conn), any())).thenReturn(false);
        assertThat(status("/api/executor/pnl/strigoi?connection=" + conn)).isEqualTo(404);
        assertThat(status("/api/executor/pnl/strigoi/strigoi-syna?connection=" + conn)).isEqualTo(404);

        when(depots.isVisible(eq(conn), any())).thenThrow(new DepotUnavailableException("agora down"));
        assertThat(status("/api/executor/pnl/strigoi?connection=" + conn)).isEqualTo(503);
    }
}
