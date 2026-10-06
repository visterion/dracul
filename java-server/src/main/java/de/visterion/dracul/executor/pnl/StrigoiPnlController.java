package de.visterion.dracul.executor.pnl;

import de.visterion.dracul.auth.CurrentUserHolder;
import de.visterion.dracul.depot.DepotService;
import de.visterion.dracul.depot.DepotUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 */
@RestController
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
@RequestMapping("/api/executor/pnl")
public class StrigoiPnlController {

    private final StrigoiPnlService service;
    private final DepotService depots;
    private final String defaultConnection;

    public StrigoiPnlController(StrigoiPnlService service, DepotService depots,
            @Value("${dracul.executor.connection:depot-1}") String defaultConnection) {
        this.service = service;
        this.depots = depots;
        this.defaultConnection = defaultConnection;
    }

    @GetMapping("/strigoi")
    public StrigoiPnlOverview overview(@RequestParam(name = "connection", required = false) String connection) {
        return service.overview(visibleConnection(connection));
    }

    @GetMapping("/strigoi/{name}")
    public StrigoiPnlDetail detail(@PathVariable String name,
            @RequestParam(name = "connection", required = false) String connection) {
        return service.detail(visibleConnection(connection), name);
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
