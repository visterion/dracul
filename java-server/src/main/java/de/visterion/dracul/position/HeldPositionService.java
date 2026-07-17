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

    /** Every open depot position for {@code connection}, left-joined by symbol to its context. */
    public List<HeldPosition> openPositions(String connection) {
        List<DepotPosition> positions;
        try {
            positions = depotClient.positions(connection).positions();
        } catch (DepotUnavailableException e) {
            log.warn("depot unavailable for connection {}: {}", connection, e.toString());
            return List.of();
        }

        List<HeldPosition> result = new ArrayList<>();
        for (DepotPosition p : positions) {
            Optional<PositionContextRow> context = contextRepo.findOpenBySymbol(connection, p.symbol());
            result.add(join(p, context.orElse(null)));
        }
        return result;
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
