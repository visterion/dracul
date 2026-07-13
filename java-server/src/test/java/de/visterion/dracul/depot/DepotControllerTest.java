package de.visterion.dracul.depot;

import de.visterion.dracul.auth.CurrentUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(service.depots("alice@x.com")).thenReturn(List.of());

        var out = newController(service).depots();

        assertThat(out.depots()).isEmpty();
        assertThat(out.error()).isNull();
        verify(service).depots("alice@x.com");
    }

    @Test
    void agoraDownYieldsEmptyListWithError() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        when(service.depots("alice@x.com")).thenThrow(new DepotUnavailableException("agora down"));

        var out = newController(service).depots();

        assertThat(out.depots()).isEmpty();
        assertThat(out.error()).isEqualTo("agora down");
    }

    @Test
    void unknownConnectionIs404() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        when(service.depots("alice@x.com")).thenReturn(List.of());

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
        when(service.depots("alice@x.com")).thenReturn(List.of(depot));

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
        when(service.depots("alice@x.com")).thenReturn(List.of(depot));

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
        when(service.depots("alice@x.com")).thenReturn(List.of(depot));

        var controller = newController(service);

        assertThatThrownBy(() -> controller.positionDetail("conn-1", "ACME"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void serviceUnavailableExceptionIs503ForPositionDetail() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        when(service.depots("alice@x.com")).thenThrow(new DepotUnavailableException("agora down"));

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
        when(service.depots("alice@x.com")).thenReturn(List.of(depot));

        var out = newController(service).positionDetail("conn-1", "ACME");

        assertThat(out.depot().id()).isEqualTo("conn-1");
        assertThat(out.depot().provider()).isEqualTo("alpaca");
        assertThat(out.depot().environment()).isEqualTo("paper");
        assertThat(out.position().symbol()).isEqualTo("ACME");
        assertThat(out.orders()).extracting(DepotOrder::symbol).containsOnly("ACME");
        assertThat(out.asOf()).isEqualTo("2026-07-11T12:00:00Z");
    }

    @Test
    void chartDelegatesToChartService() {
        var service = mock(DepotService.class);
        var chartService = mock(DepotChartService.class);
        var chart = new DepotChartService.InstrumentChart("ACME", "1y",
                List.of(new DepotChartService.ChartPoint("2026-07-01", BigDecimal.TEN)));
        when(chartService.instrumentChart("ACME", "1y")).thenReturn(chart);

        var controller = new DepotController(service, chartService, mock(DepotInstrumentService.class));
        var out = controller.chart("ACME", "1y");

        assertThat(out.symbol()).isEqualTo("ACME");
        assertThat(out.range()).isEqualTo("1y");
        assertThat(out.points()).containsExactly(new DepotChartService.ChartPoint("2026-07-01", BigDecimal.TEN));
    }

    @Test
    void depotChartUnknownConnectionIs404() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        when(service.depots("alice@x.com")).thenReturn(List.of());
        var controller = new DepotController(service, mock(DepotChartService.class), mock(DepotInstrumentService.class));

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
        when(service.depots("alice@x.com")).thenReturn(List.of(depot));
        var controller = new DepotController(service, mock(DepotChartService.class), mock(DepotInstrumentService.class));

        assertThatThrownBy(() -> controller.depotChart("conn-1", "1y"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void depotChartComposesCurveFromResolvedDepotPositions() {
        CurrentUserHolder.set("alice@x.com");
        var service = mock(DepotService.class);
        DepotDto depot = depotWithPosition("conn-1", "ACME");
        when(service.depots("alice@x.com")).thenReturn(List.of(depot));

        var chartService = mock(DepotChartService.class);
        var curve = new DepotChartService.DepotCurve(
                List.of(new DepotChartService.ChartPoint("2026-07-01", new BigDecimal("2250.00"))),
                List.of(new DepotChartService.RelativePoint("2026-07-01", BigDecimal.ZERO)),
                false);
        when(chartService.depotCurve(eq("1y"), any(), any())).thenReturn(curve);

        var controller = new DepotController(service, chartService, mock(DepotInstrumentService.class));
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

        var controller = new DepotController(service, mock(DepotChartService.class), instrumentService);
        var out = controller.instrument("ACME");

        assertThat(out.symbol()).isEqualTo("ACME");
        assertThat(out.profile()).isEqualTo(profile);
        assertThat(out.news()).isNull();
        assertThat(out.insiderActivity()).isNull();
        verify(instrumentService).bundle("ACME");
    }

    private DepotController newController(DepotService service) {
        return new DepotController(service, mock(DepotChartService.class), mock(DepotInstrumentService.class));
    }

    private DepotDto depotWithPosition(String connId, String symbol) {
        DepotPositionDto position = new DepotPositionDto(symbol, BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.valueOf(100), "USD",
                null, null, null, null, null);
        List<DepotOrder> orders = List.of(
                new DepotOrder("o1", symbol, "buy", BigDecimal.ONE, "market", "filled", "entry"),
                new DepotOrder("o2", "OTHER", "sell", BigDecimal.ONE, "market", "filled", "exit"));
        return new DepotDto(connId, "alpaca", "paper", "connected", "2026-07-11T12:00:00Z", null,
                null, null, List.of(position), orders, "2026-07-11T12:00:00Z");
    }
}
