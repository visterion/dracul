package de.visterion.dracul.report;

import de.visterion.dracul.gropar.ExitSignal;
import de.visterion.dracul.gropar.ExitSignalRepository;
import de.visterion.dracul.watchlist.PositionRisk;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure projection: held positions + persisted risk snapshot + latest exit
 *  signal -> the day's morning report. No market-data, no LLM -- total and
 *  side-effect free, so the scheduler and the REST endpoint share one source. */
@Service
public class MorningReportService {

    private static final Map<String, Integer> ACTION_RANK =
            Map.of("SELL", 0, "TRIM", 1, "HOLD", 2);

    private final WatchlistRepository watchlist;
    private final ExitSignalRepository exitSignals;

    public MorningReportService(WatchlistRepository watchlist, ExitSignalRepository exitSignals) {
        this.watchlist = watchlist;
        this.exitSignals = exitSignals;
    }

    public MorningReport build(String owner) {
        Map<String, PositionRisk> riskById = watchlist.positionRiskByItemId();
        Map<String, ExitSignal> latestByItem = latestSignalByItem(owner);

        List<MorningReportLine> lines = new ArrayList<>();
        for (WatchlistItem item : watchlist.findAllByUser(owner)) {
            if (!isHeld(item)) continue;
            lines.add(toLine(item, riskById.get(item.id()), latestByItem.get(item.id())));
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

    private Map<String, ExitSignal> latestSignalByItem(String owner) {
        Map<String, ExitSignal> latest = new HashMap<>();
        // findLatestByUser returns newest-first; first seen per item wins.
        for (ExitSignal s : exitSignals.findLatestByUser(owner, 100)) {
            if (s.watchlistItemId() != null) latest.putIfAbsent(s.watchlistItemId(), s);
        }
        return latest;
    }

    private MorningReportLine toLine(WatchlistItem item, PositionRisk pr, ExitSignal sig) {
        String action = sig == null ? "HOLD" : sig.action();
        BigDecimal activeStop  = pr == null ? null : pr.activeStop();
        BigDecimal target      = pr == null ? null : pr.nextTarget2r();
        BigDecimal close       = pr == null ? null : pr.currentClose();

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

        double shares = item.shareCount();
        double ticketShares = switch (action) {
            case "SELL" -> shares;
            case "TRIM" -> Math.floor(shares / 3.0);
            default -> 0.0;
        };
        OrderTicket ticket = new OrderTicket(action, item.ticker(), ticketShares,
                close, activeStop, target);

        boolean targetReached = target != null && activeStop != null
                && target.compareTo(activeStop) <= 0;

        return new MorningReportLine(
                item.ticker(), item.companyName(), shares, item.entryPrice(),
                close, activeStop, target, distancePct,
                action,
                thesisStatus,
                sig == null ? null : sig.confidence(),
                rationale,
                ticket, targetReached);
    }

    private boolean isHeld(WatchlistItem item) {
        return "HELD".equals(item.tag())
                && item.entryPrice() != null
                && item.shareCount() != null;
    }
}
