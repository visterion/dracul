package de.visterion.dracul.depot;

import de.visterion.dracul.auth.CurrentUserHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

/**
 * GUI-facing read path for depot connections: the full list ({@link DepotsResponse}, with
 * live-environment connections gated inside {@link DepotService} to allow-listed emails), a
 * per-position slice for the position-detail view, and chart data (a raw instrument series, plus
 * a composed depot performance curve).
 */
@RestController
@RequestMapping("/api/depots")
public class DepotController {

    private final DepotService service;
    private final DepotChartService chartService;

    public DepotController(DepotService service, DepotChartService chartService) {
        this.service = service;
        this.chartService = chartService;
    }

    @GetMapping
    public DepotsResponse depots() {
        try {
            return new DepotsResponse(service.depots(CurrentUserHolder.get()), null);
        } catch (DepotUnavailableException e) {
            return new DepotsResponse(List.of(), e.getMessage());
        }
    }

    @GetMapping("/{connection}/positions/{symbol}")
    public PositionDetailResponse positionDetail(@PathVariable String connection, @PathVariable String symbol) {
        List<DepotDto> depots;
        try {
            depots = service.depots(CurrentUserHolder.get());
        } catch (DepotUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }

        DepotDto depot = depots.stream()
                .filter(d -> connection.equals(d.id()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown depot connection"));

        if (depot.error() != null || depot.positions() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, depot.error());
        }

        DepotPositionDto position = depot.positions().stream()
                .filter(p -> symbol.equals(p.symbol()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown position"));

        List<DepotOrder> orders = depot.orders() == null ? List.of() : depot.orders().stream()
                .filter(o -> symbol.equals(o.symbol()))
                .toList();

        return new PositionDetailResponse(
                new PositionDetailResponse.DepotSummary(depot.id(), depot.provider(), depot.environment()),
                position, orders, depot.asOf());
    }

    @GetMapping("/chart")
    public ChartResponse chart(@RequestParam String symbol, @RequestParam String range) {
        DepotChartService.InstrumentChart chart = chartService.instrumentChart(symbol, range);
        return new ChartResponse(chart.symbol(), chart.range(), chart.points());
    }

    @GetMapping("/{connection}/chart")
    public DepotChartResponse depotChart(@PathVariable String connection, @RequestParam String range) {
        List<DepotDto> depots;
        try {
            depots = service.depots(CurrentUserHolder.get());
        } catch (DepotUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }

        DepotDto depot = depots.stream()
                .filter(d -> connection.equals(d.id()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown depot connection"));

        if (depot.error() != null || depot.positions() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, depot.error());
        }

        List<DepotPosition> positions = depot.positions().stream()
                .map(p -> new DepotPosition(p.symbol(), p.qty(), null, null, null, null))
                .toList();
        BigDecimal cash = depot.account() != null ? depot.account().cash() : null;

        DepotChartService.DepotCurve curve = chartService.depotCurve(range, positions, cash);
        return new DepotChartResponse(connection, range, curve.points(), curve.relative(), curve.partial());
    }

    /** Response wrapper for {@code GET /api/depots}; {@code error} is non-null when Agora is unavailable. */
    public record DepotsResponse(List<DepotDto> depots, String error) {
    }

    /** Response for the per-position slice: the owning depot's identity, the position, and its orders. */
    public record PositionDetailResponse(DepotSummary depot, DepotPositionDto position,
            List<DepotOrder> orders, String asOf) {
        public record DepotSummary(String id, String provider, String environment) {
        }
    }

    /** Response for {@code GET /api/depots/chart}: one instrument's raw close series. */
    public record ChartResponse(String symbol, String range, List<DepotChartService.ChartPoint> points) {
    }

    /** Response for {@code GET /api/depots/{connection}/chart}: the composed depot performance curve. */
    public record DepotChartResponse(String connection, String range,
            List<DepotChartService.ChartPoint> points, List<DepotChartService.RelativePoint> relative,
            boolean partial) {
    }
}
