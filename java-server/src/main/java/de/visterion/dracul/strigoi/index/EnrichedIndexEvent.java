package de.visterion.dracul.strigoi.index;

import java.math.BigDecimal;
import java.util.List;

/**
 * A tracked index-reconstitution event rendered to the wire shape returned by the tool webhook,
 * built from a persisted {@code index_event} row (V27) plus its per-stage JSONB snapshots — the
 * DB-backed replacement for the old {@code EnrichedIndexCandidate} (which read straight from the
 * live S&P constituents list). No price on the event itself: the inclusion thesis is forced
 * index-fund demand within the announcement&rarr;effective window.
 *
 * <p><b>Stage-gated, additive fields (all nullable until the row reaches the stage).</b> A row
 * progresses ANNOUNCED &rarr; EFFECTIVE &rarr; POST (&rarr; CLOSED / ABANDONED), and each stage
 * fills its own block; earlier stages' fields stay populated, later stages' stay null. So a null
 * field means "that stage's data is not yet available" — never a judgement. The response set is
 * limited to unpromoted {ANNOUNCED, EFFECTIVE, POST} rows.
 *
 * <p>The stage-gated numbers are filled by the two enrichment snapshotters
 * ({@link IndexDemandSnapshotter} / {@link IndexDriftSnapshotter}) via {@link IndexEventEnricher};
 * a field reads back null only when its stage's snapshot has not (yet) resolved for that row.
 *
 * <ul>
 *   <li><b>Base / identity</b> — {@code symbol}, {@code companyName}, {@code index}, {@code action}
 *       ("add"/"remove"), {@code source} ("sp_press"/"russell_reconstitution"),
 *       {@code announcementDate}, {@code effectiveDate}, {@code status} (the {@link IndexEventStatus}
 *       name). These replace the old single {@code dateAdded} anchor — the logic-flip is that the
 *       hunter now judges the {@code today &rarr; effectiveDate} window, not a past addition date.</li>
 *   <li><b>ANNOUNCED (demand)</b> — {@code adv} (avg daily dollar volume, 20d), {@code marketCap},
 *       {@code avgVolume20d} (avg daily share volume, 20d; carried over from the old enrichment),
 *       {@code idiosyncraticVol}, {@code freeFloatProxyMillions}, {@code demandToAdvRatioEstimate}
 *       (all coarse proxies/estimates, so named), and {@code confounders} (dilution/M&amp;A/etc.
 *       overlaps from the confounder screen; null/empty until G5).</li>
 *   <li><b>EFFECTIVE / POST (drift)</b> — {@code runUpPct} (announcement&rarr;effective),
 *       {@code postEffectivePct} (effective&rarr;latest), {@code reversalObserved} (opposite signs
 *       past a noise threshold), {@code daysSinceEffective}.</li>
 * </ul>
 */
public record EnrichedIndexEvent(
        String symbol,
        String companyName,
        String index,
        String action,
        String source,
        String announcementDate,
        String effectiveDate,
        String status,
        // ANNOUNCED (demand) stage
        BigDecimal adv,
        Double marketCap,
        Long avgVolume20d,
        Double idiosyncraticVol,
        Double freeFloatProxyMillions,
        Double demandToAdvRatioEstimate,
        List<String> confounders,
        // EFFECTIVE / POST (drift) stage
        Double runUpPct,
        Double postEffectivePct,
        Boolean reversalObserved,
        Integer daysSinceEffective
) {}
