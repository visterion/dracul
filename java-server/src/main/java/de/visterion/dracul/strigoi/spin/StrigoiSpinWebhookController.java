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
        var since = to.minusDays(lookback);

        // INGEST
        DataSourceResult<SpinoffFiling> raw = filings.searchSpinoffs(since, to);
        for (SpinCandidate c : screener.screen(raw.items())) spinRepo.upsertRegistered(c);

        // RECONCILE + ENRICH
        SpinLifecycleReconciler.ReconcileResult reconcile = reconciler.reconcile();
        enricher.enrich(reconcile);

        // RESPOND — the SAME window the ingest search used, so lookback_days actually reaches the
        // answer. It previously reached only the search while the payload was read back from the
        // whole active table, which made the parameter decorative.
        SpinPayload payload = enricher.payload(since);
        return new DataSourceResult<>(payload.candidates(), mergeHealth(raw.health(), payload));
    }

    /** ORs the response row cap into the ingest search's health. Two independent losses (Agora cut
     *  the filing search; the DB page cut the answer) must not overwrite each other, and an
     *  {@code unavailable} status passes through untouched — see
     *  {@link de.visterion.dracul.hunting.DataSourceHealth#degradedWith}. */
    static de.visterion.dracul.hunting.DataSourceHealth mergeHealth(
            de.visterion.dracul.hunting.DataSourceHealth agora, SpinPayload payload) {
        return de.visterion.dracul.hunting.DataSourceHealth.degradedWith(agora,
                "candidate list capped at " + SpinCandidateEnricher.RESPONSE_LIMIT
                        + " tracked rows for this window",
                false, payload.truncated());
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        return handleFetch(auth, body);
    }

    /**
     * D5 (#47): a completion's {@code output.terms} block carries the agent's OWN reading of
     * {@code recordDate}/{@code distributionDate}, each backed by a verbatim {@code evidence}
     * sentence. Applied here, before the inherited {@link HuntController#complete} runs its
     * prey processing — "terms" and "prey" are two independent parts of the same payload, and
     * the terms guard must run regardless of whether this delivery happens to carry any prey.
     *
     * <p><b>Fail-soft (fix-round-1, I-1).</b> Every other side effect on this path — {@link
     * #afterPersist}, the memory write-back in {@link HuntController#complete} — is deliberately
     * fail-soft: a best-effort annotation must never cost a whole night's prey. {@code
     * applyTerms} talks to the database ({@code findById}/{@code storeVerifiedDates}) and parses
     * caller-supplied dates, both of which can throw; a transient failure here is caught and
     * logged rather than turning into a 500 that makes Vistierie discard every prey this
     * completion would otherwise have persisted.
     */
    @Override
    @PostMapping("/complete")
    public ResponseEntity<Void> complete(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody JsonNode body) {
        if (!authorized(auth)) return ResponseEntity.status(401).build();
        try {
            applyTerms(body, runId);
        } catch (RuntimeException e) {
            log.warn("strigoi-spin run {} terms application failed unexpectedly — prey processing "
                    + "continues regardless: {}", runId, e.toString(), e);
        }
        return super.complete(auth, runId, body);
    }

    /**
     * Verifies and persists every entry of {@code output.terms} (schema:
     * {@code prey-list-spin.json}). Only entries whose evidence passes ALL FIVE
     * {@link TermEvidenceVerifier#supports} rules are written, per date field independently — a
     * candidate whose {@code id} matches no tracked row is skipped with no write at all.
     * No-op unless the run's status is {@code done}/{@code succeeded}, mirroring the gate the
     * inherited {@code complete} applies to prey.
     *
     * <p><b>Joined on {@code id}, not {@code symbol} (fix-round-1, I-2).</b> {@code
     * EnrichedSpinCandidate.symbol} is empty until the spin-co trades, yet REGISTERED /
     * WHEN_ISSUED rows are in the payload by design and the prompt asks the model for terms on
     * every candidate with {@code termSheetAvailable} — exactly those rows carry the most
     * valuable reading, the UPCOMING distribution date, and a symbol-only join could never
     * deliver it. {@code symbol} is kept in the payload/logs purely as a human-readable label.
     */
    private void applyTerms(JsonNode body, String runId) {
        String status = body.path("status").asText("");
        if (!"done".equals(status) && !"succeeded".equals(status)) return;
        JsonNode terms = body.path("output").path("terms");
        if (!terms.isArray()) return;

        int accepted = 0;
        int rejected = 0;
        int skippedNoText = 0;
        for (JsonNode term : terms) {
            Long id = longOrNull(term, "id");
            String label = term.path("symbol").asText("?");
            if (id == null) {
                log.info("strigoi-spin run {} terms: entry for {} has no usable id — ignored", runId, label);
                continue;
            }
            var rowOpt = spinRepo.findById(id);
            if (rowOpt.isEmpty()) {
                log.info("strigoi-spin run {} terms: id {} ({}) matches no tracked candidate — ignored",
                        runId, id, label);
                continue;
            }
            SpinCandidateRow row = rowOpt.get();
            String evidence = term.path("evidence").asText(null);

            // M-2: "no term sheet text to verify against" (a capture/source gap) must never share
            // a counter or a log line with "the evidence didn't check out" (the model made
            // something up) — the two mean completely different things for diagnosing this
            // feature, exactly like this project's data-source-health counters never share "no
            // data" with "source failed".
            if (row.termSheetText() == null || row.termSheetText().isBlank()) {
                skippedNoText++;
                log.info("strigoi-spin run {} terms: id {} ({}) has no term_sheet_text on file yet — "
                                + "reading cannot be verified (not a rejection; capture may not have "
                                + "completed)",
                        runId, id, label);
                continue;
            }

            LocalDate recordDate = null;
            String recordDateIso = isoOrNull(term, "recordDate");
            if (recordDateIso != null) {
                if (TermEvidenceVerifier.supports(row.termSheetText(), evidence, recordDateIso,
                        TermEvidenceVerifier.Field.RECORD_DATE)) {
                    recordDate = parseIsoOrNull(recordDateIso);
                    if (recordDate != null) accepted++;
                } else {
                    rejected++;
                    log.warn("strigoi-spin run {} rejected recordDate={} for id {} ({}): evidence not "
                                    + "verified against the stored term sheet",
                            runId, recordDateIso, id, label);
                }
            }

            LocalDate distributionDate = null;
            String distributionDateIso = isoOrNull(term, "distributionDate");
            if (distributionDateIso != null) {
                if (TermEvidenceVerifier.supports(row.termSheetText(), evidence, distributionDateIso,
                        TermEvidenceVerifier.Field.DISTRIBUTION_DATE)) {
                    distributionDate = parseIsoOrNull(distributionDateIso);
                    if (distributionDate != null) accepted++;
                } else {
                    rejected++;
                    log.warn("strigoi-spin run {} rejected distributionDate={} for id {} ({}): evidence "
                                    + "not verified against the stored term sheet",
                            runId, distributionDateIso, id, label);
                }
            }

            if (recordDate != null || distributionDate != null) {
                spinRepo.storeVerifiedDates(row.id(), recordDate, distributionDate);
            }
        }
        if (accepted > 0 || rejected > 0 || skippedNoText > 0) {
            log.info("strigoi-spin run {} terms: accepted={} rejected={} skippedNoText={}",
                    runId, accepted, rejected, skippedNoText);
        }
    }

    /** {@code null} for a missing field, an explicit JSON null, or a blank string — never the
     *  literal text {@code "null"}. */
    private static String isoOrNull(JsonNode term, String field) {
        JsonNode v = term.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    /** {@code null} for a missing/explicit-null/non-numeric {@code id} field. A JSON number is
     *  the schema-valid shape; a numeric string is accepted too, defensively, the same way
     *  {@link HuntController#lookbackDays} accepts a stringified tool argument. */
    private static Long longOrNull(JsonNode term, String field) {
        JsonNode v = term.get(field);
        if (v == null || v.isNull()) return null;
        if (v.canConvertToLong()) return v.asLong();
        if (v.isTextual()) {
            try {
                return Long.parseLong(v.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** M-1 defensive: {@link TermEvidenceVerifier#supports} already rejects a calendar-impossible
     *  ISO string (e.g. {@code 2026-02-30}) before this is ever called with {@code true}, but
     *  {@code LocalDate.parse} is guarded here too rather than trusted blindly — a parse failure
     *  is treated exactly like a rejection (nothing written, no field silently defaulted), not as
     *  an uncaught exception that would otherwise be caught by the outer fail-soft wrapper. */
    private static LocalDate parseIsoOrNull(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Promotion (candidate &rarr; prey): stamp the originating {@code spin_candidate} row promoted
     * for every newly-persisted prey. Called from {@link HuntController#complete} after prey
     * insertion, with only the prey actually written this delivery.
     *
     * <p><b>Role of this gate — idempotency marking, not the emit decision.</b> The LLM has already
     * decided what to emit from the RESPOND payload before this runs. What we do here is match each
     * emitted prey back to its tracked DISTRIBUTED candidate and mark it promoted, so it leaves
     * {@link SpinCandidateRepository#findActiveUnpromotedInWindow} and can never be re-emitted on a later
     * hunt (double-emission guard). Two layers make this exactly-once: the delivery-level filter in
     * {@code complete()} (only newly-inserted prey reach here, so a retried delivery marks nothing),
     * and the row-level {@code promoted_at IS NULL} CAS in {@link SpinCandidateRepository#markPromoted}.
     * The prey same-day natural-key unique index (V21) is the final backstop.
     *
     * <p><b>Promotion gate (deliberately relaxed from blueprint §5).</b> Hard conditions:
     * {@code status = DISTRIBUTED} and {@code promoted_at IS NULL} (both enforced by the SQL lookup),
     * a non-null {@code spincoMarketCapMillions} (reliably obtainable), {@code
     * distributionDateConfirmed = true} (the date anchoring {@code daysSinceDistribution} must be a
     * real term-sheet date, not the detection-timestamp fallback — see {@link
     * #withinPromotionWindow}), and {@code daysSinceDistribution <= promotion-window-days} (config,
     * default 90). {@code sizeRatio} is NOT a hard condition here — parent/sizeRatio are often
     * unresolvable, and gating on them would silence the hunter; sizeRatio is a confidence booster in
     * the prompt instead. A prey whose symbol matches no promotable row (untracked, already-promoted,
     * or failing the snapshot gate) is skipped fail-soft — the prey itself is already persisted
     * regardless.
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

    /** Snapshot gate: a non-null {@code spincoMarketCapMillions}, a CONFIRMED
     *  {@code distributionDateConfirmed}, and a {@code daysSinceDistribution} still inside the
     *  forced-selling window. All three are read from the persisted DISTRIBUTED snapshot (the same
     *  fields the LLM saw). A missing snapshot / missing fields fails the gate.
     *
     *  <p><b>Why the confirmed check (2026-08-08/09).</b> {@code daysSinceDistribution} is measured
     *  from the term-sheet distribution date when known, otherwise it falls back to
     *  {@code distributed_at} — the timestamp Dracul first OBSERVED the spin-co trading, not the
     *  market event. For a row whose DISTRIBUTED transition happened long after the real
     *  distribution (a backfill run, e.g. the 2026-08-08 ticker-backfill that stamped HONA, BSEM,
     *  ADIG, MBGL and MFP with {@code distributed_at = 2026-08-08} for filings from 2026-05-27 to
     *  2026-07-24), {@code daysSinceDistribution} reads 0 and the row would stay promotable until
     *  roughly the fallback timestamp + {@code promotionWindowDays}, months after the real forced-
     *  selling window closed. {@code distributionDateConfirmed} (see
     *  {@link SpinDistributionSnapshotter}) says whether the date is anchored to the term sheet
     *  rather than to detection; requiring it true closes that hole. Deliberately no fallback: a
     *  genuinely fresh spin-off whose term sheet carries no distribution date also will not be
     *  promoted until a later enrichment pass resolves one — the conservative direction, accepted by
     *  the operator. Missing key defaults to {@code false} (unconfirmed), matching how the enricher
     *  writes older/backfilled rows that never carried the flag at all.
     *
     *  <p><b>Visibility (2026-08-09).</b> Confirmed dates are currently rare-to-absent in prod
     *  ({@code SpinTermsParser} has not yet extracted a distribution date for any of the nine tracked
     *  candidates), so this gate is not a narrow edge case in practice — it is the thing deciding
     *  whether ANY candidate promotes right now, and a run where every row fails silently for this
     *  one reason must not read like a quiet night in the daily analysis (the failure class this
     *  project keeps getting bitten by). When a row clears every other condition — cap resolved,
     *  inside the window — and fails ONLY on the confirmed flag, that is logged at INFO so it is
     *  distinguishable from an ordinary/expected non-promotion (cap missing, window expired) and from
     *  an error. Rows failing an earlier condition do not reach this log — one extra line per
     *  otherwise-eligible candidate, not one per hunt. */
    private boolean withinPromotionWindow(SpinCandidateRow row) {
        JsonNode dist = row.distributedSnapshot();
        if (dist == null) return false;
        if (!dist.path("spincoMarketCapMillions").isNumber()) return false;
        JsonNode days = dist.path("daysSinceDistribution");
        if (!(days.isNumber() && days.asInt() <= promotionWindowDays)) return false;
        if (!dist.path("distributionDateConfirmed").asBoolean(false)) {
            log.info("strigoi-spin candidate {} ({}) would promote (cap resolved, {} days within the "
                            + "{}-day window) but distributionDateConfirmed=false — deliberately held back, "
                            + "not an error",
                    row.id(), row.symbol(), days.asInt(), promotionWindowDays);
            return false;
        }
        return true;
    }
}
