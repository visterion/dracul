package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraReference;
import de.visterion.dracul.hunting.agora.IndexChangeEvent;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import de.visterion.dracul.webhook.HuntController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(value = "dracul.strigoi.index.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-index")
public class StrigoiIndexWebhookController extends HuntController {

    private static final Logger log = LoggerFactory.getLogger(StrigoiIndexWebhookController.class);

    /** Indices ingested as tracked lifecycle rows. The Agora change tool is single-index, so it is
     *  called once per index. {@code sp500} is the verified-live anchor source whose fetch health
     *  rides the RESPOND envelope (parity with the spin hunt surfacing its single ingest search's
     *  health). */
    private static final List<String> INGEST_INDICES = List.of("sp500", "russell1000", "russell2000");
    private static final String PRIMARY_INDEX = "sp500";

    private final AgoraReference reference;
    private final IndexEventRepository indexEventRepo;
    private final IndexLifecycleReconciler reconciler;
    private final IndexEventEnricher enricher;
    private final int defaultLookback;
    private final int promotionWindowDaysSp;
    private final int promotionWindowDaysRussell;

    /** Russell events carry this source tag; anything else (notably {@code sp_press}) uses the tighter
     *  S&P window. The Russell reconstitution's preliminary&rarr;final cadence is weeks, not days. */
    private static final String RUSSELL_SOURCE = "russell_reconstitution";

    public StrigoiIndexWebhookController(
            @Value("${dracul.strigoi.index.webhook-token}") String token,
            AgoraReference reference,
            IndexEventRepository indexEventRepo,
            IndexLifecycleReconciler reconciler,
            IndexEventEnricher enricher,
            PreyRepository preyRepo,
            ToolFetchCache cache,
            HiveMemResearchService memory,
            ResearchMemoryLinkRepository memoryLinks,
            @Value("${dracul.strigoi.index.lookback-days:30}") int defaultLookback,
            @Value("${dracul.strigoi.index.promotion-window-days-sp:5}") int promotionWindowDaysSp,
            @Value("${dracul.strigoi.index.promotion-window-days-russell:20}") int promotionWindowDaysRussell) {
        super(token, preyRepo, cache, memory, memoryLinks);
        this.reference = reference;
        this.indexEventRepo = indexEventRepo;
        this.reconciler = reconciler;
        this.enricher = enricher;
        this.defaultLookback = defaultLookback;
        this.promotionWindowDaysSp = promotionWindowDaysSp;
        this.promotionWindowDaysRussell = promotionWindowDaysRussell;
    }

    @Override protected String agentName() { return "strigoi-index"; }
    @Override protected String defaultAnomalyType() { return "INDEX_INCLUSION"; }
    @Override protected String defaultHorizon() { return "1m"; }
    @Override protected boolean skipBlankSymbol() { return true; }
    @Override protected String toolName() { return "fetch_index_reconstitution_events"; }

    /**
     * The four-phase lifecycle hunt (blueprint §2.9), on the same webhook cron:
     * <ol>
     *   <li><b>INGEST</b> — {@code indexChanges} per tracked index, upsert each announced change as
     *       an ANNOUNCED row (idempotent on the natural key). Unchanged from G3.</li>
     *   <li><b>RECONCILE</b> — {@link IndexLifecycleReconciler}: pure calendar transitions, ZERO
     *       Agora calls (the effective date is already authoritative; no quote probe needed).</li>
     *   <li><b>ENRICH</b> — {@link IndexEventEnricher}: per-stage snapshotting of a bounded work
     *       queue (freshly-transitioned first, then oldest-checked, capped) into the
     *       {@code announced_snapshot} / {@code post_snapshot} columns, fully fail-soft.</li>
     *   <li><b>RESPOND</b> — {@link IndexEventEnricher#payload()} builds the {@link EnrichedIndexEvent}
     *       payload from the persisted active, unpromoted rows (ANNOUNCED/EFFECTIVE/POST,
     *       {@code promoted_at IS NULL}) + their now-filled snapshots. RESPOND does not read the live
     *       S&amp;P constituents list; the health of the {@code sp500} ingest fetch rides the response.</li>
     * </ol>
     */
    @Override
    protected DataSourceResult<?> hunt(Map<String, Object> body) {
        int lookback = lookbackDays(body, defaultLookback, 1, 90);

        // INGEST
        DataSourceResult<IndexChangeEvent> primary = ingestAnnounced(lookback);

        // RECONCILE + ENRICH
        IndexLifecycleReconciler.ReconcileResult reconcile = reconciler.reconcile();
        enricher.enrich(reconcile);

        // RESPOND
        return new DataSourceResult<>(enricher.payload(), primary.health());
    }

    /**
     * Bare ANNOUNCED-row ingestion: for each tracked index, fetch its announced constituent changes
     * and upsert them idempotently (ON CONFLICT DO NOTHING on the natural key). Rows without an
     * effective date, or without an announcement date, are skipped visibly (both are NOT NULL columns,
     * and a change with no announcement is useless for the ANNOUNCED-window promotion the hunter
     * anchors on). Fail-soft per row so a single bad change never fails the hunt. Returns the
     * {@code sp500} fetch result so its health can ride the RESPOND envelope.
     */
    private DataSourceResult<IndexChangeEvent> ingestAnnounced(int lookback) {
        DataSourceResult<IndexChangeEvent> primary = null;
        for (String index : INGEST_INDICES) {
            DataSourceResult<IndexChangeEvent> res = reference.indexChanges(index, lookback);
            for (IndexChangeEvent e : res.items()) {
                // Both dates back NOT NULL columns; skip visibly (WARN) rather than letting the
                // insert throw a swallowed DataIntegrityViolationException and vanish silently.
                if (e.effectiveDate() == null) {
                    log.warn("strigoi-index ingest dropped {} {} ({}): missing effectiveDate",
                            index, e.symbol(), e.action());
                    continue;
                }
                if (e.announcementDate() == null) {
                    log.warn("strigoi-index ingest dropped {} {} ({}): missing announcementDate",
                            index, e.symbol(), e.action());
                    continue;
                }
                try {
                    indexEventRepo.upsertAnnounced(e);
                } catch (RuntimeException ex) {
                    log.warn("strigoi-index ingest skipped {} {} ({}): {}",
                            index, e.symbol(), e.action(), ex.getMessage());
                }
            }
            if (PRIMARY_INDEX.equals(index)) primary = res;
        }
        return primary; // sp500 is always in INGEST_INDICES, so this is non-null
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        return handleFetch(auth, body);
    }

    /**
     * Promotion (event &rarr; prey): stamp the originating {@code index_event} row promoted for every
     * newly-persisted prey. Called from {@link HuntController#complete} after prey insertion, with only
     * the prey actually written this delivery. Mirrors {@code StrigoiSpinWebhookController.afterPersist}.
     *
     * <p><b>The logic-flip, enforced structurally.</b> Only an ANNOUNCED event whose forced-buy window
     * is still open can promote — the opposite of the old effective-date anchor. The hard gate is a pure
     * calendar fact:
     * <ol>
     *   <li>{@code status = ANNOUNCED} and {@code promoted_at IS NULL} — enforced by the SQL lookup
     *       {@link IndexEventRepository#findPromotableBySymbol}. EFFECTIVE/POST/CLOSED rows are never
     *       returned, so they can never be promoted; they exist only for run-up/reversal observation and
     *       pattern learning.</li>
     *   <li>{@code effective_date} strictly in the future — the zwangskauf window has not closed.</li>
     *   <li>{@code daysToEffective <= promotion-window-days}, chosen per source: {@code sp_press} uses
     *       the tight S&amp;P window (default 5), {@code russell_reconstitution} the wider Russell window
     *       (default 20).</li>
     * </ol>
     * The demand/liquidity numbers ({@code idiosyncraticVol}, {@code demandToAdvRatioEstimate}, ...) are
     * NOT part of this gate — they are noisy proxies/estimates and act as prompt-side confidence boosters
     * only, matching the spin lesson that {@code sizeRatio} is a booster, not a gate. A prey whose symbol
     * matches no promotable row is skipped fail-soft — the prey itself is already persisted regardless.
     */
    @Override
    protected void afterPersist(List<Prey> inserted, JsonNode body) {
        for (Prey p : inserted) {
            try {
                indexEventRepo.findPromotableBySymbol(p.symbol())
                        .filter(this::withinAnnouncedWindow)
                        .ifPresent(row -> {
                            if (indexEventRepo.markPromoted(row.id(), p.id())) {
                                log.info("strigoi-index promoted event {} ({}) -> prey {}",
                                        row.id(), row.symbol(), p.id());
                            }
                        });
            } catch (RuntimeException e) {
                // Fail-soft: the prey is already durably persisted; a promotion-marking failure on one
                // row must never fail the completion.
                log.debug("strigoi-index promotion skipped for prey {} ({}): {}",
                        p.id(), p.symbol(), e.getMessage());
            }
        }
    }

    /**
     * Calendar gate: the announcement&rarr;effective forced-buy window is still open. True only when the
     * {@code effective_date} is strictly in the future AND no more than the source-specific
     * promotion-window-days away. A row already at or past its effective date fails (the window has
     * closed — that is the whole point of the logic-flip). {@code status = ANNOUNCED} is already
     * guaranteed by {@link IndexEventRepository#findPromotableBySymbol}.
     */
    private boolean withinAnnouncedWindow(IndexEventRow row) {
        LocalDate eff = row.effectiveDate();
        if (eff == null) return false;
        LocalDate today = LocalDate.now();
        if (!eff.isAfter(today)) return false; // window already closed (effective today or past)
        long daysToEffective = ChronoUnit.DAYS.between(today, eff);
        return daysToEffective <= windowForSource(row.source());
    }

    /** Russell reconstitution gets the wider window; every other source (notably {@code sp_press}) the
     *  tight S&P window. */
    private int windowForSource(String source) {
        return RUSSELL_SOURCE.equals(source) ? promotionWindowDaysRussell : promotionWindowDaysSp;
    }
}
