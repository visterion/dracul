package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.marketdata.MarketDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ENRICH + RESPOND phases of the index-reconstitution hunt (see {@link StrigoiIndexWebhookController}),
 * mirroring {@link de.visterion.dracul.strigoi.spin.SpinCandidateEnricher}. This replaces the G4
 * work-queue placeholder with real per-stage snapshotting.
 *
 * <p><b>ENRICH</b> ({@link #enrich}) selects a bounded set of rows — those that reached a new stage
 * this run first, then non-terminal rows oldest-checked, capped at {@link #MAX} — and dispatches each
 * by its current status:
 * <ul>
 *   <li><b>ANNOUNCED</b> &rarr; {@link IndexDemandSnapshotter} &rarr; {@code announced_snapshot}.</li>
 *   <li><b>EFFECTIVE / POST</b> &rarr; {@link IndexDriftSnapshotter} &rarr; {@code post_snapshot}
 *       (EFFECTIVE is a transient tick with no column of its own; its drift read is stored under the
 *       POST column, the whitelisted snapshot target for the drift stage).</li>
 *   <li><b>CLOSED / ABANDONED</b> — terminal; only {@code touchLastChecked} (these never reach the
 *       non-terminal work queue anyway, so this is belt-and-braces).</li>
 * </ul>
 *
 * <p><b>Source-down guard.</b> The snapshotters share ONE strict source — the Agora price feed,
 * which raises {@link MarketDataException}. The first availability outage
 * ({@link MarketDataException.Kind#UNAVAILABLE}) marks the price source down for the rest of the
 * batch and stops enrichment early (every remaining queued row is ANNOUNCED/EFFECTIVE/POST and needs
 * a price fetch, so there is nothing left to do this run — the spin enricher's {@code skipAll}
 * discipline). A symbol-specific miss never trips the guard: the snapshotter degrades those fields to
 * null and the (partial) snapshot is still stored. Any other per-row failure degrades that one row.
 *
 * <p><b>RESPOND</b> ({@link #payload}) rebuilds {@link EnrichedIndexEvent} rows from the persisted
 * columns + snapshots for the active, unpromoted window {ANNOUNCED, EFFECTIVE, POST}.
 */
@Component
public class IndexEventEnricher {

    private static final Logger log = LoggerFactory.getLogger(IndexEventEnricher.class);

    /** Enrichment cap per run — parity with the other hunters; each row costs a few Agora calls. */
    static final int MAX = 25;
    /** Non-terminal scan bound for the work queue (reconstitutions are rare). */
    private static final int SCAN_LIMIT = 1000;
    /** Rows returned to the LLM per fetch. */
    private static final int RESPONSE_LIMIT = 50;

    private final IndexEventRepository repo;
    private final IndexDemandSnapshotter demand;
    private final IndexDriftSnapshotter drift;
    private final ObjectMapper mapper;

    public IndexEventEnricher(IndexEventRepository repo,
                              IndexDemandSnapshotter demand,
                              IndexDriftSnapshotter drift,
                              ObjectMapper mapper) {
        this.repo = repo;
        this.demand = demand;
        this.drift = drift;
        this.mapper = mapper;
    }

    /** Per-batch strict-source health; the price source, once down, is not queried again this batch. */
    private static final class SourceHealth {
        boolean priceDown;
    }

    public void enrich(IndexLifecycleReconciler.ReconcileResult reconcile) {
        enrich(reconcile, LocalDate.now());
    }

    /** Package-private date seam for deterministic tests. */
    void enrich(IndexLifecycleReconciler.ReconcileResult reconcile, LocalDate today) {
        List<IndexEventRow> queue = selectQueue(reconcile);
        SourceHealth health = new SourceHealth();
        for (IndexEventRow row : queue) {
            if (health.priceDown) {
                // The sole strict source is down; every remaining queued row needs it.
                log.info("index enrichment: price source down, skipping remaining rows");
                break;
            }
            try {
                switch (row.status()) {
                    case ANNOUNCED -> enrichAnnounced(row);
                    case EFFECTIVE, POST -> enrichDrift(row, today);
                    default -> repo.touchLastChecked(row.id());
                }
            } catch (MarketDataException e) {
                if (e.kind() == MarketDataException.Kind.UNAVAILABLE) {
                    health.priceDown = true;
                    log.warn("index enrichment: price source down ({}), skipping it for remaining rows",
                            e.getMessage());
                } else {
                    log.debug("index enrichment: price lookup failed for row {}: {}", row.id(), e.getMessage());
                }
                repo.touchLastChecked(row.id());
            } catch (RuntimeException e) {
                // Belt-and-braces: an unforeseen failure degrades one row, never the run.
                log.debug("index enrichment: row {} failed: {}", row.id(), e.getMessage());
                repo.touchLastChecked(row.id());
            }
        }
    }

    /** (transitioned this run) first, then non-terminal rows oldest-checked, capped. */
    private List<IndexEventRow> selectQueue(IndexLifecycleReconciler.ReconcileResult reconcile) {
        List<IndexEventRow> nonTerminal = repo.findNonTerminalOldestCheckedFirst(SCAN_LIMIT);
        // findNonTerminalOldestCheckedFirst already orders by last_checked_at ASC; a stable sort that
        // only lifts freshly-transitioned rows to the front preserves that secondary order.
        List<IndexEventRow> ordered = new ArrayList<>(nonTerminal);
        ordered.sort(Comparator.comparingInt(r -> reconcile.transitionedIds().contains(r.id()) ? 0 : 1));
        return ordered.size() > MAX ? ordered.subList(0, MAX) : ordered;
    }

    private void enrichAnnounced(IndexEventRow row) {
        var snap = demand.snapshot(row.symbol(), row.indexName(), row.announcementDate());
        repo.storeSnapshot(row.id(), IndexEventStatus.ANNOUNCED, mapper.valueToTree(snap));
    }

    private void enrichDrift(IndexEventRow row, LocalDate today) {
        var snap = drift.snapshot(row.symbol(), row.announcementDate(), row.effectiveDate(), today);
        // EFFECTIVE is transient; its drift read is stored under the POST column (the whitelisted
        // drift-stage snapshot target).
        repo.storeSnapshot(row.id(), IndexEventStatus.POST, mapper.valueToTree(snap));
    }

    /** Builds the LLM payload from the active, unpromoted rows + their persisted snapshots. */
    public List<EnrichedIndexEvent> payload() {
        return repo.findActiveUnpromoted(RESPONSE_LIMIT).stream().map(this::toWire).toList();
    }

    private EnrichedIndexEvent toWire(IndexEventRow row) {
        JsonNode ann = row.announcedSnapshot();
        JsonNode post = row.postSnapshot();
        return new EnrichedIndexEvent(
                row.symbol(), row.companyName(), row.indexName(), row.action(), row.source(),
                row.announcementDate() == null ? null : row.announcementDate().toString(),
                row.effectiveDate() == null ? null : row.effectiveDate().toString(),
                row.status().name(),
                // ANNOUNCED (demand) stage
                bd(ann, "adv"), dbl(ann, "marketCap"), lng(ann, "avgVolume20d"),
                dbl(ann, "idiosyncraticVol"), dbl(ann, "freeFloatProxyMillions"),
                dbl(ann, "demandToAdvRatioEstimate"), stringList(ann, "confounders"),
                // EFFECTIVE / POST (drift) stage
                dbl(post, "runUpPct"), dbl(post, "postEffectivePct"),
                bool(post, "reversalObserved"), integer(post, "daysSinceEffective"));
    }

    // --- snapshot JSON readers (nullable, defensive; mirror SpinCandidateEnricher) ---

    private static BigDecimal bd(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode n = node.path(field);
        if (!n.isNumber()) return null;
        try { return new BigDecimal(n.asString()); } catch (RuntimeException e) { return null; }
    }

    private static Double dbl(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode n = node.path(field);
        return n.isNumber() ? n.asDouble() : null;
    }

    private static Long lng(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode n = node.path(field);
        return n.isNumber() ? n.asLong() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode n = node.path(field);
        return n.isNumber() ? n.asInt() : null;
    }

    private static Boolean bool(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode n = node.path(field);
        return n.isBoolean() ? n.asBoolean() : null;
    }

    private static List<String> stringList(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode n = node.path(field);
        if (!n.isArray()) return null;
        List<String> out = new ArrayList<>();
        for (JsonNode e : n) if (e.isTextual()) out.add(e.asString());
        return out;
    }
}
