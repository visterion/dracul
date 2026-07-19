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
import java.time.LocalDate;
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
    private final HiveMemResearchService memory;
    private final int maxAgeDays;

    public OutcomeMemoryScanJob(ResearchMemoryLinkRepository links,
            ExecutorPositionRepository positions, ExecutorSignalRepository signals,
            HiveMemResearchService memory,
            @Value("${dracul.outcome.memory-scan-max-age-days:120}") int maxAgeDays) {
        this.links = links;
        this.positions = positions;
        this.signals = signals;
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

        if (p.realizedR() == null && p.exitPrice() == null) {
            return; // not enough data yet — a later scan will retry
        }

        // MAE-in-R is already computed for the TRADE row elsewhere (OutcomeBatchJob.maeR); this
        // job only has the position's own stored fields, which don't carry MAE-in-R directly,
        // and recomputing OutcomeBatchJob's rPerShare math a second time here isn't worth the
        // duplication for a nullable, best-effort field (spec §8) — pass null.
        boolean written = memory.writeOutcomeCell(link.cellId(), p.symbol(), signal.mechanism(),
                p.realizedR(), null, p.mfeR(), holdingDays(p));
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

    private static Integer holdingDays(ExecutorPosition p) {
        try {
            if (p.entryDate() == null || p.closedAt() == null) {
                return null;
            }
            return (int) ChronoUnit.DAYS.between(
                    LocalDate.parse(p.entryDate().substring(0, 10)),
                    LocalDate.parse(p.closedAt().substring(0, 10)));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
