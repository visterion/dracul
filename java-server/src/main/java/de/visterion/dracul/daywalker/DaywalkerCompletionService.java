package de.visterion.dracul.daywalker;

import de.visterion.dracul.notify.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Resolves the watchlist item for a completed Daywalker assessment, decides
 * whether the severity warrants a Telegram push (best-effort), and persists the
 * alert with the push outcome in one insert. Extracted from the controller so
 * the notify decision is unit-testable without a web context.
 */
@Component
public class DaywalkerCompletionService {

    private static final Logger log = LoggerFactory.getLogger(DaywalkerCompletionService.class);
    private static final String USER = "default";

    private final DaywalkerAlertRepository alerts;
    private final TelegramNotifier notifier;
    private final int notifyRank;

    public DaywalkerCompletionService(
            DaywalkerAlertRepository alerts,
            TelegramNotifier notifier,
            @Value("${dracul.daywalker.notify-level:CRITICAL}") String notifyLevel) {
        this.alerts = alerts;
        this.notifier = notifier;
        this.notifyRank = rank(notifyLevel);
    }

    public void persistAssessment(String symbol, String triggerType, String severity,
                                  String thesis, BigDecimal confidence, String runId) {
        var wid = alerts.resolveWatchlistItemId(USER, symbol);
        if (wid.isEmpty()) {
            log.warn("daywalker run {} unknown symbol {} — skipping", runId, symbol);
            return;
        }
        boolean sent = false;
        if (rank(severity) >= notifyRank) {
            sent = notifier.notifyAlert(symbol, triggerType, severity, thesis);
        }
        alerts.insert(USER, wid.get(), symbol, triggerType, severity, thesis, confidence, runId, sent);
        log.info("daywalker run {} persisted alert for {} ({}), notified={}",
                runId, symbol, triggerType, sent);
    }

    /** INFO < WARNING < CRITICAL; unknown severities rank as INFO. */
    private static int rank(String severity) {
        return switch (severity == null ? "" : severity.toUpperCase()) {
            case "CRITICAL" -> 3;
            case "WARNING" -> 2;
            default -> 1;
        };
    }
}
