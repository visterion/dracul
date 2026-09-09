package de.visterion.dracul.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounds the lifetime of a PENDING signal nobody looks at again. Before this, a PENDING row had
 * no exit except {@code place_entry} and {@code submit_decision} — and a signal the LLM was never
 * handed, or was handed and never decided, sat there forever, blocking its symbol at the producer
 * ({@code PreySignalEmitter} suppresses PENDING symbols).
 *
 * <p><b>Trigger.</b> Not a cron job: this runs once per maintenance pass
 * ({@code MaintenancePipeline.run}), whose only caller is the agent's {@code fetch_open_positions}
 * tool. On a night the agent never calls it, nothing is swept — and a run that skips the whole
 * exit review is a bigger problem than an unswept row. Because the prompt puts the exit review
 * after {@code submit_decision}, the sweep never races a signal the LLM is still deciding.
 *
 * <p><b>Status is REJECTED, not EXPIRED.</b> {@code EXPIRED} already means "entry bracket placed
 * and never filled" ({@code EntryExpiryService}). A signal nobody evaluated is a reject for the
 * same reason {@code place_entry}'s {@code SIGNAL_EXPIRED} is one, and it gets the same
 * {@code reject_reason}, so every existing query over {@code reject_reason='SIGNAL_EXPIRED'} sees
 * both paths. The discriminator is structural: {@code executor_decision.action = 'SWEEP'}.
 * {@code place_entry}'s expiry row has {@code action = null}, the full catalog trace and a
 * {@code decision_log} REJECT partner. The rationale prefix is for humans and Chronicle, not for
 * queries.
 *
 * <p><b>No {@code decision_log} row is written.</b> Those REJECT rows carry an
 * {@code inputs_snapshot} with {@code order_price}/{@code atr} from a live {@code EntryContext};
 * the sweeper has no context and must not write a row {@code OutcomeBatchJob.processReject} would
 * turn into a permanent {@code skipped} counterfactual. The counterfactual for a swept signal
 * comes from the signal-anchored walk instead ({@code SIGNAL_EXPIRED_UNEVALUATED}).
 *
 * <p><b>Order of side effects per signal: decision row first, then status.</b> If the status
 * update fails afterwards, the row is still PENDING, {@code findPending} returns it again, and
 * the next pass retries — a second decision row for the same signal is harmless and visible.
 * Nothing here is transactional; each signal is independent and an exception on one is caught,
 * logged and stepped over.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class PendingSignalSweeper {

    private static final Logger log = LoggerFactory.getLogger(PendingSignalSweeper.class);

    /** The {@code executor_decision.action} value that marks a sweeper row. The counterfactual
     *  finder's SQL binds to this constant, so a drift breaks a test instead of silently
     *  emptying the finder. */
    public static final String ACTION = "SWEEP";

    /** Human-readable prefix of the sweeper's rationale. For people and Chronicle, never a query
     *  predicate — {@link #ACTION} is the structural discriminator. */
    public static final String RATIONALE_PREFIX = "expired without evaluation: ";

    private final ExecutorSignalRepository signalRepo;
    private final ExecutorDecisionRepository decisionRepo;
    private final SignalAgePolicy policy;
    private final Clock clock;

    public PendingSignalSweeper(ExecutorSignalRepository signalRepo,
            ExecutorDecisionRepository decisionRepo,
            SignalAgePolicy policy,
            @Qualifier("executorClock") Clock clock) {
        this.signalRepo = signalRepo;
        this.decisionRepo = decisionRepo;
        this.policy = policy;
        this.clock = clock;
    }

    /** Retires every PENDING signal older than {@code max-signal-age-days} trading days.
     *  @return the number of signals retired this pass. */
    public int sweep(String runId) {
        // The whole set, not the 50-row window: those callers are the LLM-facing ones.
        List<ExecutorSignal> pending = signalRepo.findPending(Integer.MAX_VALUE);
        int maxAge = policy.maxSignalAgeDays();
        int retired = 0;
        List<String> retiredSymbols = new ArrayList<>();

        for (ExecutorSignal signal : pending) {
            try {
                long age = TradingDays.ageOf(signal.createdAt(), clock);
                if (age < 0) {
                    log.warn("pending sweep: cannot date signal {} ({}) — created_at '{}' is blank "
                                    + "or unparseable; leaving it PENDING rather than retiring it on a guess",
                            signal.signalId(), signal.symbol(), signal.createdAt());
                    continue;
                }
                // Strict '>', exactly like VetoService's veto #3.
                if (age <= maxAge) continue;

                // The 11-component shape submit_decision uses, NOT the legacy 10-arg overload,
                // which drops `action` positionally.
                decisionRepo.insert(new ExecutorDecision(null, signal.signalId(), signal.symbol(),
                        false, RejectReason.SIGNAL_EXPIRED.name(),
                        List.of("SIGNAL_EXPIRED:FAIL (" + age + " > " + maxAge + " days)"),
                        RATIONALE_PREFIX + age + " > " + maxAge + " trading days",
                        null, runId, null, ACTION));
                signalRepo.markStatus(signal.signalId(), "REJECTED");
                retired++;
                retiredSymbols.add(signal.symbol());
            } catch (RuntimeException e) {
                log.warn("pending sweep: failed to retire signal {} ({}): {}",
                        signal.signalId(), signal.symbol(), e.getMessage(), e);
            }
        }

        // ALWAYS logged: a night with no line means the sweep did not run, which is a different
        // and alarmable fact from "nothing was eligible".
        String symbols = retiredSymbols.isEmpty() ? "" : ": " + String.join(", ", retiredSymbols);
        if (runId == null) {
            // WARN and SAYS SO: a header-less maintenance call writes run_id NULL on every
            // retired row, which prod has never seen (411 of 411 decision rows are non-null) —
            // the fact must be in the message itself, not only in this comment, so a log
            // pipeline or grep that drops the level still sees it.
            log.warn("pending sweep: scanned {} PENDING signal(s), retired {} older than {} trading days{}"
                            + " — no run id on this maintenance call, so the decision rows carry run_id NULL",
                    pending.size(), retired, maxAge, symbols);
        } else {
            log.info("pending sweep: scanned {} PENDING signal(s), retired {} older than {} trading days{}",
                    pending.size(), retired, maxAge, symbols);
        }
        return retired;
    }
}
