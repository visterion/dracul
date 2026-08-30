package de.visterion.dracul.depot;

import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Triggers the backfill. An endpoint rather than a cron or a startup hook: the run is
 * one-off and idempotent, and it must be repeatable after the book improves — without a
 * redeploy. A startup hook would fire on every deploy and be governed only by a property
 * nobody remembers.
 *
 * <p>Changes no broker data, only a derived table, and cannot touch a MEASURED row by
 * construction (see {@code DepotEquitySnapshotRepository.upsertReconstructed}).
 */
@RestController
@RequestMapping("/api/depots")
public class DepotEquityBackfillController {

    private final DepotEquityBackfillService service;

    public DepotEquityBackfillController(DepotEquityBackfillService service) {
        this.service = service;
    }

    @PostMapping("/{connection}/equity/backfill")
    public DepotEquityBackfillService.BackfillReport backfill(@PathVariable String connection) {
        try {
            return service.run(connection);
        } catch (DepotEquityBackfillService.BackfillConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (DepotUnavailableException | AgoraUnavailableException e) {
            // DepotUnavailableException: AgoraDepotClient.positions() failed (broker holdings
            // unreachable). AgoraUnavailableException: AgoraClient.callTool("get_ohlc", ...)
            // failed (price/FX series unreachable) -- see AgoraClient.callTool, which wraps
            // every terminal failure in this type, never DepotUnavailableException (that type
            // lives in this package and is thrown only by AgoraDepotClient). Either way nothing
            // was written -- the service builds the whole run in memory first -- so a retry is
            // safe.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }
}
