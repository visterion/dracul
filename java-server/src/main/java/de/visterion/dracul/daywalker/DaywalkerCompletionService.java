package de.visterion.dracul.daywalker;

import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.vistierie.VistierieClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Resolves every owner of a completed Daywalker assessment's symbol, fires one Telegram push
 * + one live event per symbol assessment (if severe enough), and persists one alert row per
 * owner that is outside its (owner, symbol, trigger-type) cooldown.
 *
 * <p>Low-confidence CRITICAL assessments additionally trigger an asynchronous
 * reasoning-tier second opinion ({@code daywalker-deep}) — see {@link #maybeEscalate}.
 */
@Component
public class DaywalkerCompletionService {

    private static final Logger log = LoggerFactory.getLogger(DaywalkerCompletionService.class);

    private final DaywalkerAlertRepository alerts;
    private final TelegramNotifier notifier;
    private final ApplicationEventPublisher events;
    private final int notifyRank;
    private final long cooldownSeconds;
    private final ObjectProvider<VistierieClient> vistierie;
    private final boolean escalationEnabled;
    private final BigDecimal escalationThreshold;

    public DaywalkerCompletionService(
            DaywalkerAlertRepository alerts,
            TelegramNotifier notifier,
            ApplicationEventPublisher events,
            @Value("${dracul.daywalker.notify-level:CRITICAL}") String notifyLevel,
            @Value("${dracul.daywalker.cooldown:3600}") long cooldownSeconds,
            ObjectProvider<VistierieClient> vistierie,
            @Value("${dracul.daywalker.escalation-enabled:true}") boolean escalationEnabled,
            @Value("${dracul.daywalker.escalation-confidence:0.6}") BigDecimal escalationThreshold) {
        this.alerts = alerts;
        this.notifier = notifier;
        this.events = events;
        this.notifyRank = rank(notifyLevel);
        this.cooldownSeconds = cooldownSeconds;
        this.vistierie = vistierie;
        this.escalationEnabled = escalationEnabled;
        this.escalationThreshold = escalationThreshold;
    }

    public void persistAssessment(String symbol, String triggerType, String severity,
                                  String thesis, BigDecimal confidence, String runId) {
        persistAssessment(symbol, triggerType, severity, thesis, confidence, runId, null, false);
    }

    public void persistAssessment(String symbol, String triggerType, String severity,
                                  String thesis, BigDecimal confidence, String runId,
                                  String positionId) {
        persistAssessment(symbol, triggerType, severity, thesis, confidence, runId, positionId, false);
    }

    /**
     * @param fromEscalation true when this assessment is itself the completion of a
     *                       {@code daywalker-deep} escalation run — guards against a
     *                       re-triggered escalation looping forever.
     */
    public void persistAssessment(String symbol, String triggerType, String severity,
                                  String thesis, BigDecimal confidence, String runId,
                                  String positionId, boolean fromEscalation) {
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

        maybeEscalate(symbol, triggerType, severity, thesis, confidence, fromEscalation);
    }

    /**
     * Low-confidence CRITICAL assessments get a second opinion from a reasoning-tier
     * one-shot run ({@code daywalker-deep}) — asynchronous, never delays or suppresses
     * the alert above (which has already been persisted + notified by this point). Its
     * completion posts a follow-up assessment through {@link #persistAssessment} with
     * {@code fromEscalation=true}; the existing same-day dedupe/escalation-severity merge
     * (never lowered) reconciles it into the alert row already written above.
     */
    private void maybeEscalate(String symbol, String triggerType, String severity,
                               String thesis, BigDecimal confidence, boolean fromEscalation) {
        if (!escalationEnabled || fromEscalation) return;
        if (!"CRITICAL".equalsIgnoreCase(severity)) return;
        if (confidence == null || confidence.compareTo(escalationThreshold) >= 0) return;

        vistierie.ifAvailable(v -> {
            try {
                var input = java.util.Map.<String, Object>of(
                        "symbol", symbol, "trigger_type", triggerType, "thesis", thesis);
                v.triggerRun("daywalker-deep", input);
                log.info("daywalker escalation triggered for {} ({})", symbol, triggerType);
            } catch (Exception e) {
                log.warn("daywalker escalation failed for {}: {}", symbol, e.getMessage());
            }
        });
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
