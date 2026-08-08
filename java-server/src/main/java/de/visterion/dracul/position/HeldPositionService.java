package de.visterion.dracul.position;

import de.visterion.dracul.depot.AgoraDepotClient;
import de.visterion.dracul.depot.DepotPosition;
import de.visterion.dracul.depot.DepotUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The read model that joins live depot positions to their research context: for each open
 * depot position (from {@link AgoraDepotClient}), left-joins its OPEN {@code position_context}
 * row by symbol. Every depot position is included -- a position with no open context row is
 * TA-only and rides through with a null context block, never dropped.
 *
 * <p>Fail-soft: a {@link DepotUnavailableException} (the broker/depot is unreachable) yields
 * an empty list rather than propagating, mirroring how {@code DepotService} treats a down
 * connection as absent rather than fatal.
 */
@Service
public class HeldPositionService {

    private static final Logger log = LoggerFactory.getLogger(HeldPositionService.class);

    private final AgoraDepotClient depotClient;
    private final PositionContextRepository contextRepo;

    public HeldPositionService(AgoraDepotClient depotClient, PositionContextRepository contextRepo) {
        this.depotClient = depotClient;
        this.contextRepo = contextRepo;
    }

    /**
     * The open positions for one connection PLUS whether the depot actually answered.
     * {@code available == false} means the read failed ({@link DepotUnavailableException});
     * the position list is then empty. Callers that must not confuse "the depot is empty"
     * with "the depot is down" -- renfield's payload does exactly that -- use this method;
     * everyone else keeps {@link #openPositions(String)}.
     */
    public record OpenPositions(List<HeldPosition> positions, boolean available) {}

    /**
     * Every open depot position for {@code connection}, left-joined by symbol to its context.
     * Fail-soft: an unreachable depot yields an EMPTY LIST, indistinguishable from an empty
     * depot. That is deliberate and depended upon (Gropar's "empty list =&gt; pause" rule,
     * {@code FxRateRefresher}, {@code DaywalkerCompletionService}, the Lazarus path) -- do
     * not change it. Use {@link #openPositionsOrUnavailable(String)} when the difference matters.
     */
    public List<HeldPosition> openPositions(String connection) {
        return openPositionsOrUnavailable(connection).positions();
    }

    /** Same read as {@link #openPositions(String)}, but reporting whether the depot answered. */
    public OpenPositions openPositionsOrUnavailable(String connection) {
        List<DepotPosition> positions;
        try {
            positions = depotClient.positions(connection).positions();
        } catch (DepotUnavailableException e) {
            log.warn("depot unavailable for connection {}: {}", connection, e.toString());
            return new OpenPositions(List.of(), false);
        }

        List<HeldPosition> result = new ArrayList<>();
        for (DepotPosition p : positions) {
            Optional<PositionContextRow> context = contextRepo.findOpenBySymbol(connection, p.symbol());
            result.add(join(p, context.orElse(null)));
        }
        return new OpenPositions(result, true);
    }

    private HeldPosition join(DepotPosition p, PositionContextRow ctx) {
        if (ctx == null) {
            return new HeldPosition(p.symbol(), p.qty(), p.avgEntryPrice(), p.marketValue(),
                    p.unrealizedPl(), p.currency(), null, null, null, null, null, null, null, null);
        }
        return new HeldPosition(p.symbol(), p.qty(), p.avgEntryPrice(), p.marketValue(),
                p.unrealizedPl(), p.currency(), ctx.verdictId(), ctx.killCriteria(), ctx.horizon(),
                ctx.thesisSnapshot(), ctx.initialStop(), ctx.activeStop(), ctx.source(),
                ctx.openedAt());
    }
}
