package de.visterion.dracul.stopguard;

import de.visterion.dracul.daywalker.DaywalkerAlertCreatedEvent;
import de.visterion.dracul.daywalker.DaywalkerAlertRepository;
import de.visterion.dracul.daywalker.detect.TriggerType;
import de.visterion.dracul.notify.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/** Deterministic (no-LLM) alert emit for the stop-proximity watcher. Mirrors the
 *  tail of DaywalkerCompletionService — cooldown, persist, Telegram, SSE — but
 *  per (owner, position) and with a Dracul-composed German thesis. */
@Component
public class StopAlertEmitter {

    private static final Logger log = LoggerFactory.getLogger(StopAlertEmitter.class);

    private final DaywalkerAlertRepository alerts;
    private final TelegramNotifier notifier;
    private final ApplicationEventPublisher events;
    private final int notifyRank;
    private final long cooldownSeconds;

    public StopAlertEmitter(DaywalkerAlertRepository alerts, TelegramNotifier notifier,
            ApplicationEventPublisher events,
            @Value("${dracul.stopguard.notify-level:WARNING}") String notifyLevel,
            @Value("${dracul.stopguard.cooldown:82800}") long cooldownSeconds) {
        this.alerts = alerts;
        this.notifier = notifier;
        this.events = events;
        this.notifyRank = rank(notifyLevel);
        this.cooldownSeconds = cooldownSeconds;
    }

    /** Emit one stop alert if the owner is outside cooldown for this (symbol, zone).
     *  Returns true if an alert row was written. NONE is a no-op.
     *
     * <p>Depot-sourced alerts carry no {@code watchlist_items} row -- dedup and identity
     * are keyed by {@code (owner, symbol, trigger_type)}, so no item id is passed through
     * to the repository; the insert always writes a null {@code watchlist_item_id}. */
    public boolean emit(String owner, String symbol, StopZone zone,
                        BigDecimal price, BigDecimal activeStop, Instant now) {
        if (zone == StopZone.NONE) return false;
        TriggerType type = zone == StopZone.BREACHED
                ? TriggerType.STOP_BREACHED : TriggerType.STOP_PROXIMITY;
        boolean inCooldown = alerts.lastAlertAt(owner, symbol, type.name())
                .map(last -> last.isAfter(now.minusSeconds(cooldownSeconds)))
                .orElse(false);
        if (inCooldown) return false;

        String severity = zone == StopZone.BREACHED ? "CRITICAL" : "WARNING";
        String thesis = thesis(zone, symbol, price, activeStop);
        boolean sent = rank(severity) >= notifyRank
                && notifier.notifyAlert(symbol, type.name(), severity, thesis);
        String runId = "stopguard-" + now.toEpochMilli();
        alerts.insert(owner, null, symbol, type.name(), severity, thesis, null, runId, sent);
        events.publishEvent(new DaywalkerAlertCreatedEvent(owner, symbol, type.name(), severity, thesis));
        log.info("stopguard {} alert for {} (owner={}), notified={}", zone, symbol, owner, sent);
        return true;
    }

    private static String thesis(StopZone zone, String symbol, BigDecimal price, BigDecimal stop) {
        if (zone == StopZone.BREACHED) {
            return String.format("Stop gerissen: %s Kurs %s ≤ Stop %s — handeln",
                    symbol, plain(price), plain(stop));
        }
        return String.format("%s nähert sich dem Stop: Kurs %s, Stop %s",
                symbol, plain(price), plain(stop));
    }

    private static String plain(BigDecimal v) { return v == null ? "—" : v.toPlainString(); }

    static int rank(String severity) {
        return switch (severity == null ? "" : severity.toUpperCase()) {
            case "CRITICAL" -> 3; case "WARNING" -> 2; default -> 1;
        };
    }
}
