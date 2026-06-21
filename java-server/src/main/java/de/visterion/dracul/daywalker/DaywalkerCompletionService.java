package de.visterion.dracul.daywalker;

import de.visterion.dracul.notify.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Resolves the watchlist item for a completed Daywalker assessment, decides
 * whether the severity warrants a Telegram push (best-effort), persists the
 * alert with the push outcome in one insert, and publishes an event so live
 * consumers (SSE) can react.
 */
@Component
public class DaywalkerCompletionService {

    private static final Logger log = LoggerFactory.getLogger(DaywalkerCompletionService.class);
    private static final String USER = "default";

    private final DaywalkerAlertRepository alerts;
    private final TelegramNotifier notifier;
    private final ApplicationEventPublisher events;
    private final int notifyRank;

    public DaywalkerCompletionService(
            DaywalkerAlertRepository alerts,
            TelegramNotifier notifier,
            ApplicationEventPublisher events,
            @Value("${dracul.daywalker.notify-level:CRITICAL}") String notifyLevel) {
        this.alerts = alerts;
        this.notifier = notifier;
        this.events = events;
        this.notifyRank = rank(notifyLevel);
    }

    public void persistAssessment(String symbol, String triggerType, String severity,
                                  String thesis, BigDecimal confidence, String runId) {
        var owners = alerts.findOwnersBySymbol(symbol).stream()
                .filter(o -> USER.equals(o.userId()))
                .toList();
        if (owners.isEmpty()) {
            log.warn("daywalker run {} unknown symbol {} — skipping", runId, symbol);
            return;
        }
        boolean sent = false;
        if (rank(severity) >= notifyRank) {
            sent = notifier.notifyAlert(symbol, triggerType, severity, thesis);
        }
        alerts.insert(USER, owners.get(0).watchlistItemId(), symbol, triggerType, severity, thesis, confidence, runId, sent);
        events.publishEvent(new DaywalkerAlertCreatedEvent(symbol, triggerType, severity, thesis));
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
