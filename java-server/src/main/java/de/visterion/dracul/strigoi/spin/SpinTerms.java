package de.visterion.dracul.strigoi.spin;

/** Structured distribution terms extracted from a spin-off information statement's term-sheet
 *  text by {@link SpinTermsParser}. Any component may be null when the term sheet doesn't
 *  contain a recognizable pattern for it — including, always, {@link #recordDate} and
 *  {@link #distributionDate}: the parser no longer extracts either, see
 *  {@link SpinTermsParser}'s class javadoc for why. Those two fields are populated later, if at
 *  all, from the agent's evidence-verified reading of the same term-sheet text. */
public record SpinTerms(
        String distributionRatio,
        String recordDate,
        String distributionDate
) {}
