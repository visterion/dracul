package de.visterion.dracul.daywalker;

import de.visterion.dracul.notify.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Resolves every owner of a completed Daywalker assessment's symbol, fires one Telegram push
 * + one live event per symbol assessment (if severe enough), and persists one alert row per
 * owner that is outside its (owner, symbol, trigger-type) cooldown.
 */
@Component
public class DaywalkerCompletionService {

    private static final Logger log = LoggerFactory.getLogger(DaywalkerCompletionService.class);

    private final DaywalkerAlertRepository alerts;
    private final TelegramNotifier notifier;
    private final ApplicationEventPublisher events;
    private final int notifyRank;
    private final long cooldownSeconds;

    public DaywalkerCompletionService(
            DaywalkerAlertRepository alerts,
            TelegramNotifier notifier,
            ApplicationEventPublisher events,
            @Value("${dracul.daywalker.notify-level:CRITICAL}") String notifyLevel,
            @Value("${dracul.daywalker.cooldown:3600}") long cooldownSeconds) {
        this.alerts = alerts;
        this.notifier = notifier;
        this.events = events;
        this.notifyRank = rank(notifyLevel);
        this.cooldownSeconds = cooldownSeconds;
    }

    public void persistAssessment(String symbol, String triggerType, String severity,
                                  String thesis, BigDecimal confidence, String runId) {
        persistAssessment(symbol, triggerType, severity, thesis, confidence, runId, null);
    }

    public void persistAssessment(String symbol, String triggerType, String severity,
                                  String thesis, BigDecimal confidence, String runId,
                                  String positionId) {
        var all = alerts.findOwnersBySymbol(symbol);
        if (all.isEmpty()) {
            log.warn("daywalker run {} unknown symbol {} — skipping", runId, symbol);
            return;
        }
        var owners = positionId != null
                ? all.stream().filter(o -> positionId.equals(o.watchlistItemId())).toList()
                : all.stream().filter(o -> !o.held()).toList();
        Instant now = Instant.now();
        var eligible = owners.stream()
                .filter(o -> !inCooldown(o.userId(), symbol, triggerType, now))
                .toList();
        if (eligible.isEmpty()) {
            log.info("daywalker run {} no eligible owner for {} ({}) [positionId={}] — skipping",
                    runId, symbol, triggerType, positionId);
            return;
        }

        boolean sent = rank(severity) >= notifyRank
                && notifier.notifyAlert(symbol, triggerType, severity, thesis);

        for (var o : eligible) {
            var existing = alerts.findSameUtcDay(o.userId(), symbol, triggerType, now);
            if (existing.isPresent()) {
                // Same-day duplicate: refresh text/ts, escalate severity, never lower it.
                String effective = rank(severity) >= rank(existing.get().severity())
                        ? severity
                        : existing.get().severity();
                alerts.updateSameDayAlert(existing.get().id(), triggerType, effective,
                        thesis, confidence, runId, sent);
            } else {
                alerts.insert(o.userId(), o.watchlistItemId(), symbol, triggerType,
                        severity, thesis, confidence, runId, sent);
            }
        }
        // Publish only after all rows are persisted (invariant from 7ee36ef), one event per owner
        // so the SSE bridge can deliver the live toast to exactly that owner.
        for (var o : eligible) {
            events.publishEvent(new DaywalkerAlertCreatedEvent(
                    o.userId(), symbol, triggerType, severity, thesis));
        }
        log.info("daywalker run {} persisted {} alert(s) for {} ({}), notified={}",
                runId, eligible.size(), symbol, triggerType, sent);
    }

    private boolean inCooldown(String userId, String symbol, String triggerType, Instant now) {
        return alerts.lastAlertAt(userId, symbol, triggerType)
                .map(last -> last.isAfter(now.minusSeconds(cooldownSeconds)))
                .orElse(false);
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
