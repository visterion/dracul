package de.visterion.dracul.report;

import de.visterion.dracul.gropar.ExitSignal;
import de.visterion.dracul.gropar.ExitSignalRepository;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure projection: live depot positions (⨝ research context) + latest exit
 *  signal -> the day's morning report. No market-data fetch of its own, no LLM
 *  -- total and side-effect free, so the scheduler and the REST endpoint share
 *  one source. */
@Service
public class MorningReportService {

    private static final Map<String, Integer> ACTION_RANK =
            Map.of("SELL", 0, "TRIM", 1, "HOLD", 2);

    private final HeldPositionService heldPositionService;
    private final ExitSignalRepository exitSignals;
    private final String connection;

    public MorningReportService(HeldPositionService heldPositionService, ExitSignalRepository exitSignals,
            @Value("${dracul.position.connection:depot-1}") String connection) {
        this.heldPositionService = heldPositionService;
        this.exitSignals = exitSignals;
        this.connection = connection;
    }

    public MorningReport build(String owner) {
        Map<String, ExitSignal> latestBySymbol = latestSignalBySymbol(owner);

        List<MorningReportLine> lines = new ArrayList<>();
        // openPositions is fail-soft (empty list when the depot is unreachable), so a depot-down
        // read yields an empty portfolio section here rather than throwing.
        for (HeldPosition position : heldPositionService.openPositions(connection)) {
            lines.add(toLine(position, latestBySymbol.get(position.symbol())));
        }
        lines.sort(Comparator
                .comparingInt((MorningReportLine l) -> ACTION_RANK.getOrDefault(l.action(), 2))
                .thenComparing(l -> l.distanceToStopPct(),
                        Comparator.nullsLast(Comparator.naturalOrder())));

        int sell = (int) lines.stream().filter(l -> "SELL".equals(l.action())).count();
        int trim = (int) lines.stream().filter(l -> "TRIM".equals(l.action())).count();
        int hold = lines.size() - sell - trim;
        return new MorningReport(Instant.now().toString(), sell, trim, hold, lines);
    }

    /** Keyed by symbol, not watchlist_item_id: gropar's exit signals are depot-sourced and
     *  never carry a watchlist item id (it is written NULL) -- symbol is the only identity
     *  that survives from the fetch to the signal. Rekeying here is what makes gropar's exit
     *  actions actually show up in the morning report; keying by the (now-always-null) item id
     *  would silently match nothing. */
    private Map<String, ExitSignal> latestSignalBySymbol(String owner) {
        Map<String, ExitSignal> latest = new HashMap<>();
        // findLatestByUser returns newest-first; first seen per symbol wins.
        for (ExitSignal s : exitSignals.findLatestByUser(owner, 100)) {
            if (s.symbol() != null) latest.putIfAbsent(s.symbol(), s);
        }
        return latest;
    }

    private MorningReportLine toLine(HeldPosition position, ExitSignal sig) {
        String action = sig == null ? "HOLD" : sig.action();
        BigDecimal activeStop = position.activeStop();
        // No profit-target snapshot is carried by position_context yet -- null until a source
        // for it exists, same "absent snapshot" degrade the old watchlist-driven path had.
        BigDecimal target     = null;
        BigDecimal close      = currentClose(position);

        Double distancePct = null;
        if (close != null && close.signum() != 0 && activeStop != null) {
            distancePct = close.subtract(activeStop)
                    .divide(close, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
        }

        // Deterministic stop-breach fallback: a position whose close is below its
        // active stop is objectively stopped out. If the LLM signal is absent or
        // HOLD, surface SELL so the forcing-function never hides a breach behind a
        // default HOLD. An explicit LLM SELL/TRIM is preserved.
        String thesisStatus = sig == null ? null : sig.thesisStatus();
        String rationale    = sig == null ? null : sig.rationale();
        boolean stopBreached = close != null && activeStop != null
                && close.compareTo(activeStop) < 0;
        if (stopBreached && "HOLD".equals(action)) {
            action = "SELL";
            thesisStatus = null;
            rationale = "Kurs unter aktivem Stop (deterministische Regel)";
        }

        double shares = position.quantity().doubleValue();
        double ticketShares = switch (action) {
            case "SELL" -> shares;
            case "TRIM" -> trimShares(shares);
            default -> 0.0;
        };
        OrderTicket ticket = new OrderTicket(action, position.symbol(), ticketShares,
                close, activeStop, target);

        boolean targetReached = target != null && activeStop != null
                && target.compareTo(activeStop) <= 0;

        return new MorningReportLine(
                position.symbol(), position.symbol(), shares, position.avgPrice().doubleValue(),
                close, activeStop, target, distancePct,
                action,
                thesisStatus,
                sig == null ? null : sig.confidence(),
                rationale,
                ticket, targetReached);
    }

    /** Derived from the depot's live market value / quantity -- the depot carries no explicit
     *  "current close" field, but marketValue is quantity * current price. Null when either is
     *  missing or the position has zero quantity (division-by-zero guard). */
    private static BigDecimal currentClose(HeldPosition position) {
        BigDecimal marketValue = position.marketValue();
        BigDecimal quantity = position.quantity();
        if (marketValue == null || quantity == null || quantity.signum() == 0) return null;
        return marketValue.divide(quantity, 4, RoundingMode.HALF_UP);
    }

    /** One third of the position for a TRIM ticket. Whole-share positions keep
     *  whole-share tickets (floor, unchanged behaviour); fractional positions
     *  keep 4 decimals (share_count is NUMERIC(12,4)) instead of flooring a
     *  0.73-share position to a "0 Stück" ticket. */
    private static double trimShares(double shares) {
        if (shares == Math.floor(shares)) return Math.floor(shares / 3.0);
        return BigDecimal.valueOf(shares)
                .divide(BigDecimal.valueOf(3), 4, RoundingMode.DOWN)
                .doubleValue();
    }
}
