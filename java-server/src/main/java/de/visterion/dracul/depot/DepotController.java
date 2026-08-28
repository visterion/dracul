package de.visterion.dracul.depot;

import de.visterion.dracul.auth.CurrentUserHolder;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.vistierie.VistierieClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * GUI-facing read path for depot connections: the full list ({@link DepotsResponse}, with
 * live-environment connections gated inside {@link DepotService} to allow-listed emails), a
 * per-position slice for the position-detail view, and chart data (a raw instrument series, plus
 * the measured depot equity curve from {@link DepotEquityCurveService}).
 */
@RestController
@RequestMapping("/api/depots")
public class DepotController {

    private final DepotService service;
    private final DepotChartService chartService;
    private final DepotInstrumentService instrumentService;
    private final DepotHistoryService historyService;
    private final VistierieClient vistierie;
    private final PreyRepository prey;
    private final DepotEquityCurveService curveService;

    public DepotController(DepotService service, DepotChartService chartService,
            DepotInstrumentService instrumentService, DepotHistoryService historyService,
            VistierieClient vistierie, PreyRepository prey, DepotEquityCurveService curveService) {
        this.service = service;
        this.chartService = chartService;
        this.instrumentService = instrumentService;
        this.historyService = historyService;
        this.vistierie = vistierie;
        this.prey = prey;
        this.curveService = curveService;
    }

    @GetMapping
    public DepotsResponse depots(@RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        try {
            return new DepotsResponse(service.depots(CurrentUserHolder.get(), refresh), null);
        } catch (DepotUnavailableException e) {
            return new DepotsResponse(List.of(), e.getMessage());
        }
    }

    @GetMapping("/{connection}/positions/{symbol}")
    public PositionDetailResponse positionDetail(@PathVariable String connection, @PathVariable String symbol) {
        DepotDto depot = resolveDepot(connection);

        DepotPositionDto position = depot.positions().stream()
                .filter(p -> symbol.equals(p.symbol()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown position"));

        List<DepotOrder> orders = depot.orders() == null ? List.of() : depot.orders().stream()
                .filter(o -> symbol.equals(o.symbol()))
                .toList();

        String runId = historyService.runIdForOpenPosition(connection, symbol);
        List<DepotMove> moves = historyService.movesForOpenPosition(connection, symbol);
        return new PositionDetailResponse(
                new PositionDetailResponse.DepotSummary(depot.id(), depot.provider(), depot.environment()),
                position, orders, depot.asOf(), runId, moves);
    }

    @GetMapping("/{connection}/history")
    public DepotHistoryResponse history(@PathVariable String connection) {
        try {
            return new DepotHistoryResponse(historyService.history(connection, CurrentUserHolder.get()), null);
        } catch (DepotUnavailableException e) {
            return new DepotHistoryResponse(List.of(), e.getMessage());
        }
    }

    /**
     * Raw Vistierie run transcript (Schicht 2): exact prompt + raw LLM answer + tool results,
     * un-truncated ({@code view=full}), proxied read-only. Returns {@code {transcript:null,
     * expired:true}} when Vistierie pruned the run or is unreachable (never 500).
     * <p>Ownership-scoped: {@code runId} must belong to a {@link de.visterion.dracul.prey.Prey}
     * discovered for the current user, otherwise 404 (not 403 — avoids leaking whether a run
     * exists at all) to prevent one user reading another user's run transcript.
     */
    @GetMapping("/run/{runId}/transcript")
    public TranscriptResponse transcript(@PathVariable String runId) {
        if (!prey.runExistsForUser(runId, CurrentUserHolder.get())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown run");
        }
        JsonNode node = vistierie.getRunTranscript(runId, "full");
        return new TranscriptResponse(node, node == null);
    }

    @GetMapping("/chart")
    public ChartResponse chart(@RequestParam String symbol, @RequestParam String range) {
        DepotChartService.InstrumentChart chart = chartService.instrumentChart(symbol, range);
        return new ChartResponse(chart.symbol(), chart.range(), chart.points());
    }

    @GetMapping("/{connection}/chart")
    public DepotChartResponse depotChart(@PathVariable String connection,
                                         @RequestParam String range) {
        // resolveDepot stays: it carries the live-depot allow-list and the 404/503 contract the
        // frontend depends on. The curve itself no longer needs any broker data.
        resolveDepot(connection);

        DepotEquityCurveService.EquityCurve curve = curveService.curve(connection, range);
        return new DepotChartResponse(connection, range, curve.granularity(),
                curve.points(), curve.relative(), curve.currency());
    }

    /**
     * Instrument info bundle for the GUI's instrument page: profile, news, earnings window,
     * analyst estimates, earnings estimates, fundamental score, fundamentals, and insider
     * activity — ungated market data (no depot/live-visibility concern, unlike the endpoints
     * above). Each section is independently nullable; see {@link DepotInstrumentService}.
     */
    @GetMapping("/instrument/{symbol}")
    public InstrumentResponse instrument(@PathVariable String symbol) {
        DepotInstrumentService.InstrumentBundle bundle = instrumentService.bundle(symbol);
        return new InstrumentResponse(bundle.symbol(), bundle.profile(), bundle.news(), bundle.earnings(),
                bundle.analystEstimates(), bundle.earningsEstimates(), bundle.fundamentalScore(),
                bundle.fundamentals(), bundle.insiderActivity());
    }

    /**
     * Resolves a depot connection to its {@link DepotDto}, gating on Agora availability, the
     * connection existing, and the depot's own fetch having succeeded. Shared by
     * {@link #positionDetail} and {@link #depotChart} which both need a live, error-free depot.
     */
    private DepotDto resolveDepot(String connection) {
        DepotDto depot;
        try {
            depot = service.depot(connection, CurrentUserHolder.get(), false);
        } catch (DepotUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }

        if (depot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown depot connection");
        }
        if (depot.error() != null || depot.positions() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, depot.error());
        }

        return depot;
    }

    /** Response wrapper for {@code GET /api/depots}; {@code error} is non-null when Agora is unavailable. */
    public record DepotsResponse(List<DepotDto> depots, String error) {
    }

    /** Response for the per-position slice: the owning depot's identity, the position, its
     *  orders, — for open positions only — a heuristic run_id (symbol-linked, see
     *  {@link DepotHistoryService#runIdForOpenPosition}) driving the raw-transcript drilldown
     *  ({@code null} when unlinkable), and the position's move timeline (see
     *  {@link DepotHistoryService#movesForOpenPosition}, empty when unlinkable). */
    public record PositionDetailResponse(DepotSummary depot, DepotPositionDto position,
            List<DepotOrder> orders, String asOf, String runId, List<DepotMove> moves) {
        public record DepotSummary(String id, String provider, String environment) {
        }
    }

    /** Response for {@code GET /api/depots/chart}: one instrument's raw close series. */
    public record ChartResponse(String symbol, String range, List<DepotChartService.ChartPoint> points) {
    }

    /** Response for {@code GET /api/depots/{connection}/chart}: the measured equity curve.
     *  {@code granularity} is the frontend's branch condition for how to read {@code t}
     *  ({@code YYYY-MM-DD} vs an ISO instant); {@code currency} is null while the connection has
     *  no snapshot yet. */
    public record DepotChartResponse(String connection, String range, String granularity,
                                     List<DepotEquityCurveService.CurvePoint> points,
                                     List<DepotEquityCurveService.RelativePoint> relative,
                                     String currency) {
    }

    /**
     * Response for {@code GET /api/depots/instrument/{symbol}}: each section is the raw Agora
     * tool output, {@code null} when that section's call failed.
     */
    public record InstrumentResponse(String symbol, JsonNode profile, JsonNode news, JsonNode earnings,
            JsonNode analystEstimates, JsonNode earningsEstimates, JsonNode fundamentalScore,
            JsonNode fundamentals, JsonNode insiderActivity) {
    }

    /**
     * Response for {@code GET /api/depots/run/{runId}/transcript}: the raw Vistierie transcript
     * body, or {@code expired:true} when Vistierie has none (pruned run or unreachable).
     */
    public record TranscriptResponse(JsonNode transcript, boolean expired) {
    }
}
