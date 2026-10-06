package de.visterion.dracul.executor.pnl;

import de.visterion.dracul.auth.CurrentUserHolder;
import de.visterion.dracul.depot.DepotService;
import de.visterion.dracul.depot.DepotUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only P&L per strigoi (spec 2026-10-06). Behind Cloudflare Access like every
 * {@code /api/**} GUI read (not in {@code CloudflareAccessFilter.EXCLUDED}) and additionally
 * behind the Depots live-visibility gate: an invisible or unknown connection is 404, an Agora
 * outage while listing connections is 503 — the {@code DepotController.resolveDepot} contract.
 * {@code connection} defaults to the executor's connection.
 *
 * <p>This bean is intentionally an unconditional {@code @RestController} (no
 * {@code @ConditionalOnProperty}), same reasoning as {@code report.DecisionDocController}:
 * {@code SpaFallbackController} maps dot-free paths of 1-4 segments, which covers
 * {@code /api/executor/pnl/strigoi} (4 segments). Were this bean absent when
 * {@code dracul.executor.enabled=false} (so {@link StrigoiPnlService} does not exist), the
 * request would fall through to the SPA and yield a 200 text/html instead of a 404. The "off"
 * state is therefore a content-level 404 via {@link ObjectProvider#getIfAvailable()}.
 */
@RestController
@RequestMapping("/api/executor/pnl")
public class StrigoiPnlController {

    private final ObjectProvider<StrigoiPnlService> service;
    private final DepotService depots;
    private final String defaultConnection;

    public StrigoiPnlController(ObjectProvider<StrigoiPnlService> service, DepotService depots,
            @Value("${dracul.executor.connection:depot-1}") String defaultConnection) {
        this.service = service;
        this.depots = depots;
        this.defaultConnection = defaultConnection;
    }

    @GetMapping("/strigoi")
    public StrigoiPnlOverview overview(@RequestParam(name = "connection", required = false) String connection) {
        StrigoiPnlService svc = requireService();
        return svc.overview(visibleConnection(connection));
    }

    @GetMapping("/strigoi/{name}")
    public StrigoiPnlDetail detail(@PathVariable String name,
            @RequestParam(name = "connection", required = false) String connection) {
        StrigoiPnlService svc = requireService();
        return svc.detail(visibleConnection(connection), name);
    }

    private StrigoiPnlService requireService() {
        StrigoiPnlService svc = service.getIfAvailable();
        if (svc == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "executor disabled");
        return svc;
    }

    private String visibleConnection(String requested) {
        String connection = requested == null || requested.isBlank() ? defaultConnection : requested;
        boolean visible;
        try {
            visible = depots.isVisible(connection, CurrentUserHolder.get());
        } catch (DepotUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
        if (!visible) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown depot connection");
        return connection;
    }
}
