package de.visterion.dracul.depot;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class DepotServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode json(String s) { return mapper.readTree(s); }

    private static final String LIVE_EMAILS = "viktor@ufelmann.de";

    @Test void happyPathComputesAggregatesAndEnrichesQuotes() {
        AgoraDepotClient depotClient = Mockito.mock(AgoraDepotClient.class);
        AgoraClient agora = Mockito.mock(AgoraClient.class);

        DepotConnection conn = new DepotConnection("depot-1", "alpaca", "paper", "connected", "2026-07-11T10:00:00Z");
        when(depotClient.listConnections()).thenReturn(List.of(conn));

        DepotAccount account = new DepotAccount(
                new BigDecimal("1000"), new BigDecimal("5000"), new BigDecimal("2000"),
                "USD", "ACTIVE", "2026-07-11T10:00:00Z");
        when(depotClient.account("depot-1")).thenReturn(account);

        DepotPosition posA = new DepotPosition("AAPL", new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("1200"), new BigDecimal("200"), "USD");
        DepotPosition posB = new DepotPosition("MSFT", new BigDecimal("5"), new BigDecimal("300"),
                new BigDecimal("1600"), new BigDecimal("100"), "USD");
        PositionsSnapshot snapshot = new PositionsSnapshot(List.of(posA, posB), "2026-07-11T10:05:00Z");
        when(depotClient.positions("depot-1")).thenReturn(snapshot);

        when(depotClient.orders("depot-1")).thenReturn(List.of());

        when(agora.callTool(eq("get_quote"), any())).thenReturn(json("""
                {"quotes":[
                  {"symbol":"AAPL","price":120.0,"dayChangePercent":2.0,"currency":"USD"},
                  {"symbol":"MSFT","price":320.0,"dayChangePercent":-1.0,"currency":"USD"}
                ]}
                """));

        DepotService service = new DepotService(depotClient, agora, LIVE_EMAILS);

        List<DepotDto> result = service.depots("viktor@ufelmann.de");

        assertThat(result).hasSize(1);
        DepotDto dto = result.getFirst();
        assertThat(dto.error()).isNull();
        assertThat(dto.asOf()).isEqualTo("2026-07-11T10:05:00Z");

        // investedValue = 1200 + 1600 = 2800
        assertThat(dto.aggregates().investedValue()).isEqualByComparingTo("2800.00");
        // totalUnrealizedPl = 200 + 100 = 300
        assertThat(dto.aggregates().totalUnrealizedPl()).isEqualByComparingTo("300.00");
        // totalUnrealizedPlPct = pl / (invested - pl) * 100 = 300 / (2800-300) * 100 = 300/2500*100 = 12.00
        assertThat(dto.aggregates().totalUnrealizedPlPct()).isEqualByComparingTo("12.00");
        // dayChangeAbs = 1200*0.02 + 1600*(-0.01) = 24 - 16 = 8.00
        assertThat(dto.aggregates().dayChangeAbs()).isEqualByComparingTo("8.00");
        // dayChangePct = dayChangeAbs / invested * 100 = 8/2800*100 = 0.2857... -> HALF_UP scale2 = 0.29
        assertThat(dto.aggregates().dayChangePct()).isEqualByComparingTo("0.29");

        assertThat(dto.positions()).hasSize(2);
        DepotPositionDto posDtoA = dto.positions().stream().filter(p -> p.symbol().equals("AAPL")).findFirst().orElseThrow();
        // unrealizedPlPct = unrealizedPl / (qty*avgEntryPrice) * 100 = 200/(10*100)*100 = 20.00
        assertThat(posDtoA.unrealizedPlPct()).isEqualByComparingTo("20.00");
        // weightPct = marketValue/invested*100 = 1200/2800*100 = 42.857... -> 42.86
        assertThat(posDtoA.weightPct()).isEqualByComparingTo("42.86");
        assertThat(posDtoA.price()).isEqualByComparingTo("120.0");
        assertThat(posDtoA.dayChangePercent()).isEqualByComparingTo("2.0");

        DepotPositionDto posDtoB = dto.positions().stream().filter(p -> p.symbol().equals("MSFT")).findFirst().orElseThrow();
        // unrealizedPlPct = 100/(5*300)*100 = 100/1500*100 = 6.666... -> 6.67
        assertThat(posDtoB.unrealizedPlPct()).isEqualByComparingTo("6.67");
        // weightPct = 1600/2800*100 = 57.142... -> 57.14
        assertThat(posDtoB.weightPct()).isEqualByComparingTo("57.14");
    }

    @Test void liveDepotVisibleOnlyToAllowedEmail() {
        AgoraDepotClient depotClient = Mockito.mock(AgoraDepotClient.class);
        AgoraClient agora = Mockito.mock(AgoraClient.class);

        DepotConnection paper = new DepotConnection("depot-paper", "alpaca", "paper", "connected", "2026-07-11T10:00:00Z");
        DepotConnection live = new DepotConnection("depot-live", "alpaca", "live", "connected", "2026-07-11T10:00:00Z");
        when(depotClient.listConnections()).thenReturn(List.of(paper, live));

        DepotAccount account = new DepotAccount(new BigDecimal("1000"), new BigDecimal("1000"),
                new BigDecimal("1000"), "USD", "ACTIVE", "2026-07-11T10:00:00Z");
        when(depotClient.account(any())).thenReturn(account);
        when(depotClient.positions(any())).thenReturn(new PositionsSnapshot(List.of(), "2026-07-11T10:00:00Z"));
        when(depotClient.orders(any())).thenReturn(List.of());

        DepotService service = new DepotService(depotClient, agora, LIVE_EMAILS);

        List<DepotDto> allowed = service.depots("viktor@ufelmann.de");
        assertThat(allowed).extracting(DepotDto::id).containsExactlyInAnyOrder("depot-paper", "depot-live");

        List<DepotDto> denied = service.depots("other@x.com");
        assertThat(denied).extracting(DepotDto::id).containsExactly("depot-paper");
    }

    @Test void liveEnvironmentCaseInsensitiveCompare() {
        AgoraDepotClient depotClient = Mockito.mock(AgoraDepotClient.class);
        AgoraClient agora = Mockito.mock(AgoraClient.class);

        DepotConnection live = new DepotConnection("depot-live", "alpaca", "LIVE", "connected", "2026-07-11T10:00:00Z");
        when(depotClient.listConnections()).thenReturn(List.of(live));
        when(depotClient.account(any())).thenReturn(new DepotAccount(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, "USD", "ACTIVE", "2026-07-11T10:00:00Z"));
        when(depotClient.positions(any())).thenReturn(new PositionsSnapshot(List.of(), "2026-07-11T10:00:00Z"));
        when(depotClient.orders(any())).thenReturn(List.of());

        DepotService service = new DepotService(depotClient, agora, LIVE_EMAILS);

        assertThat(service.depots("other@x.com")).isEmpty();
        assertThat(service.depots("viktor@ufelmann.de")).hasSize(1);
    }

    @Test void perConnectionFailureIsolatesOthers() {
        AgoraDepotClient depotClient = Mockito.mock(AgoraDepotClient.class);
        AgoraClient agora = Mockito.mock(AgoraClient.class);

        DepotConnection conn1 = new DepotConnection("depot-1", "alpaca", "paper", "connected", "2026-07-11T10:00:00Z");
        DepotConnection conn2 = new DepotConnection("depot-2", "alpaca", "paper", "connected", "2026-07-11T10:00:00Z");
        when(depotClient.listConnections()).thenReturn(List.of(conn1, conn2));

        DepotAccount account = new DepotAccount(new BigDecimal("1000"), new BigDecimal("1000"),
                new BigDecimal("1000"), "USD", "ACTIVE", "2026-07-11T10:00:00Z");
        when(depotClient.account("depot-1")).thenReturn(account);
        when(depotClient.account("depot-2")).thenThrow(new DepotUnavailableException("agora down"));
        when(depotClient.positions("depot-1")).thenReturn(new PositionsSnapshot(List.of(), "2026-07-11T10:00:00Z"));
        when(depotClient.orders("depot-1")).thenReturn(List.of());

        DepotService service = new DepotService(depotClient, agora, LIVE_EMAILS);
        List<DepotDto> result = service.depots("viktor@ufelmann.de");

        assertThat(result).hasSize(2);
        DepotDto ok = result.stream().filter(d -> d.id().equals("depot-1")).findFirst().orElseThrow();
        DepotDto failed = result.stream().filter(d -> d.id().equals("depot-2")).findFirst().orElseThrow();
        assertThat(ok.error()).isNull();
        assertThat(failed.error()).isEqualTo("agora down");
        assertThat(failed.account()).isNull();
        assertThat(failed.positions()).isNull();
        assertThat(failed.aggregates()).isNull();
    }

    @Test void quoteFailureNullsOnlyDayChangeFields() {
        AgoraDepotClient depotClient = Mockito.mock(AgoraDepotClient.class);
        AgoraClient agora = Mockito.mock(AgoraClient.class);

        DepotConnection conn = new DepotConnection("depot-1", "alpaca", "paper", "connected", "2026-07-11T10:00:00Z");
        when(depotClient.listConnections()).thenReturn(List.of(conn));
        when(depotClient.account("depot-1")).thenReturn(new DepotAccount(new BigDecimal("1000"),
                new BigDecimal("1000"), new BigDecimal("1000"), "USD", "ACTIVE", "2026-07-11T10:00:00Z"));

        DepotPosition posA = new DepotPosition("AAPL", new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("1200"), new BigDecimal("200"), "USD");
        when(depotClient.positions("depot-1")).thenReturn(new PositionsSnapshot(List.of(posA), "2026-07-11T10:05:00Z"));
        when(depotClient.orders("depot-1")).thenReturn(List.of());

        when(agora.callTool(eq("get_quote"), any())).thenThrow(new AgoraUnavailableException("agora quote down"));

        DepotService service = new DepotService(depotClient, agora, LIVE_EMAILS);
        List<DepotDto> result = service.depots("viktor@ufelmann.de");

        assertThat(result).hasSize(1);
        DepotDto dto = result.getFirst();
        assertThat(dto.error()).isNull();
        assertThat(dto.aggregates().investedValue()).isEqualByComparingTo("1200.00");
        assertThat(dto.aggregates().totalUnrealizedPl()).isEqualByComparingTo("200.00");
        assertThat(dto.aggregates().dayChangeAbs()).isNull();
        assertThat(dto.aggregates().dayChangePct()).isNull();

        DepotPositionDto posDto = dto.positions().getFirst();
        assertThat(posDto.unrealizedPl()).isEqualByComparingTo("200");
        assertThat(posDto.unrealizedPlPct()).isEqualByComparingTo("20.00");
        assertThat(posDto.price()).isNull();
        assertThat(posDto.dayChangePercent()).isNull();
    }

    @Test void listConnectionsFailureReturnsEmptyList() {
        AgoraDepotClient depotClient = Mockito.mock(AgoraDepotClient.class);
        AgoraClient agora = Mockito.mock(AgoraClient.class);
        when(depotClient.listConnections()).thenThrow(new DepotUnavailableException("agora completely down"));

        DepotService service = new DepotService(depotClient, agora, LIVE_EMAILS);
        assertThat(service.depots("viktor@ufelmann.de")).isEmpty();
    }
}
