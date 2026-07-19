package de.visterion.dracul.daywalker;

import de.visterion.dracul.daywalker.detect.TriggerEvent;
import de.visterion.dracul.daywalker.detect.TriggerType;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.notify.TelegramNotifier;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.vistierie.VistierieClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final boolean daywalkerDeepEnabled;
    private final BigDecimal escalationThreshold;
    private final String depotOwner;
    private final String publicUrl;
    private final String deepWebhookToken;
    private final HeldPositionService heldPositions;
    private final String connection;
    private final HiveMemResearchService memory;

    public DaywalkerCompletionService(
            DaywalkerAlertRepository alerts,
            TelegramNotifier notifier,
            ApplicationEventPublisher events,
            @Value("${dracul.daywalker.notify-level:CRITICAL}") String notifyLevel,
            @Value("${dracul.daywalker.cooldown:3600}") long cooldownSeconds,
            ObjectProvider<VistierieClient> vistierie,
            @Value("${dracul.daywalker.escalation-enabled:true}") boolean escalationEnabled,
            @Value("${dracul.daywalker-deep.enabled:false}") boolean daywalkerDeepEnabled,
            @Value("${dracul.daywalker.escalation-confidence:0.6}") BigDecimal escalationThreshold,
            @Value("${dracul.primary-user-email:}") String primaryUser,
            @Value("${dracul.public-url}") String publicUrl,
            @Value("${dracul.daywalker-deep.webhook-token:dev-token-change-me}") String deepWebhookToken,
            HeldPositionService heldPositions,
            @Value("${dracul.position.connection:depot-1}") String connection,
            HiveMemResearchService memory) {
        this.alerts = alerts;
        this.notifier = notifier;
        this.events = events;
        this.notifyRank = rank(notifyLevel);
        this.cooldownSeconds = cooldownSeconds;
        this.vistierie = vistierie;
        this.escalationEnabled = escalationEnabled;
        this.daywalkerDeepEnabled = daywalkerDeepEnabled;
        this.escalationThreshold = escalationThreshold;
        this.depotOwner = primaryUser == null || primaryUser.isBlank() ? "default" : primaryUser;
        this.publicUrl = publicUrl;
        this.deepWebhookToken = deepWebhookToken;
        this.heldPositions = heldPositions;
        this.connection = connection;
        this.memory = memory;
    }

    public void persistAssessment(String symbol, String triggerType, String severity,
                                  String thesis, BigDecimal confidence, String runId) {
        persistAssessment(symbol, triggerType, severity, thesis, confidence, runId, null, null, false);
    }

    public void persistAssessment(String symbol, String triggerType, String severity,
                                  String thesis, BigDecimal confidence, String runId,
                                  String positionId) {
        persistAssessment(symbol, triggerType, severity, thesis, confidence, runId, positionId, null, false);
    }

    /**
     * @param fromEscalation true when this assessment is itself the completion of a
     *                       {@code daywalker-deep} escalation run — guards against a
     *                       re-triggered escalation looping forever. Deep runs carry no
     *                       {@code event_type} (their schema is not extended); the repository
     *                       COALESCE keeps any previously persisted value.
     */
    public void persistAssessment(String symbol, String triggerType, String severity,
                                  String thesis, BigDecimal confidence, String runId,
                                  String positionId, boolean fromEscalation) {
        persistAssessment(symbol, triggerType, severity, thesis, confidence, runId, positionId,
                null, fromEscalation);
    }

    /** T1.3: {@code eventType} is the controller-mapped LLM event category (nullable). */
    public void persistAssessment(String symbol, String triggerType, String severity,
                                  String thesis, BigDecimal confidence, String runId,
                                  String positionId, String eventType) {
        persistAssessment(symbol, triggerType, severity, thesis, confidence, runId, positionId,
                eventType, false);
    }

    public void persistAssessment(String symbol, String triggerType, String severity,
                                  String thesis, BigDecimal confidence, String runId,
                                  String positionId, String eventType, boolean fromEscalation) {
        List<DaywalkerAlertRepository.OwnerItem> owners;
        if (TriggerEvent.PORTFOLIO_SYMBOL.equals(symbol)) {
            // T2.2 C2: PORTFOLIO is a pseudo-symbol — findOwnersBySymbol would be empty and the R1
            // depot check would WARN-skip it. Route straight to the primary owner BEFORE the R1
            // chain; an (illegal) echoed position_id changes nothing and triggers no depot lookup.
            owners = List.of(new DaywalkerAlertRepository.OwnerItem(depotOwner, null, true));
        } else if (positionId != null) {
            // Depot-sourced assessment (A6): DaywalkerEventEngine now only fans triggers over
            // depot positions, and positionId round-trips as the depot SYMBOL — never a
            // watchlist_items UUID (that table may not even contain this ticker). Route
            // straight to the single configured primary-user owner, established convention
            // from A5/gropar for depot's single-account model; no watchlist lookup at all.
            owners = List.of(new DaywalkerAlertRepository.OwnerItem(depotOwner, null, true));
        } else {
            var all = alerts.findOwnersBySymbol(symbol);
            if (all.isEmpty()) {
                // R1 case 2: position_id is legal to omit. Before skipping, check the open
                // depot. openPositions() degrades to EMPTY on depot outage — an empty depot
                // must NOT be read as "not held", so only a REACHABLE depot that does not
                // hold the symbol keeps the WARN-and-skip (hallucinated-symbol guard).
                var open = heldPositions.openPositions(connection);
                boolean heldInDepot = open.stream().anyMatch(p -> symbol.equals(p.symbol()));
                if (!open.isEmpty() && !heldInDepot) {
                    log.warn("daywalker run {} unknown symbol {} — skipping", runId, symbol);
                    return;
                }
                owners = List.of(new DaywalkerAlertRepository.OwnerItem(depotOwner, null, true));
            } else {
                owners = all.stream().filter(o -> !o.held()).toList();
                if (owners.isEmpty()) {
                    // R1 case 1: every watchlist row is tagged HELD (stale tag / external
                    // holding / depot degrade) — fall back to the primary owner so a row IS
                    // written and the engine cooldown engages.
                    owners = List.of(new DaywalkerAlertRepository.OwnerItem(depotOwner, null, true));
                }
            }
        }
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

        Map<String, String> effectiveByOwner = new HashMap<>();
        for (var o : eligible) {
            var existing = alerts.findSameUtcDay(o.userId(), symbol, triggerType, now);
            String effective;
            if (existing.isPresent()) {
                // Same-day duplicate: refresh text/ts, escalate severity, never lower it.
                effective = rank(severity) >= rank(existing.get().severity())
                        ? severity
                        : existing.get().severity();
                alerts.updateSameDayAlert(existing.get().id(), triggerType, effective,
                        thesis, confidence, runId, sent, eventType);
            } else {
                effective = severity;
                alerts.insert(o.userId(), o.watchlistItemId(), symbol, triggerType,
                        severity, thesis, confidence, runId, sent, eventType);
                // Cell-only write-back (T1.6 Task 9): one thesis cell per newly-inserted owner
                // row -- append-only per spec §6, no research_memory_link row (daywalker alerts
                // never resolve to a Task-10 outcome the way prey do). A same-day update
                // (existing.isPresent() branch above) is a mere severity/text refresh, not a new
                // row, so it does NOT write a cell. Best-effort: writeThesisMemory is itself
                // guarded/never-throwing; the outer try/catch is defense-in-depth so a bug here
                // can't 500 the completion.
                try {
                    memory.writeThesisMemory("daywalker_alert", symbol, triggerType, thesis,
                            List.of(), List.of(), List.of(), null, "daywalker",
                            confidence == null ? 0.0 : confidence.doubleValue(), runId);
                } catch (RuntimeException e) {
                    log.warn("daywalker run {} — memory write for {} failed unexpectedly: {}",
                            runId, symbol, e.getMessage());
                }
            }
            effectiveByOwner.put(o.userId(), effective);
        }
        // Publish only after all rows are persisted (invariant from 7ee36ef), one event per owner,
        // carrying the EFFECTIVE severity so the SSE toast matches the persisted row.
        for (var o : eligible) {
            events.publishEvent(new DaywalkerAlertCreatedEvent(
                    o.userId(), symbol, triggerType, effectiveByOwner.get(o.userId()), thesis));
        }
        log.info("daywalker run {} persisted {} alert(s) for {} ({}), notified={}",
                runId, eligible.size(), symbol, triggerType, sent);

        maybeEscalate(symbol, triggerType, severity, thesis, confidence, positionId, fromEscalation);
    }

    /**
     * Low-confidence CRITICAL assessments get a second opinion from a reasoning-tier
     * one-shot run ({@code daywalker-deep}) — asynchronous, never delays or suppresses
     * the alert above (which has already been persisted + notified by this point). Its
     * completion posts a follow-up assessment through {@link #persistAssessment} with
     * {@code fromEscalation=true}; the existing same-day dedupe/escalation-severity merge
     * (never lowered) reconciles it into the alert row already written above.
     *
     * <p>{@code positionId} (nullable) rides along in the trigger input as {@code position_id}
     * and is echoed back verbatim by the deep agent, so the follow-up assessment resolves
     * against the SAME owner set as the original (exact holder for position-scoped alerts,
     * non-held watchers otherwise).
     *
     * <p>Gated on BOTH {@code dracul.daywalker.escalation-enabled} and
     * {@code dracul.daywalker-deep.enabled} — the latter defaults to {@code false}, so without
     * this second check every low-confidence CRITICAL assessment would trigger a run for a
     * possibly-unregistered {@code daywalker-deep} agent (silently WARN-swallowed downstream).
     */
    private void maybeEscalate(String symbol, String triggerType, String severity,
                               String thesis, BigDecimal confidence, String positionId,
                               boolean fromEscalation) {
        // T2.2 (round 1, M3): the deep agent's input carries only symbol/trigger_type/thesis —
        // never the portfolio snapshot — a second opinion on portfolio exposure is content-free.
        if (TriggerType.MACRO_PORTFOLIO.name().equals(triggerType)) return;
        if (!escalationEnabled || !daywalkerDeepEnabled || fromEscalation) return;
        if (!"CRITICAL".equalsIgnoreCase(severity)) return;
        if (confidence == null || confidence.compareTo(escalationThreshold) >= 0) return;

        vistierie.ifAvailable(v -> {
            try {
                var input = new java.util.HashMap<String, Object>();
                input.put("symbol", symbol);
                input.put("trigger_type", triggerType);
                input.put("thesis", thesis);
                if (positionId != null) input.put("position_id", positionId);
                v.triggerRun("daywalker-deep", input,
                        publicUrl + "/api/daywalker-deep/complete", deepWebhookToken);
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
