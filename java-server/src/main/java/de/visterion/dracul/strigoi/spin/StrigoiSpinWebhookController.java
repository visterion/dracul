package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.SpinoffFiling;
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
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(value = "dracul.strigoi.spin.enabled", havingValue = "true")
@RequestMapping("/api/strigoi-spin")
public class StrigoiSpinWebhookController extends HuntController {

    private final AgoraFilings filings;
    private final SpinoffScreener screener;
    private final SpinCandidateRepository spinRepo;
    private final SpinLifecycleReconciler reconciler;
    private final SpinCandidateEnricher enricher;
    private final int defaultLookback;
    private final int promotionWindowDays;

    private static final Logger log = LoggerFactory.getLogger(StrigoiSpinWebhookController.class);

    public StrigoiSpinWebhookController(
            @Value("${dracul.strigoi.spin.webhook-token}") String token,
            AgoraFilings filings,
            SpinoffScreener screener,
            SpinCandidateRepository spinRepo,
            SpinLifecycleReconciler reconciler,
            SpinCandidateEnricher enricher,
            PreyRepository preyRepo,
            ToolFetchCache cache,
            HiveMemResearchService memory,
            ResearchMemoryLinkRepository memoryLinks,
            @Value("${dracul.strigoi.spin.lookback-days:60}") int defaultLookback,
            @Value("${dracul.strigoi.spin.promotion-window-days:90}") int promotionWindowDays) {
        super(token, preyRepo, cache, memory, memoryLinks);
        this.filings = filings;
        this.screener = screener;
        this.spinRepo = spinRepo;
        this.reconciler = reconciler;
        this.enricher = enricher;
        this.defaultLookback = defaultLookback;
        this.promotionWindowDays = promotionWindowDays;
    }

    @Override protected String agentName() { return "strigoi-spin"; }
    @Override protected String defaultAnomalyType() { return "SPINOFF"; }
    @Override protected String defaultHorizon() { return "6m"; }
    @Override protected boolean skipBlankSymbol() { return true; }
    @Override protected String toolName() { return "fetch_recent_spinoff_candidates"; }

    /**
     * The four-phase lifecycle hunt (blueprint §3), on the same webhook cron:
     * <ol>
     *   <li><b>INGEST</b> — {@code searchSpinoffs} + screener, upsert each spin-co as a REGISTERED row
     *       (idempotent on the natural key).</li>
     *   <li><b>RECONCILE</b> — {@link SpinLifecycleReconciler}: calendar transitions (0 calls) + one
     *       batched quote probe.</li>
     *   <li><b>ENRICH</b> — {@link SpinCandidateEnricher}: stage-appropriate snapshots for a bounded
     *       set of freshly-transitioned + due-for-recheck rows.</li>
     *   <li><b>RESPOND</b> — build the {@link EnrichedSpinCandidate} payload from the persisted
     *       active, unpromoted rows (replaces the old read-straight-from-the-live-search path). The
     *       data-source health of the ingest search rides the response as before.</li>
     * </ol>
     */
    @Override
    protected DataSourceResult<?> hunt(Map<String, Object> body) {
        int lookback = lookbackDays(body, defaultLookback, 1, 90);
        var to = LocalDate.now();

        // INGEST
        DataSourceResult<SpinoffFiling> raw = filings.searchSpinoffs(to.minusDays(lookback), to);
        for (SpinCandidate c : screener.screen(raw.items())) spinRepo.upsertRegistered(c);

        // RECONCILE + ENRICH
        SpinLifecycleReconciler.ReconcileResult reconcile = reconciler.reconcile();
        enricher.enrich(reconcile);

        // RESPOND
        return new DataSourceResult<>(enricher.payload(), raw.health());
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Map<String, Object> body) {
        return handleFetch(auth, body);
    }

    /**
     * Promotion (candidate &rarr; prey): stamp the originating {@code spin_candidate} row promoted
     * for every newly-persisted prey. Called from {@link HuntController#complete} after prey
     * insertion, with only the prey actually written this delivery.
     *
     * <p><b>Role of this gate — idempotency marking, not the emit decision.</b> The LLM has already
     * decided what to emit from the RESPOND payload before this runs. What we do here is match each
     * emitted prey back to its tracked DISTRIBUTED candidate and mark it promoted, so it leaves
     * {@link SpinCandidateRepository#findActiveUnpromoted} and can never be re-emitted on a later
     * hunt (double-emission guard). Two layers make this exactly-once: the delivery-level filter in
     * {@code complete()} (only newly-inserted prey reach here, so a retried delivery marks nothing),
     * and the row-level {@code promoted_at IS NULL} CAS in {@link SpinCandidateRepository#markPromoted}.
     * The prey same-day natural-key unique index (V21) is the final backstop.
     *
     * <p><b>Promotion gate (deliberately relaxed from blueprint §5).</b> Hard conditions:
     * {@code status = DISTRIBUTED} and {@code promoted_at IS NULL} (both enforced by the SQL lookup),
     * a non-null {@code spincoMarketCapMillions} (reliably obtainable), and
     * {@code daysSinceDistribution <= promotion-window-days} (config, default 90). {@code sizeRatio}
     * is NOT a hard condition here — parent/sizeRatio are often unresolvable, and gating on them
     * would silence the hunter; sizeRatio is a confidence booster in the prompt instead. A prey whose
     * symbol matches no promotable row (untracked, already-promoted, or failing the snapshot gate) is
     * skipped fail-soft — the prey itself is already persisted regardless.
     */
    @Override
    protected void afterPersist(List<Prey> inserted, JsonNode body) {
        for (Prey p : inserted) {
            try {
                spinRepo.findPromotableBySymbol(p.symbol())
                        .filter(this::withinPromotionWindow)
                        .ifPresent(row -> {
                            if (spinRepo.markPromoted(row.id(), p.id())) {
                                log.info("strigoi-spin promoted candidate {} ({}) -> prey {}",
                                        row.id(), row.symbol(), p.id());
                            }
                        });
            } catch (RuntimeException e) {
                // Fail-soft: the prey is already durably persisted; a promotion-marking failure on
                // one row must never fail the completion.
                log.debug("strigoi-spin promotion skipped for prey {} ({}): {}",
                        p.id(), p.symbol(), e.getMessage());
            }
        }
    }

    /** Snapshot gate: a non-null {@code spincoMarketCapMillions} and a {@code daysSinceDistribution}
     *  still inside the forced-selling window. Both are read from the persisted DISTRIBUTED snapshot
     *  (the same fields the LLM saw). A missing snapshot / missing fields fails the gate. */
    private boolean withinPromotionWindow(SpinCandidateRow row) {
        JsonNode dist = row.distributedSnapshot();
        if (dist == null) return false;
        if (!dist.path("spincoMarketCapMillions").isNumber()) return false;
        JsonNode days = dist.path("daysSinceDistribution");
        return days.isNumber() && days.asInt() <= promotionWindowDays;
    }
}
