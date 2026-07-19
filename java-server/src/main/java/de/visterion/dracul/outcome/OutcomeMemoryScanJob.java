package de.visterion.dracul.outcome;

import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignal;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.research.ResearchMemoryLink;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Own-scan (T1.6 D9/§5.2): does NOT piggyback {@link OutcomeBatchJob}'s one-shot finalization
 * pass (which returns early once a row {@code isComplete} and never fires again for that row).
 * Instead scans {@code research_memory_link(kind='prey', outcome_written=false)} directly,
 * resolves each link to its closed position via {@code prey_id} (NEVER the symbol/date fallback
 * {@code OutcomeBatchJob.resolveEnterDecision} uses — that cross-wires two same-symbol hunts,
 * exactly the calibration case this feature exists for), and writes a realized-outcome HiveMem
 * cell. A HiveMem outage on the completion night is retried by the NEXT run of this job,
 * independent of {@code OutcomeBatchJob}'s own completeness bookkeeping.
 *
 * <p>The realized R is NOT final at close: {@link OutcomeBatchJob} recomputes a quantity-weighted
 * {@code realized_r} across trims/re-entries during its 14-calendar-day re-entry window
 * ({@code REENTRY_WINDOW_CALENDAR_DAYS}), and only the {@code outcome_log} TRADE row carries that
 * final weighted value — the position's own {@code realized_r}/{@code mfe_r} are un-weighted and
 * can be stale. So this scan gates the cell write on {@link OutcomeLogRepository#
 * findCompleteTradeByPositionId} (mirrors {@code OutcomeBatchJob.isComplete}'s
 * {@code reentryWindowElapsed} flag) and reads {@code realizedR}/{@code maeR}/{@code mfeR}/
 * {@code holdingDays} from that row, never from the position.
 */
@Component
@ConditionalOnProperty(value = "dracul.outcome.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class OutcomeMemoryScanJob {

    private static final Logger log = LoggerFactory.getLogger(OutcomeMemoryScanJob.class);
    private static final int SCAN_LIMIT = 500;

    private final ResearchMemoryLinkRepository links;
    private final ExecutorPositionRepository positions;
    private final ExecutorSignalRepository signals;
    private final OutcomeLogRepository outcomeLog;
    private final HiveMemResearchService memory;
    private final int maxAgeDays;

    public OutcomeMemoryScanJob(ResearchMemoryLinkRepository links,
            ExecutorPositionRepository positions, ExecutorSignalRepository signals,
            OutcomeLogRepository outcomeLog, HiveMemResearchService memory,
            @Value("${dracul.outcome.memory-scan-max-age-days:120}") int maxAgeDays) {
        this.links = links;
        this.positions = positions;
        this.signals = signals;
        this.outcomeLog = outcomeLog;
        this.memory = memory;
        this.maxAgeDays = maxAgeDays;
    }

    @Scheduled(cron = "${dracul.outcome.hivemem-scan-cron:0 45 22 * * 2-6}")
    public void run() {
        try {
            scanOnce();
        } catch (Exception e) {
            log.error("outcome memory scan failed", e);
        }
    }

    /** Package-private for testing. */
    void scanOnce() {
        List<ResearchMemoryLink> unwritten = links.findUnwrittenPreyLinks(SCAN_LIMIT);
        if (unwritten.isEmpty()) {
            return;
        }

        for (ExecutorPosition p : positions.findClosed()) {
            try {
                processClosedPosition(p, unwritten);
            } catch (Exception e) {
                log.warn("outcome memory scan: failed for closed position {} ({}): {}",
                        p.id(), p.symbol(), e.getMessage(), e);
            }
        }

        excludeStaleLinks();
    }

    private void processClosedPosition(ExecutorPosition p, List<ResearchMemoryLink> unwritten) {
        if (p.sourceSignalId() == null) {
            return; // no-op: injected/manual position, no signal chain to resolve a prey from
        }
        ExecutorSignal signal = signals.findById(p.sourceSignalId());
        if (signal == null || signal.preyId() == null) {
            return; // no-op: unresolvable signal/prey — no WARN-spam, this is an expected state
        }

        ResearchMemoryLink link = unwritten.stream()
                .filter(l -> "prey".equals(l.kind()) && l.refId().equals(signal.preyId()))
                .findFirst().orElse(null);
        if (link == null) {
            return; // already written, excluded, or no thesis cell was ever recorded for this prey
        }

        OutcomeLogRow row = outcomeLog.findCompleteTradeByPositionId(p.id());
        if (row == null) {
            return; // not complete yet (still within OutcomeBatchJob's re-entry window, or the
            // TRADE row hasn't been written at all yet) — a later scan will retry
        }

        boolean written = memory.writeOutcomeCell(link.cellId(), p.symbol(), signal.mechanism(),
                row.realizedR(), row.maeR(), row.mfeR(), row.holdingDays());
        if (written) {
            links.markOutcomeWritten(link.id());
        }
        // else: leave outcome_written=false — the NEXT scanOnce() run will retry this link.
    }

    /** Terminal scan-bound: a link still unwritten after the position pass above, older than
     *  {@code maxAgeDays} (max(horizon)+reentry window), is presumed a prey that was never
     *  traded to completion — excluded so the unwritten candidate set doesn't grow unboundedly. */
    private void excludeStaleLinks() {
        Instant cutoff = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS);
        for (ResearchMemoryLink link : links.findUnwrittenPreyLinks(SCAN_LIMIT)) {
            if (link.createdAt() != null && link.createdAt().isBefore(cutoff)) {
                links.markExcluded(link.id());
                log.info("outcome memory scan: excluding stale unwritten link {} ({}, prey {}) — "
                        + "no completed trade after {} days", link.id(), link.symbol(),
                        link.refId(), maxAgeDays);
            }
        }
    }
}
