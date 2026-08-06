package de.visterion.dracul.hunting.agora;

/**
 * Outcome of one cheap 52-week-range probe: the range if there is one, and — when there is not —
 * WHY, because the two reasons must be reported differently.
 *
 * <p><b>Why this is not just a nullable {@link PriceRange}.</b> Production, 2026-08-05: the lazarus
 * pre-filter screened all 490 S&amp;P 500 members it was handed, lost exactly three (FDXF, HONA, Q)
 * and reported {@code partial=true} — which makes the daily analysis raise its "the hunter got an
 * incomplete answer" alarm. But those three are simply younger than 52 weeks (Q, Qnity Electronics,
 * first traded 2025-10-27): their 52-week range is not computable and will not be until they age.
 * A permanent property of an instrument is not a data-source degradation, and an alarm that fires
 * every single night for it stops being an alarm.
 *
 * <p>The distinction only became readable at commit e5db10be, which made the MCP {@code isError}
 * envelope the SOLE outage discriminator in {@code AgoraClient.parseToolText}. Before that a young
 * symbol and an unreachable Agora arrived through the same channel and merging them was the only
 * honest option; now a young symbol comes back as a normal body with {@code available:false} while
 * an outage still throws {@code AgoraUnavailableException}.
 */
public record RangeProbe(PriceRange range, RangeProbe.Kind kind) {

    public enum Kind {
        /** A usable 52-week range; {@link #range()} is non-null. */
        OK,
        /**
         * Agora answered correctly and the answer is "this instrument has no 52-week window yet".
         * Not a degradation — nothing failed and a retry cannot change it.
         */
        NOT_ELIGIBLE,
        /**
         * Agora answered, but with something the screen cannot use: no current close, a
         * non-positive low, or a body that does not carry the spec that was asked for. Something
         * IS wrong upstream, so this counts as a degradation.
         */
        UNUSABLE
    }

    public static RangeProbe of(PriceRange range) {
        return new RangeProbe(range, Kind.OK);
    }

    public static RangeProbe notEligible() {
        return new RangeProbe(null, Kind.NOT_ELIGIBLE);
    }

    public static RangeProbe unusable() {
        return new RangeProbe(null, Kind.UNUSABLE);
    }
}
