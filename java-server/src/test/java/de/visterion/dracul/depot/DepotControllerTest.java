package de.visterion.dracul.depot;

import de.visterion.dracul.auth.CurrentUserHolder;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DepotControllerTest {

    @AfterEach
    void clearUser() {
        CurrentUserHolder.clear();
    }

    @Test
    void passesCurrentUserToService() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        when(service.depots("alice@x.com", false)).thenReturn(List.of());

        var out = newController(service).depots(false);

        assertThat(out.depots()).isEmpty();
        assertThat(out.error()).isNull();
        verify(service).depots("alice@x.com", false);
    }

    @Test
    void agoraDownYieldsEmptyListWithError() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        when(service.depots("alice@x.com", false)).thenThrow(new DepotUnavailableException("agora down"));

        var out = newController(service).depots(false);

        assertThat(out.depots()).isEmpty();
        assertThat(out.error()).isEqualTo("agora down");
    }

    @Test
    void depotsEndpointForwardsRefreshFalseByDefault() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        when(service.depots(any(), eq(false))).thenReturn(List.of());

        newController(service).depots(false);

        verify(service).depots(any(), eq(false));
    }

    @Test
    void depotsEndpointForwardsRefreshTrueWhenRequested() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        when(service.depots(any(), eq(true))).thenReturn(List.of());

        newController(service).depots(true);

        verify(service).depots(any(), eq(true));
    }

    @Test
    void resolveDepotUsesSingleConnectionServiceMethodNotAllConnections() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        when(service.depot(eq("conn-1"), any(), eq(false))).thenReturn(null);

        var controller = newController(service);

        assertThatThrownBy(() -> controller.positionDetail("conn-1", "ACME"))
                .isInstanceOf(ResponseStatusException.class);

        verify(service).depot(eq("conn-1"), any(), eq(false));
        verify(service, never()).depots(any());
        verify(service, never()).depots(any(), anyBoolean());
    }

    @Test
    void resolveDepotThrows404WhenConnectionUnknown() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        when(service.depot(eq("depot-x"), any(), eq(false))).thenReturn(null);

        var controller = newController(service);

        assertThatThrownBy(() -> controller.positionDetail("depot-x", "AAPL"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownConnectionIs404() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        when(service.depot("missing-conn", "alice@x.com", false)).thenReturn(null);

        var controller = newController(service);

        assertThatThrownBy(() -> controller.positionDetail("missing-conn", "ACME"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownPositionIs404() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        DepotDto depot = depotWithPosition("conn-1", "ACME");
        when(service.depot("conn-1", "alice@x.com", false)).thenReturn(depot);

        var controller = newController(service);

        assertThatThrownBy(() -> controller.positionDetail("conn-1", "MSFT"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void brokerDownConnectionIs503() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        DepotDto depot = new DepotDto("conn-1", "alpaca", "paper", "connected", "2026-07-11T12:00:00Z",
                "agora down", null, null, null, null, null);
        when(service.depot("conn-1", "alice@x.com", false)).thenReturn(depot);

        var controller = newController(service);

        assertThatThrownBy(() -> controller.positionDetail("conn-1", "ACME"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void nullPositionsWithoutErrorIsAlso503() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        DepotDto depot = new DepotDto("conn-1", "alpaca", "paper", "connected", "2026-07-11T12:00:00Z",
                null, null, null, null, null, null);
        when(service.depot("conn-1", "alice@x.com", false)).thenReturn(depot);

        var controller = newController(service);

        assertThatThrownBy(() -> controller.positionDetail("conn-1", "ACME"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void serviceUnavailableExceptionIs503ForPositionDetail() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        when(service.depot("conn-1", "alice@x.com", false)).thenThrow(new DepotUnavailableException("agora down"));

        var controller = newController(service);

        assertThatThrownBy(() -> controller.positionDetail("conn-1", "ACME"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void foundPositionReturnsSliceWithOnlyThatSymbolsOrders() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        DepotDto depot = depotWithPosition("conn-1", "ACME");
        when(service.depot("conn-1", "alice@x.com", false)).thenReturn(depot);

        var out = newController(service).positionDetail("conn-1", "ACME");

        assertThat(out.depot().id()).isEqualTo("conn-1");
        assertThat(out.depot().provider()).isEqualTo("alpaca");
        assertThat(out.depot().environment()).isEqualTo("paper");
        assertThat(out.position().symbol()).isEqualTo("ACME");
        assertThat(out.orders()).extracting(DepotOrder::symbol).containsOnly("ACME");
        assertThat(out.asOf()).isEqualTo("2026-07-11T12:00:00Z");
        // newController() wires a bare mock(DepotHistoryService.class), which defaults to
        // null for runIdForOpenPosition and an empty list for movesForOpenPosition —
        // asserting explicitly locks in "no link = null / empty".
        assertThat(out.runId()).isNull();
        assertThat(out.moves()).isEmpty();
    }

    @Test
    void foundPositionCarriesHeuristicRunIdFromHistoryService() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        DepotDto depot = depotWithPosition("conn-1", "ACME");
        when(service.depot("conn-1", "alice@x.com", false)).thenReturn(depot);
        var historyService = mock(DepotHistoryService.class);
        when(historyService.runIdForOpenPosition("conn-1", "ACME")).thenReturn("run-heuristic-1");

        var controller = new DepotController(service, mock(DepotChartService.class),
                mock(DepotInstrumentService.class), historyService, mock(VistierieClient.class),
                mock(PreyRepository.class));

        var out = controller.positionDetail("conn-1", "ACME");

        assertThat(out.runId()).isEqualTo("run-heuristic-1");
        verify(historyService).runIdForOpenPosition("conn-1", "ACME");
    }

    @Test
    void foundPositionCarriesMoveTimelineFromHistoryService() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        DepotDto depot = depotWithPosition("conn-1", "ACME");
        when(service.depot("conn-1", "alice@x.com", false)).thenReturn(depot);
        var historyService = mock(DepotHistoryService.class);
        List<DepotMove> moves = List.of(
                new DepotMove("ENTER", "OK", "2026-07-01T10:00:00Z", "run-enter"),
                new DepotMove("TRIM", "T2_TARGET", "2026-07-05T10:00:00Z", "run-trim"));
        when(historyService.movesForOpenPosition("conn-1", "ACME")).thenReturn(moves);

        var controller = new DepotController(service, mock(DepotChartService.class),
                mock(DepotInstrumentService.class), historyService, mock(VistierieClient.class),
                mock(PreyRepository.class));

        var out = controller.positionDetail("conn-1", "ACME");

        assertThat(out.moves()).isEqualTo(moves);
        verify(historyService).movesForOpenPosition("conn-1", "ACME");
    }

    @Test
    void chartDelegatesToChartService() {
        var service = mock(DepotService.class);
        var chartService = mock(DepotChartService.class);
        var chart = new DepotChartService.InstrumentChart("ACME", "1y",
                List.of(new DepotChartService.ChartPoint("2026-07-01", BigDecimal.TEN)));
        when(chartService.instrumentChart("ACME", "1y")).thenReturn(chart);

        var controller = new DepotController(service, chartService, mock(DepotInstrumentService.class), mock(DepotHistoryService.class), mock(VistierieClient.class), mock(PreyRepository.class));
        var out = controller.chart("ACME", "1y");

        assertThat(out.symbol()).isEqualTo("ACME");
        assertThat(out.range()).isEqualTo("1y");
        assertThat(out.points()).containsExactly(new DepotChartService.ChartPoint("2026-07-01", BigDecimal.TEN));
    }

    @Test
    void depotChartUnknownConnectionIs404() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        when(service.depot("missing-conn", "alice@x.com", false)).thenReturn(null);
        var controller = new DepotController(service, mock(DepotChartService.class), mock(DepotInstrumentService.class), mock(DepotHistoryService.class), mock(VistierieClient.class), mock(PreyRepository.class));

        assertThatThrownBy(() -> controller.depotChart("missing-conn", "1y"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void depotChartBrokenConnectionIs503() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        DepotDto depot = new DepotDto("conn-1", "alpaca", "paper", "connected", "2026-07-11T12:00:00Z",
                "agora down", null, null, null, null, null);
        when(service.depot("conn-1", "alice@x.com", false)).thenReturn(depot);
        var controller = new DepotController(service, mock(DepotChartService.class), mock(DepotInstrumentService.class), mock(DepotHistoryService.class), mock(VistierieClient.class), mock(PreyRepository.class));

        assertThatThrownBy(() -> controller.depotChart("conn-1", "1y"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void depotChartComposesCurveFromResolvedDepotPositions() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        DepotDto depot = depotWithPosition("conn-1", "ACME");
        when(service.depot("conn-1", "alice@x.com", false)).thenReturn(depot);

        var chartService = mock(DepotChartService.class);
        var curve = new DepotChartService.DepotCurve(
                List.of(new DepotChartService.ChartPoint("2026-07-01", new BigDecimal("2250.00"))),
                List.of(new DepotChartService.RelativePoint("2026-07-01", BigDecimal.ZERO)),
                false);
        when(chartService.depotCurve(eq("1y"), any(), any())).thenReturn(curve);

        var controller = new DepotController(service, chartService, mock(DepotInstrumentService.class), mock(DepotHistoryService.class), mock(VistierieClient.class), mock(PreyRepository.class));
        var out = controller.depotChart("conn-1", "1y");

        assertThat(out.connection()).isEqualTo("conn-1");
        assertThat(out.range()).isEqualTo("1y");
        assertThat(out.points()).isEqualTo(curve.points());
        assertThat(out.relative()).isEqualTo(curve.relative());
        assertThat(out.partial()).isFalse();
        verify(chartService).depotCurve(eq("1y"), any(), any());
    }

    @Test
    void instrumentDelegatesToInstrumentServiceAndFlattensBundle() {
        var service = mock(DepotService.class);
        var instrumentService = mock(DepotInstrumentService.class);
        var mapper = new tools.jackson.databind.ObjectMapper();
        tools.jackson.databind.JsonNode profile = mapper.readTree("{\"name\":\"Acme\"}");
        var bundle = new DepotInstrumentService.InstrumentBundle(
                "ACME", profile, null, null, null, null, null, null, null);
        when(instrumentService.bundle("ACME")).thenReturn(bundle);

        var controller = new DepotController(service, mock(DepotChartService.class), instrumentService, mock(DepotHistoryService.class), mock(VistierieClient.class), mock(PreyRepository.class));
        var out = controller.instrument("ACME");

        assertThat(out.symbol()).isEqualTo("ACME");
        assertThat(out.profile()).isEqualTo(profile);
        assertThat(out.news()).isNull();
        assertThat(out.insiderActivity()).isNull();
        verify(instrumentService).bundle("ACME");
    }

    private DepotController newController(DepotService service) {
        return new DepotController(service, mock(DepotChartService.class), mock(DepotInstrumentService.class), mock(DepotHistoryService.class), mock(VistierieClient.class), mock(PreyRepository.class));
    }

    private DepotDto depotWithPosition(String connId, String symbol) {
        DepotPositionDto position = new DepotPositionDto(symbol, BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.valueOf(100), "USD",
                null, null, null, null, null);
        List<DepotOrder> orders = List.of(
                new DepotOrder("o1", symbol, "buy", BigDecimal.ONE, "market", "filled", "entry", null, null, null, null),
                new DepotOrder("o2", "OTHER", "sell", BigDecimal.ONE, "market", "filled", "exit", "o1", null, null, null));
        return new DepotDto(connId, "alpaca", "paper", "connected", "2026-07-11T12:00:00Z", null,
                null, null, List.of(position), orders, "2026-07-11T12:00:00Z");
    }
}
